package io.github.augztn.pocketlink.ssdbridge.tunnel

import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Real Cloudflare Tunnel provider adapter (100% pure Kotlin / OkHttp, Google Play Policy compliant
 * — zero runtime binary downloads / zero Dynamic Code Loading).
 *
 * Supports two Cloudflare modes:
 * 1. [CloudflareTunnelMode.EDGE_BRIDGE]: Pure-Kotlin outbound WSS reverse-proxy tunnel connected
 *    to the user's Cloudflare Worker + Durable Object (`wss://<worker>/__pocketlink_tunnel`),
 *    streaming HTTP requests/responses directly to local `http://127.0.0.1:<port>`.
 * 2. [CloudflareTunnelMode.ZERO_TRUST]: Validates Cloudflare Zero Trust tunnel tokens (`eyJ…`),
 *    verifies live end-to-end HTTPS reachability via Cloudflare Edge (`CF-Ray` + `/health`),
 *    and continuously monitors tunnel health while active.
 */
class CloudflareTunnelAdapter(
    private val configProvider: () -> CloudflareTunnelConfig,
    private val workerSecretProvider: () -> String,
    private val onStateChanged: (TunnelStatusSnapshot) -> Unit = {},
) : TunnelProviderAdapter {

    override val providerId: TunnelProviderId = TunnelProviderId.CLOUDFLARE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningFlag = AtomicBoolean(false)
    private val requestsCounter = AtomicLong(0L)
    private val bytesCounter = AtomicLong(0L)

    @Volatile private var activePort: Int = 8000
    @Volatile private var activeSocket: WebSocket? = null
    @Volatile private var monitorJob: Job? = null
    @Volatile private var reconnectJob: Job? = null

    private val wsClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val localProxyClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _status = MutableStateFlow(TunnelStatusSnapshot())
    override val status: StateFlow<TunnelStatusSnapshot> = _status.asStateFlow()

    fun syncConfigPreview(config: CloudflareTunnelConfig) {
        updateStatus { cur ->
            if (cur.state == TunnelState.STOPPED || cur.state == TunnelState.ERROR) {
                cur.copy(
                    mode = config.mode,
                    publicUrl = config.activePublicUrl.takeIf { it.isNotBlank() },
                    autoStartWithServer = config.autoStartWithServer,
                    tokenInfo = config.tokenInfo,
                )
            } else {
                cur.copy(
                    autoStartWithServer = config.autoStartWithServer,
                    tokenInfo = config.tokenInfo,
                )
            }
        }
    }

    override fun start(localPort: Int): Result<Unit> {
        val config = configProvider()
        activePort = localPort
        val targetLocal = "http://127.0.0.1:$localPort"

        return when (config.mode) {
            CloudflareTunnelMode.EDGE_BRIDGE -> startEdgeBridge(config, targetLocal)
            CloudflareTunnelMode.ZERO_TRUST -> startZeroTrustMonitor(config, targetLocal)
        }
    }

    private fun startEdgeBridge(config: CloudflareTunnelConfig, targetLocal: String): Result<Unit> {
        val publicUrl = normalizeCloudflarePublicUrl(config.workerPublicUrl).getOrElse { err ->
            return Result.failure(err)
        }
        val secret = workerSecretProvider().trim()
        if (secret.length < 8) {
            return Result.failure(
                IllegalArgumentException("Set a Tunnel Secret (8+ characters) matching your Cloudflare Worker TUNNEL_SECRET.")
            )
        }

        stopInternal(logStop = false)
        runningFlag.set(true)
        requestsCounter.set(0L)
        bytesCounter.set(0L)

        updateStatus {
            it.copy(
                mode = CloudflareTunnelMode.EDGE_BRIDGE,
                state = TunnelState.CONNECTING,
                publicUrl = publicUrl,
                localTargetUrl = targetLocal,
                autoStartWithServer = config.autoStartWithServer,
                cloudflareRay = null,
                cloudflareProxied = false,
                latencyMs = null,
                requestsProxied = 0L,
                bytesProxied = 0L,
                tokenInfo = config.tokenInfo,
                lastError = null,
            )
        }
        appendLog("INFO", "Connecting Cloudflare Edge Tunnel -> $publicUrl")
        connectEdgeWebSocket(publicUrl, secret, attempt = 0)
        return Result.success(Unit)
    }

    private fun connectEdgeWebSocket(publicUrl: String, secret: String, attempt: Int) {
        if (!runningFlag.get()) return
        val wsUrl = buildWorkerWebSocketUrl(publicUrl)
        val req = Request.Builder()
            .url(wsUrl)
            .header("X-PocketLink-Tunnel-Key", secret)
            .header("User-Agent", "PocketLink-CloudflareTunnel/1.0")
            .build()

        activeSocket?.cancel()
        activeSocket = wsClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val cfRay = response.header("CF-Ray")?.trim()?.takeIf { it.isNotEmpty() }
                val proxied = cfRay != null ||
                    response.header("Server").orEmpty().contains("cloudflare", ignoreCase = true)
                appendLog(
                    "INFO",
                    if (cfRay != null) "Cloudflare Edge Tunnel online (CF-Ray: $cfRay)"
                    else "Cloudflare Edge Tunnel online ($publicUrl)"
                )
                updateStatus {
                    it.copy(
                        state = TunnelState.CONNECTED,
                        publicUrl = publicUrl,
                        cloudflareRay = cfRay,
                        cloudflareProxied = proxied,
                        lastError = null,
                    )
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    handleIncomingTunnelFrame(webSocket, text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (activeSocket === webSocket) activeSocket = null
                if (!runningFlag.get()) return
                appendLog("WARN", "Edge socket closed ($code ${reason.ifBlank { "remote close" }}). Reconnecting…")
                scheduleReconnect(publicUrl, secret, attempt + 1)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (activeSocket === webSocket) activeSocket = null
                if (!runningFlag.get()) return
                val code = response?.code
                val msg = when (code) {
                    401 -> "Unauthorized (401): Tunnel Secret does not match Worker TUNNEL_SECRET."
                    404 -> "Worker endpoint /__pocketlink_tunnel not found (404). Deploy the PocketLink Worker script."
                    else -> t.message?.takeIf { it.isNotBlank() } ?: "Could not connect to Cloudflare Worker."
                }
                appendLog("ERROR", msg)
                if (code == 401 || code == 404) {
                    runningFlag.set(false)
                    updateStatus { it.copy(state = TunnelState.ERROR, lastError = msg) }
                } else {
                    updateStatus { it.copy(state = TunnelState.CONNECTING, lastError = msg) }
                    scheduleReconnect(publicUrl, secret, attempt + 1)
                }
            }
        })
    }

    private fun scheduleReconnect(publicUrl: String, secret: String, nextAttempt: Int) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val backoffSec = when (nextAttempt) {
                1 -> 2L
                2 -> 4L
                3 -> 8L
                4 -> 15L
                else -> 30L
            }
            delay(backoffSec * 1000L)
            if (runningFlag.get()) {
                appendLog("INFO", "Reconnecting to Cloudflare Edge (attempt #$nextAttempt)…")
                connectEdgeWebSocket(publicUrl, secret, nextAttempt)
            }
        }
    }

    private fun handleIncomingTunnelFrame(webSocket: WebSocket, rawText: String) {
        val frame = runCatching { JSONObject(rawText) }.getOrNull() ?: return
        val type = frame.optString("type")
        if (type == "pong") return
        if (type != "http_request") return

        val reqId = frame.optString("id").trim()
        if (reqId.isEmpty()) return
        val method = frame.optString("method", "GET").uppercase(Locale.US)
        val rawPath = frame.optString("path", "/").let { if (it.startsWith("/")) it else "/$it" }
        val rawHeaders = mutableMapOf<String, String>()
        frame.optJSONObject("headers")?.let { hdrObj ->
            val keys = hdrObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                rawHeaders[k] = hdrObj.optString(k)
            }
        }
        val filteredHeaders = sanitizeForwardedRequestHeaders(rawHeaders)
        val bodyB64 = frame.optString("body_b64", "")
        val reqBodyBytes = if (bodyB64.isNotEmpty()) {
            runCatching { Base64.decode(bodyB64, Base64.DEFAULT) }.getOrDefault(ByteArray(0))
        } else {
            ByteArray(0)
        }

        val targetUrl = "http://127.0.0.1:$activePort$rawPath"
        val reqBuilder = Request.Builder().url(targetUrl)
        filteredHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
        reqBuilder.header("X-Forwarded-Proto", "https")

        val hasBodyMethod = method == "POST" || method == "PUT" || method == "PATCH" || method == "DELETE"
        val contentType = filteredHeaders.entries
            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
            ?.value
            ?.toMediaTypeOrNull()

        val body = when {
            method == "GET" || method == "HEAD" -> null
            reqBodyBytes.isNotEmpty() -> reqBodyBytes.toRequestBody(contentType)
            hasBodyMethod -> ByteArray(0).toRequestBody(contentType)
            else -> null
        }
        reqBuilder.method(method, body)

        try {
            localProxyClient.newCall(reqBuilder.build()).execute().use { upstream ->
                val resHeaders = JSONObject()
                for (i in 0 until upstream.headers.size) {
                    val name = upstream.headers.name(i)
                    val lower = name.lowercase(Locale.US)
                    if (lower in HOP_BY_HOP_HEADERS) continue
                    resHeaders.put(name, upstream.headers.value(i))
                }
                webSocket.send(
                    JSONObject()
                        .put("type", "http_response_start")
                        .put("id", reqId)
                        .put("status", upstream.code)
                        .put("headers", resHeaders)
                        .toString()
                )

                var streamedBytes = 0L
                val stream = upstream.body?.byteStream()
                if (stream != null && method != "HEAD") {
                    val buf = ByteArray(STREAM_CHUNK_BYTES)
                    while (runningFlag.get()) {
                        val read = stream.read(buf)
                        if (read <= 0) break
                        streamedBytes += read
                        val encoded = Base64.encodeToString(buf, 0, read, Base64.NO_WRAP)
                        val sent = webSocket.send(
                            JSONObject()
                                .put("type", "http_response_chunk")
                                .put("id", reqId)
                                .put("data_b64", encoded)
                                .toString()
                        )
                        if (!sent) break
                    }
                }

                webSocket.send(
                    JSONObject()
                        .put("type", "http_response_end")
                        .put("id", reqId)
                        .toString()
                )

                val totalReqs = requestsCounter.incrementAndGet()
                val totalBytes = bytesCounter.addAndGet(streamedBytes + reqBodyBytes.size)
                updateStatus {
                    it.copy(requestsProxied = totalReqs, bytesProxied = totalBytes)
                }
            }
        } catch (err: Exception) {
            val detail = err.message ?: "Local PocketLink host unavailable on 127.0.0.1:$activePort"
            appendLog("WARN", "Proxy $method $rawPath failed: $detail")
            webSocket.send(
                JSONObject()
                    .put("type", "http_response_error")
                    .put("id", reqId)
                    .put("status", 502)
                    .put("message", detail)
                    .toString()
            )
        }
    }

    private fun startZeroTrustMonitor(config: CloudflareTunnelConfig, targetLocal: String): Result<Unit> {
        val publicUrl = normalizeCloudflarePublicUrl(config.zeroTrustPublicUrl).getOrElse { err ->
            return Result.failure(err)
        }

        stopInternal(logStop = false)
        runningFlag.set(true)

        updateStatus {
            it.copy(
                mode = CloudflareTunnelMode.ZERO_TRUST,
                state = TunnelState.CONNECTING,
                publicUrl = publicUrl,
                localTargetUrl = targetLocal,
                autoStartWithServer = config.autoStartWithServer,
                cloudflareRay = null,
                cloudflareProxied = false,
                latencyMs = null,
                tokenInfo = config.tokenInfo,
                lastError = null,
            )
        }
        appendLog("INFO", "Verifying Cloudflare Zero Trust route -> $publicUrl")

        monitorJob = scope.launch {
            while (isActive && runningFlag.get()) {
                val localOk = checkLocalHealth(activePort)
                if (!localOk) {
                    val msg = "Local PocketLink server is not responding on 127.0.0.1:$activePort. Start the local server first."
                    appendLog("ERROR", msg)
                    updateStatus { it.copy(state = TunnelState.ERROR, lastError = msg) }
                } else {
                    val probe = executePublicProbe(publicUrl)
                    if (probe.reachable) {
                        appendLog(
                            "INFO",
                            buildString {
                                append("Verified HTTPS $publicUrl (${probe.latencyMs} ms)")
                                probe.cloudflareRay?.let { append(" · CF-Ray $it") }
                            }
                        )
                        updateStatus {
                            it.copy(
                                state = TunnelState.CONNECTED,
                                publicUrl = publicUrl,
                                cloudflareRay = probe.cloudflareRay,
                                cloudflareProxied = probe.cloudflareProxied,
                                latencyMs = probe.latencyMs,
                                lastError = null,
                            )
                        }
                    } else {
                        val errMsg = probe.errorMessage ?: "Public Cloudflare URL did not reach PocketLink."
                        appendLog("WARN", errMsg)
                        updateStatus {
                            it.copy(
                                state = TunnelState.ERROR,
                                cloudflareRay = probe.cloudflareRay,
                                cloudflareProxied = probe.cloudflareProxied,
                                latencyMs = probe.latencyMs.takeIf { ms -> ms > 0L },
                                lastError = errMsg,
                            )
                        }
                    }
                }
                delay(ZERO_TRUST_POLL_INTERVAL_MS)
            }
        }
        return Result.success(Unit)
    }

    override suspend fun verifyPublicEndpoint(): Result<TunnelProbeResult> = withContext(Dispatchers.IO) {
        val config = configProvider()
        val publicUrl = normalizeCloudflarePublicUrl(config.activePublicUrl).getOrElse { err ->
            return@withContext Result.failure(err)
        }
        appendLog("INFO", "Probing $publicUrl/health…")
        val result = executePublicProbe(publicUrl)
        if (result.reachable) {
            appendLog(
                "INFO",
                buildString {
                    append("Probe OK (${result.latencyMs} ms)")
                    result.cloudflareRay?.let { append(" · CF-Ray $it") }
                }
            )
            updateStatus {
                it.copy(
                    cloudflareRay = result.cloudflareRay ?: it.cloudflareRay,
                    cloudflareProxied = result.cloudflareProxied || it.cloudflareProxied,
                    latencyMs = result.latencyMs,
                    lastError = null,
                )
            }
        } else {
            appendLog("WARN", "Probe failed: ${result.errorMessage}")
        }
        Result.success(result)
    }

    private fun checkLocalHealth(port: Int): Boolean = runCatching {
        val req = Request.Builder().url("http://127.0.0.1:$port/health").get().build()
        localProxyClient.newCall(req).execute().use { res ->
            res.isSuccessful && res.body?.string().orEmpty().contains("pocketlink-native")
        }
    }.getOrDefault(false)

    private fun executePublicProbe(publicUrl: String): TunnelProbeResult {
        val started = System.nanoTime()
        return try {
            val req = Request.Builder()
                .url("$publicUrl/health")
                .header("Accept", "application/json")
                .get()
                .build()
            probeClient.newCall(req).execute().use { res ->
                val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
                val body = res.body?.string().orEmpty()
                val cfRay = res.header("CF-Ray")?.trim()?.takeIf { it.isNotEmpty() }
                val serverHeader = res.header("Server").orEmpty()
                val isCf = cfRay != null || serverHeader.contains("cloudflare", ignoreCase = true)
                val parsedJson = runCatching { JSONObject(body) }.getOrNull()
                val service = parsedJson?.optString("service")?.takeIf { it.isNotBlank() }
                val ok = res.isSuccessful && service == "pocketlink-native"
                val err = when {
                    ok -> null
                    res.code == 503 -> "Cloudflare edge responded 503 — start the Cloudflare Tunnel or local server."
                    res.code == 502 || res.code == 530 -> "Cloudflare edge could not reach local host 127.0.0.1:$activePort (HTTP ${res.code})."
                    res.isSuccessful -> "HTTPS endpoint responded, but it is not a PocketLink server."
                    else -> "Cloudflare probe returned HTTP ${res.code}."
                }
                TunnelProbeResult(
                    reachable = ok,
                    publicUrl = publicUrl,
                    latencyMs = elapsedMs,
                    statusCode = res.code,
                    serviceName = service,
                    cloudflareRay = cfRay,
                    cloudflareProxied = isCf,
                    errorMessage = err,
                )
            }
        } catch (err: IOException) {
            TunnelProbeResult(
                reachable = false,
                publicUrl = publicUrl,
                errorMessage = err.message ?: "Could not connect to $publicUrl",
            )
        }
    }

    override fun stop() {
        stopInternal(logStop = true)
    }

    private fun stopInternal(logStop: Boolean) {
        val wasRunning = runningFlag.getAndSet(false)
        reconnectJob?.cancel()
        reconnectJob = null
        monitorJob?.cancel()
        monitorJob = null
        activeSocket?.close(1000, "Stopped by user")
        activeSocket = null
        if (logStop && wasRunning) {
            appendLog("INFO", "Cloudflare Tunnel stopped")
        }
        updateStatus {
            it.copy(
                state = TunnelState.STOPPED,
                lastError = null,
            )
        }
    }

    override fun clearLogs() {
        updateStatus { it.copy(logs = emptyList()) }
    }

    private fun appendLog(level: String, message: String) {
        val entry = TunnelLogLine(System.currentTimeMillis(), level, message)
        updateStatus { cur ->
            val updated = (cur.logs + entry).takeLast(MAX_LOG_LINES)
            cur.copy(logs = updated)
        }
    }

    private fun updateStatus(transform: (TunnelStatusSnapshot) -> TunnelStatusSnapshot) {
        val next = transform(_status.value)
        _status.value = next
        onStateChanged(next)
    }

    companion object {
        private const val STREAM_CHUNK_BYTES = 48 * 1024
        private const val ZERO_TRUST_POLL_INTERVAL_MS = 25_000L
        private const val MAX_LOG_LINES = 60

        private val HOP_BY_HOP_HEADERS = setOf(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "host",
            // Prevent NanoHTTPD from treating forwarded IP headers as non-loopback peer IP
            "remote-addr",
            "http-client-ip",
            "x-real-ip",
        )

        internal fun buildWorkerWebSocketUrl(publicHttpsUrl: String): String {
            val clean = publicHttpsUrl.trim().trimEnd('/')
            val wssBase = when {
                clean.startsWith("https://", ignoreCase = true) -> "wss://" + clean.substring(8)
                clean.startsWith("wss://", ignoreCase = true) -> clean
                else -> "wss://$clean"
            }
            return "$wssBase/__pocketlink_tunnel"
        }

        internal fun sanitizeForwardedRequestHeaders(raw: Map<String, String>): Map<String, String> =
            raw.filterKeys { key -> key.trim().lowercase(Locale.US) !in HOP_BY_HOP_HEADERS }

        fun buildQuickTunnelCommand(localPort: Int = 8000): String =
            "cloudflared tunnel --url http://127.0.0.1:$localPort --protocol http2"

        fun buildNamedTunnelCommand(tokenConfigured: Boolean): String =
            if (tokenConfigured) "cloudflared tunnel run --protocol http2 --token <YOUR_CLOUDFLARE_TUNNEL_TOKEN>"
            else "cloudflared tunnel run --protocol http2 --token eyJ..."
    }
}
