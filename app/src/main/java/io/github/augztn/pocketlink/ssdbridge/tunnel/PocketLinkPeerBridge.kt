package io.github.augztn.pocketlink.ssdbridge.tunnel

import android.os.Build
import android.util.Base64
import io.github.augztn.pocketlink.ssdbridge.BridgeService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class DiscoveredHostEndpoint(
    val subdomain: String,
    val publicUrl: String,
    val hostIp: String,
    val port: Int,
    val instanceId: String,
    val deviceName: String,
    val discoveredAtMs: Long = System.currentTimeMillis(),
) {
    val baseUrl: String get() = "http://$hostIp:$port"
}

/**
 * Zero-configuration Device-to-Device Tunnel & Discovery Bridge for PocketLink 1-Click Cloudflare URLs
 * (`https://pocketlink-<id>.trycloudflare.com`).
 *
 * Combines two automatic transport layers so a Client phone (e.g. Realme) always connects to the
 * real Host phone (e.g. Samsung) — and never to itself:
 * 1. Direct LAN / Wi-Fi / Hotspot / Tailscale transport discovered via UDP broadcast beacons (port 48765),
 *    WSS presence exchange, and fast parallel /24 subnet health probing.
 * 2. Outbound MQTT-over-WSS Cloud Relay transport (`wss://broker.emqx.io:8084/mqtt` with failover to
 *    `wss://broker.hivemq.com:8884/mqtt`) that tunnels HTTP requests and byte-range media streams
 *    across different networks, mobile data, or routers with AP client isolation.
 */
object PocketLinkPeerBridge {
    private const val UDP_DISCOVERY_PORT = 48765
    private const val MAX_RELAY_CHUNK_BYTES = 256 * 1024

    private val relayBrokers = listOf(
        "wss://broker.emqx.io:8084/mqtt",
        "wss://broker.hivemq.com:8884/mqtt",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hostRunning = AtomicBoolean(false)

    @Volatile private var activeHostSubdomain: String = ""
    @Volatile private var activeHostPort: Int = 8000
    @Volatile private var udpServerJob: Job? = null
    @Volatile private var udpBeaconJob: Job? = null
    @Volatile private var hostRelayJob: Job? = null
    @Volatile private var hostRelaySocket: WebSocket? = null

    private val directEndpointCache = ConcurrentHashMap<String, DiscoveredHostEndpoint>()
    private val recentLanHosts = ConcurrentHashMap<String, DiscoveredHostEndpoint>()

    private val fastProbeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(450, TimeUnit.MILLISECONDS)
        .readTimeout(650, TimeUnit.MILLISECONDS)
        .writeTimeout(650, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val localLoopbackClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val wssRelayClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun startHostServices(subdomain: String, localPort: Int) {
        val cleanSub = subdomain.trim().lowercase(Locale.ROOT)
        if (cleanSub.isBlank()) return
        activeHostSubdomain = cleanSub
        activeHostPort = localPort
        if (hostRunning.getAndSet(true)) {
            publishHostPresenceUpdate()
            return
        }
        startUdpDiscoveryResponder()
        startUdpBeaconBroadcaster()
        startHostWssRelay()
    }

    fun stopHostServices() {
        hostRunning.set(false)
        udpServerJob?.cancel()
        udpServerJob = null
        udpBeaconJob?.cancel()
        udpBeaconJob = null
        hostRelayJob?.cancel()
        hostRelayJob = null
        runCatching { hostRelaySocket?.close(1000, "Host stopped") }
        hostRelaySocket = null
    }

    private fun buildHostInfoJson(): JSONObject {
        val port = BridgeService.activeApiPort.takeIf { it > 0 } ?: activeHostPort
        val sub = activeHostSubdomain.ifBlank {
            TunnelManager.currentAutoSubdomain().orEmpty()
        }
        val ipsArray = JSONArray()
        getLocalIpv4Addresses().forEach { ipsArray.put(it) }
        return JSONObject()
            .put("type", "pocketlink_host_beacon")
            .put("subdomain", sub)
            .put("public_url", "https://$sub.trycloudflare.com")
            .put("instance", BridgeService.activeInstanceId)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("port", port)
            .put("ips", ipsArray)
            .put("ts", System.currentTimeMillis())
    }

    private fun startUdpDiscoveryResponder() {
        udpServerJob?.cancel()
        udpServerJob = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(UDP_DISCOVERY_PORT))
                }
                val buf = ByteArray(2048)
                while (isActive && hostRunning.get()) {
                    val packet = DatagramPacket(buf, buf.size)
                    runCatching { socket.receive(packet) }.onSuccess {
                        val raw = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@onSuccess
                        when (json.optString("type")) {
                            "pocketlink_discover" -> {
                                if (BridgeService.apiRunning) {
                                    val replyBytes = buildHostInfoJson().toString().toByteArray(Charsets.UTF_8)
                                    val reply = DatagramPacket(
                                        replyBytes,
                                        replyBytes.size,
                                        packet.address,
                                        packet.port
                                    )
                                    runCatching { socket.send(reply) }
                                }
                            }
                            "pocketlink_host_beacon" -> {
                                parseBeaconIntoCache(json, packet.address?.hostAddress)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Port 48765 may already be bound
            } finally {
                runCatching { socket?.close() }
            }
        }
    }

    private fun startUdpBeaconBroadcaster() {
        udpBeaconJob?.cancel()
        udpBeaconJob = scope.launch {
            while (isActive && hostRunning.get()) {
                if (BridgeService.apiRunning) {
                    runCatching {
                        val payload = buildHostInfoJson().toString().toByteArray(Charsets.UTF_8)
                        DatagramSocket().use { sock ->
                            sock.broadcast = true
                            val targets = getBroadcastAddresses()
                            for (addr in targets) {
                                val pkt = DatagramPacket(payload, payload.size, addr, UDP_DISCOVERY_PORT)
                                runCatching { sock.send(pkt) }
                            }
                        }
                    }
                }
                delay(4_000L)
            }
        }
    }

    private fun parseBeaconIntoCache(json: JSONObject, senderIp: String?): DiscoveredHostEndpoint? {
        val instance = json.optString("instance")
        if (instance.isBlank() || instance == BridgeService.activeInstanceId) return null
        val sub = json.optString("subdomain").trim().lowercase(Locale.ROOT)
        val port = json.optInt("port", 8000).takeIf { it in 1..65535 } ?: 8000
        val device = json.optString("device", "PocketLink Host")
        val myIps = getLocalIpv4Addresses().toSet()

        val candidateIps = mutableListOf<String>()
        if (!senderIp.isNullOrBlank() && senderIp !in myIps && senderIp != "127.0.0.1") {
            candidateIps.add(senderIp)
        }
        val arr = json.optJSONArray("ips")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val ip = arr.optString(i).trim()
                if (ip.isNotBlank() && ip !in myIps && ip !in candidateIps) {
                    candidateIps.add(ip)
                }
            }
        }
        val bestIp = candidateIps.firstOrNull() ?: return null
        val publicUrl = json.optString("public_url").ifBlank {
            if (sub.isNotBlank()) "https://$sub.trycloudflare.com" else "http://$bestIp:$port"
        }
        val endpoint = DiscoveredHostEndpoint(
            subdomain = sub,
            publicUrl = publicUrl,
            hostIp = bestIp,
            port = port,
            instanceId = instance,
            deviceName = device,
        )
        if (sub.isNotBlank()) {
            recentLanHosts[sub] = endpoint
            directEndpointCache[sub] = endpoint
        }
        recentLanHosts[bestIp] = endpoint
        return endpoint
    }

    private fun startHostWssRelay() {
        hostRelayJob?.cancel()
        hostRelayJob = scope.launch {
            var brokerIdx = 0
            while (isActive && hostRunning.get()) {
                val sub = activeHostSubdomain.ifBlank { TunnelManager.currentAutoSubdomain().orEmpty() }
                if (sub.isBlank()) {
                    delay(2_000L)
                    continue
                }
                val brokerUrl = relayBrokers[brokerIdx % relayBrokers.size]
                brokerIdx++
                val closedDeferred = CompletableDeferred<Unit>()
                val reqTopic = "pocketlink/v2/host/$sub/req"
                val infoTopic = "pocketlink/v2/host/$sub/info"
                val clientId = "plh-${sub.takeLast(8)}-${UUID.randomUUID().toString().take(6)}"

                val wsReq = Request.Builder()
                    .url(brokerUrl)
                    .header("Sec-WebSocket-Protocol", "mqtt")
                    .build()

                val ws = wssRelayClient.newWebSocket(wsReq, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(MqttWire.encodeConnect(clientId))
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val packets = MqttWire.decodePackets(bytes.toByteArray())
                        for (pkt in packets) {
                            when (pkt.type) {
                                MqttWire.TYPE_CONNACK -> {
                                    webSocket.send(MqttWire.encodeSubscribe(1, reqTopic))
                                    val infoPayload = buildHostInfoJson().toString().toByteArray(Charsets.UTF_8)
                                    webSocket.send(MqttWire.encodePublish(infoTopic, infoPayload, retain = true))
                                }
                                MqttWire.TYPE_PUBLISH -> {
                                    if (pkt.topic == reqTopic) {
                                        scope.launch {
                                            handleIncomingRelayHttpRequest(webSocket, pkt.payload)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closedDeferred.complete(Unit)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        closedDeferred.complete(Unit)
                    }
                })
                hostRelaySocket = ws

                val pingJob = scope.launch {
                    while (isActive && !closedDeferred.isCompleted && hostRunning.get()) {
                        delay(20_000L)
                        if (!closedDeferred.isCompleted) {
                            ws.send(MqttWire.encodePingReq())
                            if (BridgeService.apiRunning) {
                                val infoPayload = buildHostInfoJson().toString().toByteArray(Charsets.UTF_8)
                                ws.send(MqttWire.encodePublish(infoTopic, infoPayload, retain = true))
                            }
                        }
                    }
                }

                closedDeferred.await()
                pingJob.cancel()
                if (hostRunning.get()) {
                    delay(3_000L)
                }
            }
        }
    }

    private fun publishHostPresenceUpdate() {
        val ws = hostRelaySocket ?: return
        val sub = activeHostSubdomain.ifBlank { TunnelManager.currentAutoSubdomain().orEmpty() }
        if (sub.isBlank() || !BridgeService.apiRunning) return
        runCatching {
            val infoTopic = "pocketlink/v2/host/$sub/info"
            val infoPayload = buildHostInfoJson().toString().toByteArray(Charsets.UTF_8)
            ws.send(MqttWire.encodePublish(infoTopic, infoPayload, retain = true))
        }
    }

    private fun handleIncomingRelayHttpRequest(webSocket: WebSocket, payloadBytes: ByteArray) {
        val reqJson = runCatching { JSONObject(String(payloadBytes, Charsets.UTF_8)) }.getOrNull() ?: return
        val reqId = reqJson.optString("req_id")
        val replyTopic = reqJson.optString("reply_topic")
        if (reqId.isBlank() || replyTopic.isBlank()) return

        val method = reqJson.optString("method", "GET").uppercase(Locale.US)
        val rawPath = reqJson.optString("path", "/").let { if (it.startsWith("/")) it else "/$it" }
        val port = BridgeService.activeApiPort.takeIf { it > 0 } ?: activeHostPort
        val localUrl = "http://127.0.0.1:$port$rawPath"

        val reqBuilder = Request.Builder().url(localUrl)
        val hdrObj = reqJson.optJSONObject("headers")
        var incomingRange: String? = null
        if (hdrObj != null) {
            val keys = hdrObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val lower = k.lowercase(Locale.US)
                if (lower == "host" || lower == "connection" || lower == "accept-encoding") continue
                val v = hdrObj.optString(k)
                if (lower == "range") incomingRange = v
                reqBuilder.header(k, v)
            }
        }
        // Bound open-ended media stream ranges so WebSocket relay frames stay fast and compact
        if (incomingRange != null && incomingRange.startsWith("bytes=", ignoreCase = true)) {
            val spec = incomingRange.substringAfter("=", "").trim()
            val startStr = spec.substringBefore("-", "")
            val endStr = spec.substringAfter("-", "")
            val startByte = startStr.toLongOrNull()
            val endByte = endStr.toLongOrNull()
            if (startByte != null && (endByte == null || endByte - startByte > MAX_RELAY_CHUNK_BYTES)) {
                val boundedEnd = startByte + MAX_RELAY_CHUNK_BYTES - 1
                reqBuilder.header("Range", "bytes=$startByte-$boundedEnd")
            }
        } else if (rawPath.contains("/stream") || rawPath.contains("/download")) {
            reqBuilder.header("Range", "bytes=0-${MAX_RELAY_CHUNK_BYTES - 1}")
        }

        reqBuilder.header("X-Forwarded-Proto", "https")
        reqBuilder.header("X-PocketLink-Auto-Tunnel", "1")
        reqBuilder.header("Accept-Encoding", "identity")

        val bodyB64 = reqJson.optString("body_b64", "")
        val reqBodyBytes = if (bodyB64.isNotEmpty()) {
            runCatching { Base64.decode(bodyB64, Base64.DEFAULT) }.getOrDefault(ByteArray(0))
        } else ByteArray(0)

        val contentType = hdrObj?.optString("Content-Type")?.takeIf { it.isNotBlank() }?.toMediaTypeOrNull()
            ?: hdrObj?.optString("content-type")?.takeIf { it.isNotBlank() }?.toMediaTypeOrNull()

        val hasBodyMethod = method == "POST" || method == "PUT" || method == "PATCH" || method == "DELETE"
        val body = when {
            method == "GET" || method == "HEAD" -> null
            reqBodyBytes.isNotEmpty() -> reqBodyBytes.toRequestBody(contentType)
            hasBodyMethod -> ByteArray(0).toRequestBody(contentType)
            else -> null
        }
        reqBuilder.method(method, body)

        try {
            localLoopbackClient.newCall(reqBuilder.build()).execute().use { res ->
                val resHeaders = JSONObject()
                for (i in 0 until res.headers.size) {
                    val name = res.headers.name(i)
                    resHeaders.put(name, res.headers.value(i))
                }
                val rawBytes = if (method == "HEAD") {
                    ByteArray(0)
                } else {
                    res.body?.byteStream()?.use { stream ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(16 * 1024)
                        var total = 0
                        while (total < MAX_RELAY_CHUNK_BYTES + 65536) {
                            val r = stream.read(buf)
                            if (r <= 0) break
                            out.write(buf, 0, r)
                            total += r
                        }
                        out.toByteArray()
                    } ?: ByteArray(0)
                }

                TunnelManager.recordTrafficIfInitialized(rawBytes.size.toLong() + reqBodyBytes.size)
                val resJson = JSONObject()
                    .put("req_id", reqId)
                    .put("status", res.code)
                    .put("message", res.message)
                    .put("headers", resHeaders)
                    .put("body_b64", Base64.encodeToString(rawBytes, Base64.NO_WRAP))

                webSocket.send(
                    MqttWire.encodePublish(
                        topic = replyTopic,
                        payload = resJson.toString().toByteArray(Charsets.UTF_8),
                        retain = false,
                    )
                )
            }
        } catch (e: Exception) {
            val errBody = JSONObject().put("detail", e.message ?: "Host local server error").toString()
            val resJson = JSONObject()
                .put("req_id", reqId)
                .put("status", 502)
                .put("message", "Bad Gateway")
                .put("headers", JSONObject().put("Content-Type", "application/json"))
                .put("body_b64", Base64.encodeToString(errBody.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            webSocket.send(
                MqttWire.encodePublish(
                    topic = replyTopic,
                    payload = resJson.toString().toByteArray(Charsets.UTF_8),
                    retain = false,
                )
            )
        }
    }

    /**
     * Discovers any active remote PocketLink Host device on the local network (excluding this device).
     */
    suspend fun discoverAnyRemoteHost(): DiscoveredHostEndpoint? = coroutineScope {
        val recent = recentLanHosts.values
            .filter { it.instanceId != BridgeService.activeInstanceId }
            .maxByOrNull { it.discoveredAtMs }
        if (recent != null && verifyDirectEndpoint(recent.hostIp, recent.port, null) != null) {
            return@coroutineScope recent
        }

        val udpDeferred = async(Dispatchers.IO) { queryUdpBroadcast(targetSubdomain = null) }
        val subnetDeferred = async(Dispatchers.IO) { scanLocalSubnets(targetSubdomain = null) }
        udpDeferred.await() ?: subnetDeferred.await()
    }

    /**
     * Resolves and executes an OkHttp request targeting a remote `https://pocketlink-<id>.trycloudflare.com`
     * Host device. First tries direct Wi-Fi/LAN/Tailscale HTTP to the Host phone; if unreachable,
     * tunnels the HTTP request over the encrypted WSS Cloud Relay.
     */
    fun executeRemoteTunnelRequest(
        chain: Interceptor.Chain,
        authedReq: Request,
        targetSubdomain: String,
    ): Response {
        val cleanSub = targetSubdomain.trim().lowercase(Locale.ROOT)
        val cached = directEndpointCache[cleanSub]
        if (cached != null && cached.instanceId != BridgeService.activeInstanceId) {
            try {
                return proceedDirectToHost(chain, authedReq, cached)
            } catch (_: IOException) {
                directEndpointCache.remove(cleanSub)
            }
        }

        val discovered = runBlocking(Dispatchers.IO) {
            resolveRemoteHostEndpoint(cleanSub)
        }
        if (discovered != null && discovered.instanceId != BridgeService.activeInstanceId) {
            directEndpointCache[cleanSub] = discovered
            try {
                return proceedDirectToHost(chain, authedReq, discovered)
            } catch (_: IOException) {
                directEndpointCache.remove(cleanSub)
            }
        }

        // Cross-network / AP-isolated fallback: execute HTTP request over the WSS Cloud Relay
        return runBlocking(Dispatchers.IO) {
            executeViaWssRelay(authedReq, cleanSub)
        } ?: throw IOException(
            "Could not reach Host device ($cleanSub.trycloudflare.com). " +
                "Make sure PocketLink Host Server & Cloudflare Tunnel are turned ON on the Host phone."
        )
    }

    private fun proceedDirectToHost(
        chain: Interceptor.Chain,
        authedReq: Request,
        endpoint: DiscoveredHostEndpoint,
    ): Response {
        val bridgedUrl = authedReq.url.newBuilder()
            .scheme("http")
            .host(endpoint.hostIp)
            .port(endpoint.port)
            .build()
        val bridgedReq = authedReq.newBuilder()
            .url(bridgedUrl)
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Host", "${endpoint.subdomain.ifBlank { "pocketlink" }}.trycloudflare.com")
            .header("X-PocketLink-Auto-Tunnel", "1")
            .build()
        val res = chain.proceed(bridgedReq)
        val bodyLen = res.header("Content-Length")?.toLongOrNull() ?: 512L
        TunnelManager.recordTrafficIfInitialized(bodyLen)
        return res
    }

    suspend fun resolveRemoteHostEndpoint(targetSubdomain: String): DiscoveredHostEndpoint? = coroutineScope {
        val cleanSub = targetSubdomain.trim().lowercase(Locale.ROOT)
        directEndpointCache[cleanSub]?.let { cached ->
            if (cached.instanceId != BridgeService.activeInstanceId &&
                verifyDirectEndpoint(cached.hostIp, cached.port, cleanSub) != null
            ) {
                return@coroutineScope cached
            }
        }

        val udpJob = async(Dispatchers.IO) { queryUdpBroadcast(cleanSub) }
        val wssInfoJob = async(Dispatchers.IO) { fetchHostInfoViaWssAndProbeLan(cleanSub) }
        val subnetJob = async(Dispatchers.IO) { scanLocalSubnets(cleanSub) }

        val udpRes = udpJob.await()
        if (udpRes != null) {
            directEndpointCache[cleanSub] = udpRes
            return@coroutineScope udpRes
        }
        val wssLanRes = wssInfoJob.await()
        if (wssLanRes != null) {
            directEndpointCache[cleanSub] = wssLanRes
            return@coroutineScope wssLanRes
        }
        val subnetRes = subnetJob.await()
        if (subnetRes != null) {
            directEndpointCache[cleanSub] = subnetRes
            return@coroutineScope subnetRes
        }
        null
    }

    private fun queryUdpBroadcast(targetSubdomain: String?): DiscoveredHostEndpoint? = runCatching {
        DatagramSocket().use { sock ->
            sock.broadcast = true
            sock.soTimeout = 350
            val query = JSONObject()
                .put("type", "pocketlink_discover")
                .put("subdomain", targetSubdomain.orEmpty())
                .toString()
                .toByteArray(Charsets.UTF_8)
            for (addr in getBroadcastAddresses()) {
                val pkt = DatagramPacket(query, query.size, addr, UDP_DISCOVERY_PORT)
                runCatching { sock.send(pkt) }
            }
            val started = System.currentTimeMillis()
            val buf = ByteArray(2048)
            var fallbackMatch: DiscoveredHostEndpoint? = null
            while (System.currentTimeMillis() - started < 360L) {
                val reply = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(reply)
                } catch (_: Exception) {
                    break
                }
                val raw = String(reply.data, reply.offset, reply.length, Charsets.UTF_8)
                val json = runCatching { JSONObject(raw) }.getOrNull() ?: continue
                val parsed = parseBeaconIntoCache(json, reply.address?.hostAddress) ?: continue
                if (targetSubdomain.isNullOrBlank() || parsed.subdomain.equals(targetSubdomain, ignoreCase = true)) {
                    return@runCatching parsed
                }
                if (fallbackMatch == null) fallbackMatch = parsed
            }
            fallbackMatch
        }
    }.getOrNull()

    private suspend fun scanLocalSubnets(targetSubdomain: String?): DiscoveredHostEndpoint? = coroutineScope {
        val myIps = getLocalIpv4Addresses()
        if (myIps.isEmpty()) return@coroutineScope null
        val myIpSet = myIps.toSet()

        val prefixes = myIps.mapNotNull { ip ->
            val parts = ip.split('.')
            if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}" else null
        }.distinct()

        val candidateIps = mutableListOf<String>()
        for (prefix in prefixes) {
            for (host in 1..254) {
                val ip = "$prefix.$host"
                if (ip !in myIpSet) candidateIps.add(ip)
            }
        }

        val sem = Semaphore(48)
        val exactMatch = java.util.concurrent.atomic.AtomicReference<DiscoveredHostEndpoint?>(null)
        val fallbackRemoteHost = java.util.concurrent.atomic.AtomicReference<DiscoveredHostEndpoint?>(null)

        val jobs = candidateIps.map { ip ->
            async(Dispatchers.IO) {
                if (exactMatch.get() != null) return@async
                sem.withPermit {
                    if (exactMatch.get() != null) return@withPermit
                    for (port in listOf(8000, 8001)) {
                        val match = verifyDirectEndpoint(ip, port, targetSubdomain)
                        if (match != null) {
                            if (targetSubdomain.isNullOrBlank() ||
                                match.subdomain.equals(targetSubdomain, ignoreCase = true)
                            ) {
                                exactMatch.compareAndSet(null, match)
                            } else {
                                fallbackRemoteHost.compareAndSet(null, match)
                            }
                            break
                        }
                    }
                }
            }
        }
        jobs.awaitAll()
        exactMatch.get() ?: fallbackRemoteHost.get()
    }

    private fun verifyDirectEndpoint(
        ip: String,
        port: Int,
        expectedSubdomain: String?,
    ): DiscoveredHostEndpoint? = runCatching {
        val req = Request.Builder()
            .url("http://$ip:$port/health")
            .header("X-PocketLink-Auto-Tunnel", "1")
            .get()
            .build()
        fastProbeClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@runCatching null
            val body = res.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return@runCatching null
            if (json.optString("service") != "pocketlink-native") return@runCatching null
            val instance = json.optString("instance")
            // CRITICAL: Never match our own in-process server!
            if (instance.isNotBlank() && instance == BridgeService.activeInstanceId) {
                return@runCatching null
            }
            val sub = json.optString("subdomain").trim().lowercase(Locale.ROOT)
                .ifBlank { expectedSubdomain.orEmpty() }
            val pubUrl = json.optString("public_url").ifBlank {
                if (sub.isNotBlank()) "https://$sub.trycloudflare.com" else "http://$ip:$port"
            }
            val device = json.optString("device", "PocketLink Host")
            val endpoint = DiscoveredHostEndpoint(
                subdomain = sub,
                publicUrl = pubUrl,
                hostIp = ip,
                port = port,
                instanceId = instance.ifBlank { "remote-$ip:$port" },
                deviceName = device,
            )
            if (sub.isNotBlank()) {
                recentLanHosts[sub] = endpoint
            }
            recentLanHosts[ip] = endpoint
            endpoint
        }
    }.getOrNull()

    private suspend fun fetchHostInfoViaWssAndProbeLan(targetSubdomain: String): DiscoveredHostEndpoint? {
        val infoTopic = "pocketlink/v2/host/$targetSubdomain/info"
        for (brokerUrl in relayBrokers) {
            val infoDeferred = CompletableDeferred<JSONObject?>()
            val clientId = "pld-${UUID.randomUUID().toString().take(8)}"
            val wsReq = Request.Builder()
                .url(brokerUrl)
                .header("Sec-WebSocket-Protocol", "mqtt")
                .build()
            val ws = wssRelayClient.newWebSocket(wsReq, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(MqttWire.encodeConnect(clientId))
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    for (pkt in MqttWire.decodePackets(bytes.toByteArray())) {
                        when (pkt.type) {
                            MqttWire.TYPE_CONNACK -> {
                                webSocket.send(MqttWire.encodeSubscribe(1, infoTopic))
                            }
                            MqttWire.TYPE_PUBLISH -> {
                                val json = runCatching {
                                    JSONObject(String(pkt.payload, Charsets.UTF_8))
                                }.getOrNull()
                                infoDeferred.complete(json)
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    infoDeferred.complete(null)
                }
            })

            val infoJson = withTimeoutOrNull(1_600L) { infoDeferred.await() }
            runCatching { ws.close(1000, null) }

            if (infoJson != null) {
                val instance = infoJson.optString("instance")
                if (instance.isNotBlank() && instance != BridgeService.activeInstanceId) {
                    val port = infoJson.optInt("port", 8000)
                    val ipsArr = infoJson.optJSONArray("ips")
                    val myIps = getLocalIpv4Addresses().toSet()
                    if (ipsArr != null) {
                        for (i in 0 until ipsArr.length()) {
                            val ip = ipsArr.optString(i).trim()
                            if (ip.isNotBlank() && ip !in myIps) {
                                val verified = verifyDirectEndpoint(ip, port, targetSubdomain)
                                if (verified != null) return verified
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private suspend fun executeViaWssRelay(
        authedReq: Request,
        targetSubdomain: String,
    ): Response? {
        val reqId = UUID.randomUUID().toString().replace("-", "").take(16)
        val clientId = "plc-$reqId"
        val reqTopic = "pocketlink/v2/host/$targetSubdomain/req"
        val replyTopic = "pocketlink/v2/client/$clientId/res"

        val pathWithQuery = buildString {
            append(authedReq.url.encodedPath)
            authedReq.url.encodedQuery?.takeIf { it.isNotBlank() }?.let {
                append('?')
                append(it)
            }
        }
        val headersJson = JSONObject()
        for (i in 0 until authedReq.headers.size) {
            headersJson.put(authedReq.headers.name(i), authedReq.headers.value(i))
        }
        val reqBodyB64 = authedReq.body?.let { body ->
            val buffer = Buffer()
            body.writeTo(buffer)
            Base64.encodeToString(buffer.readByteArray(), Base64.NO_WRAP)
        }.orEmpty()

        val payloadBytes = JSONObject()
            .put("req_id", reqId)
            .put("reply_topic", replyTopic)
            .put("method", authedReq.method)
            .put("path", pathWithQuery)
            .put("headers", headersJson)
            .put("body_b64", reqBodyB64)
            .toString()
            .toByteArray(Charsets.UTF_8)

        for (brokerUrl in relayBrokers) {
            val responseDeferred = CompletableDeferred<JSONObject?>()
            val wsReq = Request.Builder()
                .url(brokerUrl)
                .header("Sec-WebSocket-Protocol", "mqtt")
                .build()

            val ws = wssRelayClient.newWebSocket(wsReq, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(MqttWire.encodeConnect(clientId))
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    for (pkt in MqttWire.decodePackets(bytes.toByteArray())) {
                        when (pkt.type) {
                            MqttWire.TYPE_CONNACK -> {
                                webSocket.send(MqttWire.encodeSubscribe(1, replyTopic))
                            }
                            MqttWire.TYPE_SUBACK -> {
                                webSocket.send(MqttWire.encodePublish(reqTopic, payloadBytes, retain = false))
                            }
                            MqttWire.TYPE_PUBLISH -> {
                                val json = runCatching {
                                    JSONObject(String(pkt.payload, Charsets.UTF_8))
                                }.getOrNull()
                                if (json != null && json.optString("req_id") == reqId) {
                                    responseDeferred.complete(json)
                                }
                            }
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    responseDeferred.complete(null)
                }
            })

            val resJson = withTimeoutOrNull(8_500L) { responseDeferred.await() }
            runCatching { ws.close(1000, null) }

            if (resJson != null) {
                val status = resJson.optInt("status", 200)
                val msg = resJson.optString("message", "OK")
                val hdrs = resJson.optJSONObject("headers")
                val bodyBytes = resJson.optString("body_b64", "").let { b64 ->
                    if (b64.isNotEmpty()) runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrDefault(ByteArray(0))
                    else ByteArray(0)
                }
                val contentType = hdrs?.optString("Content-Type")?.takeIf { it.isNotBlank() }
                    ?: hdrs?.optString("content-type")?.takeIf { it.isNotBlank() }
                    ?: "application/json; charset=utf-8"

                val resBuilder = Response.Builder()
                    .request(authedReq)
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message(msg)

                if (hdrs != null) {
                    val keys = hdrs.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        resBuilder.header(k, hdrs.optString(k))
                    }
                }
                resBuilder.body(bodyBytes.toResponseBody(contentType.toMediaTypeOrNull()))
                TunnelManager.recordTrafficIfInitialized(bodyBytes.size.toLong())
                return resBuilder.build()
            }
        }
        return null
    }

    fun getLocalIpv4Addresses(): List<String> = runCatching {
        val result = mutableListOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.inetAddresses.toList()) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress ?: continue
                    if (!ip.startsWith("169.254.")) {
                        result.add(ip)
                    }
                }
            }
        }
        result.distinct()
    }.getOrDefault(emptyList())

    private fun getBroadcastAddresses(): List<InetAddress> = runCatching {
        val result = mutableListOf<InetAddress>()
        runCatching { result.add(InetAddress.getByName("255.255.255.255")) }
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (ia in iface.interfaceAddresses) {
                val bcast = ia.broadcast
                if (bcast != null && bcast !in result) {
                    result.add(bcast)
                }
            }
        }
        result
    }.getOrDefault(emptyList())
}

/**
 * Lightweight pure-Kotlin MQTT v3.1.1 packet encoder/decoder for WebSocket binary frames.
 */
internal object MqttWire {
    const val TYPE_CONNECT = 1
    const val TYPE_CONNACK = 2
    const val TYPE_PUBLISH = 3
    const val TYPE_SUBSCRIBE = 8
    const val TYPE_SUBACK = 9
    const val TYPE_PINGREQ = 12
    const val TYPE_PINGRESP = 13

    class DecodedPacket(
        val type: Int,
        val topic: String = "",
        val payload: ByteArray = ByteArray(0),
    )

    fun encodeConnect(clientId: String, keepAliveSec: Int = 30): ByteString {
        val body = ByteArrayOutputStream()
        writeUtf8(body, "MQTT")
        body.write(4) // Protocol level 4 (MQTT 3.1.1)
        body.write(0x02) // Clean session
        body.write((keepAliveSec shr 8) and 0xff)
        body.write(keepAliveSec and 0xff)
        writeUtf8(body, clientId)
        return wrapPacket(0x10, body.toByteArray())
    }

    fun encodeSubscribe(packetId: Int, topic: String): ByteString {
        val body = ByteArrayOutputStream()
        body.write((packetId shr 8) and 0xff)
        body.write(packetId and 0xff)
        writeUtf8(body, topic)
        body.write(0) // QoS 0
        return wrapPacket(0x82, body.toByteArray())
    }

    fun encodePublish(topic: String, payload: ByteArray, retain: Boolean): ByteString {
        val body = ByteArrayOutputStream()
        writeUtf8(body, topic)
        body.write(payload)
        val header = if (retain) 0x31 else 0x30
        return wrapPacket(header, body.toByteArray())
    }

    fun encodePingReq(): ByteString = byteArrayOf(0xC0.toByte(), 0x00).toByteString()

    fun decodePackets(raw: ByteArray): List<DecodedPacket> {
        val list = mutableListOf<DecodedPacket>()
        var offset = 0
        while (offset < raw.size) {
            val firstByte = raw[offset].toInt() and 0xff
            val type = (firstByte shr 4) and 0x0f
            val flags = firstByte and 0x0f
            offset++

            var multiplier = 1
            var remainingLength = 0
            while (offset < raw.size) {
                val encodedByte = raw[offset].toInt() and 0xff
                offset++
                remainingLength += (encodedByte and 127) * multiplier
                if ((encodedByte and 128) == 0) break
                multiplier *= 128
            }
            if (offset + remainingLength > raw.size) break
            val packetEnd = offset + remainingLength

            if (type == TYPE_PUBLISH) {
                if (offset + 2 <= packetEnd) {
                    val topicLen = ((raw[offset].toInt() and 0xff) shl 8) or (raw[offset + 1].toInt() and 0xff)
                    var pos = offset + 2
                    if (pos + topicLen <= packetEnd) {
                        val topic = String(raw, pos, topicLen, Charsets.UTF_8)
                        pos += topicLen
                        val qos = (flags shr 1) and 0x03
                        if (qos > 0 && pos + 2 <= packetEnd) {
                            pos += 2
                        }
                        val payload = raw.copyOfRange(pos, packetEnd)
                        list.add(DecodedPacket(type = type, topic = topic, payload = payload))
                    }
                }
            } else {
                list.add(DecodedPacket(type = type))
            }
            offset = packetEnd
        }
        return list
    }

    private fun writeUtf8(out: ByteArrayOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.write((bytes.size shr 8) and 0xff)
        out.write(bytes.size and 0xff)
        out.write(bytes)
    }

    private fun wrapPacket(headerByte: Int, body: ByteArray): ByteString {
        val out = ByteArrayOutputStream(body.size + 5)
        out.write(headerByte and 0xff)
        var x = body.size
        do {
            var encodedByte = x % 128
            x /= 128
            if (x > 0) {
                encodedByte = encodedByte or 128
            }
            out.write(encodedByte)
        } while (x > 0)
        out.write(body)
        return out.toByteArray().toByteString()
    }
}
