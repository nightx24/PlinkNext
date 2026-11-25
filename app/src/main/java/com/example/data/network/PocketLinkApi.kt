package com.example.data.network

import android.net.Uri
import android.util.Base64
import com.example.data.model.DirectoryListing
import com.example.data.model.FileEntry
import com.example.data.model.HostSystemTelemetry
import com.example.data.model.IndexedFile
import com.example.data.model.IndexedMediaPage
import com.example.data.model.MediaCategory
import com.example.data.model.MediaExtensions
import com.example.data.model.MediaIndexStatus
import com.example.data.model.NetworkRouteType
import com.example.data.model.RecentFile
import com.example.data.model.StorageVolume
import com.example.data.model.VideoMetadata
import com.example.data.model.classifyNetworkRoute
import com.example.data.store.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class ApiHttpException(val statusCode: Int, message: String) : IOException(message)

class PocketLinkApi(
    rawAddress: String,
    val username: String,
    val password: String,
) {
    val baseUrl: String = normalizeBaseUrl(rawAddress)
    val routeType: NetworkRouteType = classifyNetworkRoute(baseUrl)
    val authHeader: String = Credentials.basic(username, password, Charsets.UTF_8)

    val httpClient: OkHttpClient = sharedBaseClient.newBuilder()
        .addInterceptor { chain ->
            val req = chain.request()
            if (!req.isHttps) {
                val requestRoute = classifyNetworkRoute(req.url.toString())
                if (requestRoute != NetworkRouteType.LOCALHOST &&
                    requestRoute != NetworkRouteType.TAILSCALE &&
                    requestRoute != NetworkRouteType.PRIVATE_LAN
                ) {
                    throw IOException(
                        "Plain HTTP is allowed only over Tailscale (100.x.y.z), private Wi-Fi/LAN, or localhost."
                    )
                }
            }
            val reqUrl = req.url.toString()
            val targetsThisServer = reqUrl.startsWith(baseUrl)
            val needsAuth = targetsThisServer && !req.url.encodedPath.endsWith("/health")
            val authedReq = if (needsAuth && req.header("Authorization") == null) {
                req.newBuilder().header("Authorization", authHeader).build()
            } else {
                req
            }

            val hostLower = authedReq.url.host.lowercase()
            val targetAutoSubdomain = io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelManager
                .extractAutoCloudflareSubdomain(hostLower)
            val isThisDeviceOwnAutoCloudflare = targetsThisServer &&
                io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelManager.isThisDeviceAutoPublicUrl(hostLower)
            val isRemoteAutoCloudflare = targetsThisServer &&
                targetAutoSubdomain != null &&
                !isThisDeviceOwnAutoCloudflare
            val isLocalLoopback = targetsThisServer && (
                hostLower == "127.0.0.1" || hostLower == "localhost" || hostLower == "::1"
                )
            val activeLocalPort = io.github.augztn.pocketlink.ssdbridge.BridgeService.activeApiPort
                .takeIf { it > 0 } ?: 8000

            if (isRemoteAutoCloudflare && targetAutoSubdomain != null) {
                io.github.augztn.pocketlink.ssdbridge.tunnel.PocketLinkPeerBridge.executeRemoteTunnelRequest(
                    chain = chain,
                    authedReq = authedReq,
                    targetSubdomain = targetAutoSubdomain,
                )
            } else if (isThisDeviceOwnAutoCloudflare) {
                val bridgedUrl = authedReq.url.newBuilder()
                    .scheme("http")
                    .host("127.0.0.1")
                    .port(activeLocalPort)
                    .build()
                val bridgedReq = authedReq.newBuilder()
                    .url(bridgedUrl)
                    .header("X-Forwarded-Proto", "https")
                    .header("X-Forwarded-Host", authedReq.url.host)
                    .header("X-PocketLink-Auto-Tunnel", "1")
                    .build()
                val res = chain.proceed(bridgedReq)
                val bodyLen = res.header("Content-Length")?.toLongOrNull() ?: 512L
                io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelManager.recordTrafficIfInitialized(bodyLen)
                res
            } else if (isLocalLoopback && io.github.augztn.pocketlink.ssdbridge.BridgeService.apiRunning) {
                val bridgedUrl = authedReq.url.newBuilder()
                    .scheme("http")
                    .host("127.0.0.1")
                    .port(activeLocalPort)
                    .build()
                val bridgedReq = authedReq.newBuilder()
                    .url(bridgedUrl)
                    .header("X-PocketLink-Auto-Tunnel", "1")
                    .build()
                chain.proceed(bridgedReq)
            } else {
                chain.proceed(authedReq)
            }
        }
        .build()

    val mediaClient: OkHttpClient = httpClient.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    suspend fun checkHealth(): Long = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val req = Request.Builder().url("$baseUrl/health").get().build()
        httpClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                throw ApiHttpException(res.code, extractDetail(res.body?.string()) ?: "Health check failed (${res.code})")
            }
        }
        (System.nanoTime() - started) / 1_000_000L
    }

    suspend fun authenticate(): DirectoryListing = listDirectory("")

    suspend fun listDirectory(path: String = ""): DirectoryListing = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/files/".toHttpUrl().newBuilder()
            .addQueryParameter("path", path)
            .build()
        val json = getJson(url.toString())
        parseListing(json)
    }

    suspend fun listStorageVolumes(): List<StorageVolume> = withContext(Dispatchers.IO) {
        val json = getJson("$baseUrl/api/storage/volumes")
        val arr = json.optJSONArray("items") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    StorageVolume(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        available = o.optBoolean("available", false),
                        writable = o.optBoolean("writable", false),
                        totalBytes = o.optLongOrNull("total_bytes"),
                        usedBytes = o.optLongOrNull("used_bytes"),
                        availableBytes = o.optLongOrNull("available_bytes"),
                        usagePercent = o.optDoubleOrNull("usage_percent"),
                    )
                )
            }
        }
    }

    suspend fun listStorageDirectory(volume: String, path: String = ""): DirectoryListing =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/storage/list".toHttpUrl().newBuilder()
                .addQueryParameter("volume", volume)
                .addQueryParameter("path", path)
                .build()
            parseListing(getJson(url.toString()))
        }

    suspend fun systemTelemetry(): HostSystemTelemetry = withContext(Dispatchers.IO) {
        val json = getJson("$baseUrl/api/system/telemetry")
        val device = json.optJSONObject("device") ?: JSONObject()
        val cpu = json.optJSONObject("cpu") ?: JSONObject()
        val memory = json.optJSONObject("memory") ?: JSONObject()
        val storage = json.optJSONObject("storage") ?: JSONObject()
        val battery = json.optJSONObject("battery") ?: JSONObject()
        HostSystemTelemetry(
            sampledAtMs = json.optLongOrNull("sampled_at_ms"),
            manufacturer = device.optStringOrNull("manufacturer"),
            deviceModel = device.optStringOrNull("model"),
            androidVersion = device.optStringOrNull("android_version"),
            cpuModel = cpu.optStringOrNull("model"),
            cpuCores = cpu.optIntOrNull("cores"),
            cpuUsagePercent = cpu.optDoubleOrNull("usage_percent"),
            cpuUsageStatus = cpu.optString("usage_status", "unavailable"),
            memoryTotalBytes = memory.optLongOrNull("total_bytes"),
            memoryUsedBytes = memory.optLongOrNull("used_bytes"),
            memoryAvailableBytes = memory.optLongOrNull("available_bytes"),
            memoryUsagePercent = memory.optDoubleOrNull("usage_percent"),
            storageTotalBytes = storage.optLongOrNull("total_bytes"),
            storageUsedBytes = storage.optLongOrNull("used_bytes"),
            storageAvailableBytes = storage.optLongOrNull("available_bytes"),
            storageUsagePercent = storage.optDoubleOrNull("usage_percent"),
            batteryPercent = battery.optIntOrNull("percent"),
            batteryStatus = battery.optString("status", "unknown"),
        )
    }

    suspend fun createFolder(path: String, name: String, volume: String? = null) = withContext(Dispatchers.IO) {
        if (volume == null || volume == "internal") {
            val body = JSONObject().put("path", path).put("name", name)
                .toString().toRequestBody(JSON_MEDIA)
            executeRequest(Request.Builder().url("$baseUrl/api/files/mkdir").post(body).build())
        } else {
            val body = JSONObject().put("volume", volume).put("path", path).put("name", name)
                .toString().toRequestBody(JSON_MEDIA)
            executeRequest(Request.Builder().url("$baseUrl/api/storage/mkdir").post(body).build())
        }
    }

    suspend fun renameItem(path: String, newName: String, volume: String? = null) = withContext(Dispatchers.IO) {
        if (volume == null || volume == "internal") {
            val body = JSONObject().put("path", path).put("new_name", newName)
                .toString().toRequestBody(JSON_MEDIA)
            executeRequest(Request.Builder().url("$baseUrl/api/files/rename").patch(body).build())
        } else {
            val body = JSONObject().put("volume", volume).put("path", path).put("new_name", newName)
                .toString().toRequestBody(JSON_MEDIA)
            executeRequest(Request.Builder().url("$baseUrl/api/storage/rename").post(body).build())
        }
    }

    suspend fun deleteItem(path: String, volume: String? = null) = withContext(Dispatchers.IO) {
        val url = if (volume == null || volume == "internal") {
            "$baseUrl/api/files/item".toHttpUrl().newBuilder()
                .addQueryParameter("path", path)
                .build()
        } else {
            "$baseUrl/api/storage/item".toHttpUrl().newBuilder()
                .addQueryParameter("volume", volume)
                .addQueryParameter("path", path)
                .build()
        }
        executeRequest(Request.Builder().url(url).delete().build())
    }

    suspend fun videoMetadata(path: String, volume: String? = null): VideoMetadata? =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = "$baseUrl/api/media/metadata".toHttpUrl().newBuilder()
                    .addQueryParameter("path", path)
                if (volume != null && volume != "internal") {
                    builder.addQueryParameter("volume", volume)
                }
                val json = getJson(builder.build().toString())
                VideoMetadata(
                    durationMs = json.optLong("duration_ms", 0L),
                    width = json.optInt("width", 0),
                    height = json.optInt("height", 0),
                )
            }.getOrNull()
        }

    suspend fun mediaIndexStatus(): MediaIndexStatus = withContext(Dispatchers.IO) {
        parseIndexStatus(getJson("$baseUrl/api/index/status"))
    }

    suspend fun startMediaIndexScan(): MediaIndexStatus = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/api/index/scan")
            .post("{}".toRequestBody(JSON_MEDIA))
            .build()
        val json = executeJson(req)
        parseIndexStatus(json)
    }

    suspend fun indexedMedia(
        category: MediaCategory,
        query: String = "",
        limit: Int = 200,
        offset: Int = 0,
    ): IndexedMediaPage = withContext(Dispatchers.IO) {
        val builder = "$baseUrl/api/index/media".toHttpUrl().newBuilder()
            .addQueryParameter("category", category.apiValue)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
        if (query.isNotBlank()) builder.addQueryParameter("q", query.trim())
        val json = getJson(builder.build().toString())
        IndexedMediaPage(
            items = parseIndexedFiles(json.optJSONArray("items")),
            total = json.optInt("total", 0),
            status = parseIndexStatus(json.optJSONObject("status") ?: JSONObject()),
        )
    }

    /**
     * Fetches embedded tag lyrics (FLAC VORBIS_COMMENT / ID3v2 USLT / MP4 ©lyr) or sidecar .lrc
     * text for an audio file hosted on the PocketLink server.
     */
    suspend fun fetchHostAudioLyrics(path: String, volume: String? = null): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            if (path.isBlank()) return@withContext null
            val builder = "$baseUrl/api/media/lyrics".toHttpUrl().newBuilder()
                .addQueryParameter("path", path)
            if (!volume.isNullOrBlank() && volume != "internal") {
                builder.addQueryParameter("volume", volume)
            }
            val req = Request.Builder().url(builder.build()).get().build()
            runCatching {
                httpClient.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@runCatching null
                    val raw = res.body?.string().orEmpty()
                    if (raw.isBlank()) return@runCatching null
                    val json = JSONObject(raw)
                    val lyrics = json.optString("lyrics").trim()
                    if (lyrics.isEmpty()) return@runCatching null
                    val source = json.optString("source").ifBlank { "Host Embedded" }
                    lyrics to source
                }
            }.getOrNull()
        }

    suspend fun searchIndexedFiles(
        query: String = "",
        limit: Int = 150,
        offset: Int = 0,
    ): IndexedMediaPage = withContext(Dispatchers.IO) {
        val builder = "$baseUrl/api/index/files".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
        if (query.isNotBlank()) builder.addQueryParameter("q", query.trim())
        val json = getJson(builder.build().toString())
        IndexedMediaPage(
            items = parseIndexedFiles(json.optJSONArray("items")),
            total = json.optInt("total", 0),
            status = parseIndexStatus(json.optJSONObject("status") ?: JSONObject()),
        )
    }

    suspend fun listRecentFiles(limit: Int = 12): List<RecentFile> = withContext(Dispatchers.IO) {
        val indexed = runCatching { searchIndexedFiles(query = "", limit = 40, offset = 0) }.getOrNull()
        val volumes = runCatching { listStorageVolumes() }.getOrDefault(emptyList())
        val volumeNames = volumes.associate { it.id to it.name }
        if (indexed != null && indexed.items.isNotEmpty()) {
            return@withContext indexed.items
                .filter { !it.isDirectory }
                .take(limit)
                .map { item ->
                    val vol = item.volume.takeIf { it != "internal" }
                    RecentFile(
                        file = item.toFileEntry(),
                        path = item.path,
                        volume = vol,
                        volumeName = if (vol == null) "Internal storage" else (volumeNames[vol] ?: vol),
                    )
                }
        }
        // Fallback: shallow directory walk on internal root & common folders
        val found = mutableListOf<RecentFile>()
        val roots = listOf("", "Download", "Documents", "Music", "Pictures", "DCIM", "Movies")
        for (dir in roots) {
            val listing = runCatching { listDirectory(dir) }.getOrNull() ?: continue
            for (entry in listing.items) {
                if (!entry.isDirectory) {
                    val fullPath = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
                    found.add(RecentFile(entry, fullPath, null, "Internal storage"))
                }
            }
        }
        found.distinctBy { it.path }.sortedByDescending { it.file.modified }.take(limit)
    }

    suspend fun walkMediaFallback(category: MediaCategory, maxDirs: Int = 80): List<IndexedFile> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<IndexedFile>()
            val queue = ArrayDeque<Pair<String?, String>>()
            queue.add(null to "")
            val volumes = runCatching { listStorageVolumes().filter { it.available } }.getOrDefault(emptyList())
            volumes.forEach { queue.add(it.id to "") }
            var visited = 0
            while (queue.isNotEmpty() && visited < maxDirs) {
                val (vol, path) = queue.removeFirst()
                visited++
                val listing = runCatching {
                    if (vol == null) listDirectory(path) else listStorageDirectory(vol, path)
                }.getOrNull() ?: continue
                for (entry in listing.items) {
                    if (entry.name.startsWith(".")) continue
                    val fullPath = if (path.isEmpty()) entry.name else "$path/${entry.name}"
                    if (entry.isDirectory) {
                        queue.add(vol to fullPath)
                    } else {
                        val matches = when (category) {
                            MediaCategory.MUSIC -> MediaExtensions.isAudio(entry.name)
                            MediaCategory.VIDEO -> MediaExtensions.isVideo(entry.name)
                            MediaCategory.PHOTO -> MediaExtensions.isPhoto(entry.name)
                        }
                        if (matches) {
                            results.add(
                                IndexedFile(
                                    volume = vol ?: "internal",
                                    path = fullPath,
                                    name = entry.name,
                                    type = entry.type,
                                    size = entry.size,
                                    modified = entry.modified,
                                    mime = "",
                                    category = category.apiValue,
                                )
                            )
                        }
                    }
                }
            }
            results
        }

    suspend fun uploadStream(
        remoteDirPath: String,
        volume: String?,
        fileName: String,
        contentLength: Long,
        openInputStream: () -> InputStream?,
        onProgress: (Long, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val url = if (volume == null || volume == "internal") {
            "$baseUrl/api/files/upload".toHttpUrl().newBuilder()
                .addQueryParameter("path", remoteDirPath)
                .build()
        } else {
            "$baseUrl/api/storage/upload".toHttpUrl().newBuilder()
                .addQueryParameter("volume", volume)
                .addQueryParameter("path", remoteDirPath)
                .build()
        }
        val streamingBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = contentLength
            override fun writeTo(sink: BufferedSink) {
                val input = openInputStream() ?: throw IOException("Could not open source file")
                input.use { stream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var uploaded = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        uploaded += read
                        onProgress(uploaded, contentLength)
                    }
                }
            }
        }
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, streamingBody)
            .build()
        val req = Request.Builder().url(url).post(multipart).build()
        executeRequest(req, mediaClient)
    }

    suspend fun downloadToFile(
        remotePath: String,
        volume: String?,
        targetFile: File,
        onProgress: (Long, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val url = downloadUrl(remotePath, volume)
        val req = Request.Builder().url(url).get().build()
        mediaClient.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                throw ApiHttpException(res.code, extractDetail(res.body?.string()) ?: "Download failed (${res.code})")
            }
            val body = res.body ?: throw IOException("Empty response body")
            val total = body.contentLength()
            targetFile.parentFile?.mkdirs()
            val tmpFile = File(targetFile.parentFile, "${targetFile.name}.part")
            body.byteStream().use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            if (targetFile.exists()) targetFile.delete()
            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }
        }
    }

    fun streamUrl(path: String, volume: String? = null): String {
        return if (volume == null || volume == "internal") {
            "$baseUrl/api/files/stream".toHttpUrl().newBuilder()
                .addQueryParameter("path", path)
                .build()
                .toString()
        } else {
            "$baseUrl/api/storage/download".toHttpUrl().newBuilder()
                .addQueryParameter("volume", volume)
                .addQueryParameter("path", path)
                .build()
                .toString()
        }
    }

    fun downloadUrl(path: String, volume: String? = null): String {
        return if (volume == null || volume == "internal") {
            "$baseUrl/api/files/download".toHttpUrl().newBuilder()
                .addQueryParameter("path", path)
                .build()
                .toString()
        } else {
            "$baseUrl/api/storage/download".toHttpUrl().newBuilder()
                .addQueryParameter("volume", volume)
                .addQueryParameter("path", path)
                .build()
                .toString()
        }
    }

    fun videoThumbnailUrl(path: String, volume: String? = null): String {
        val builder = "$baseUrl/api/media/thumbnail".toHttpUrl().newBuilder()
            .addQueryParameter("path", path)
        if (volume != null && volume != "internal") {
            builder.addQueryParameter("volume", volume)
        }
        return builder.build().toString()
    }

    private fun getJson(url: String): JSONObject {
        val req = Request.Builder().url(url).get().build()
        return executeJson(req)
    }

    private fun executeJson(req: Request, client: OkHttpClient = httpClient): JSONObject {
        client.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val detail = extractDetail(raw) ?: "HTTP ${res.code}"
                AppLogger.warning("API request failed", "${req.method} ${req.url.encodedPath} -> ${res.code}: $detail")
                throw ApiHttpException(res.code, detail)
            }
            return if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }
    }

    private fun executeRequest(req: Request, client: OkHttpClient = httpClient) {
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                val raw = res.body?.string().orEmpty()
                val detail = extractDetail(raw) ?: "HTTP ${res.code}"
                AppLogger.warning("API request failed", "${req.method} ${req.url.encodedPath} -> ${res.code}: $detail")
                throw ApiHttpException(res.code, detail)
            }
        }
    }

    private fun extractDetail(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching { JSONObject(raw).optString("detail").takeIf { it.isNotBlank() } }.getOrNull()
    }

    private fun parseListing(json: JSONObject): DirectoryListing {
        val arr = json.optJSONArray("items")
        val items = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        FileEntry(
                            name = o.optString("name"),
                            type = o.optString("type", "file"),
                            size = o.optLong("size", 0L),
                            modified = o.opt("modified")?.toString().orEmpty(),
                            created = o.optStringOrNull("created"),
                        )
                    )
                }
            }
        }
        return DirectoryListing(
            path = json.optString("path", ""),
            items = items,
        )
    }

    private fun parseIndexStatus(json: JSONObject): MediaIndexStatus = MediaIndexStatus(
        state = json.optString("state", "idle"),
        scanned = json.optInt("scanned", 0),
        skipped = json.optInt("skipped", 0),
        lastCompletedAt = json.optLong("last_completed_at", 0L),
        fileCount = json.optInt("file_count", 0),
        mediaCount = json.optInt("media_count", 0),
        lastError = json.optStringOrNull("last_error"),
        lastScanStartedAt = json.optLong("last_scan_started_at", 0L),
        elapsedMs = json.optLong("elapsed_ms", 0L),
        currentSource = json.optStringOrNull("current_source"),
    )

    private fun parseIndexedFiles(arr: org.json.JSONArray?): List<IndexedFile> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    IndexedFile(
                        volume = o.optString("volume", "internal"),
                        path = o.optString("path", ""),
                        name = o.optString("name", ""),
                        type = o.optString("type", "file"),
                        size = o.optLong("size", 0L),
                        modified = o.opt("modified")?.toString().orEmpty(),
                        mime = o.optString("mime", "application/octet-stream"),
                        category = o.optStringOrNull("category"),
                        artist = o.optStringOrNull("artist"),
                        title = o.optStringOrNull("title"),
                        album = o.optStringOrNull("album"),
                        durationMs = o.optLong("duration_ms", 0L),
                        sampleRateHz = o.optInt("sample_rate", 0),
                        bitDepth = o.optInt("bit_depth", 0),
                        hasEmbeddedLyrics = o.optBoolean("has_lyrics", false),
                    )
                )
            }
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val sharedBaseClient: OkHttpClient = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(12, 2, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 12
            })
            .retryOnConnectionFailure(true)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            require(trimmed.isNotEmpty()) { "Enter a server address." }
            val withScheme = if ("://" in trimmed) {
                trimmed
            } else if (trimmed.count { it == ':' } > 1 && !trimmed.startsWith("[")) {
                "http://[$trimmed]"
            } else {
                "http://$trimmed"
            }
            val parsed = Uri.parse(withScheme)
            val scheme = parsed.scheme?.lowercase()
            require(scheme == "http" || scheme == "https") { "Use http:// or https:// for the server address." }
            require(!parsed.host.isNullOrBlank()) { "Enter a valid host or IP address." }
            return withScheme
        }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = optString(key).trim()
    return value.takeIf { it.isNotEmpty() && it != "null" }
}

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }
