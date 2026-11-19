package io.github.augztn.pocketlink.ssdbridge

import android.content.Context
import android.media.MediaMetadataRetriever
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import android.os.Environment
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import android.util.Base64

/**
 * Native replacement for the Termux FastAPI file/storage routes.
 * The listener is bound to all interfaces for Tailscale reachability, while
 * NetworkAccessPolicy rejects cleartext peers outside loopback/Tailscale.
 * Every route except health requires HTTP Basic authentication.
 */
internal class NativeApiServer(
    private val context: Context,
    private val credentials: ServerCredentials,
    listenPort: Int = PORT,
    bindHost: String? = null
) : NanoHTTPD(bindHost, listenPort) {
    private val prefs = context.getSharedPreferences("ssd-bridge", Context.MODE_PRIVATE)
    private val systemTelemetry by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SystemTelemetryReader(context)
    }
    private val scopedStorage by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ScopedStorageRegistry(context)
    }

    private fun resolveInternalRoot(): File =
        if (scopedStorage.hasFullFilesAccess()) scopedStorage.externalStorageDir()
        else scopedStorage.sandboxRootDir()

    private val mediaIndexDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MediaIndexScanner(
            context,
            scopedStorage,
            {
                val ids = linkedSetOf<String>()
                prefs.all.keys.filter { it.startsWith("volume_uri_") }.forEach {
                    ids.add(it.removePrefix("volume_uri_"))
                }
                if (prefs.getString("usb1", null) != null) ids.add("usb1")
                if (prefs.getString("usb2", null) != null) ids.add("usb2")
                ids.mapNotNull { id -> externalRoot(id)?.let { root -> id to root } }
            }
        )
    }
    private val mediaIndex by mediaIndexDelegate

    private fun markMediaIndexDirty() {
        if (mediaIndexDelegate.isInitialized()) mediaIndex.markDirty()
    }

    fun ensureMediaIndexStarted() = mediaIndex.ensureStarted()

    fun mediaIndexStatus(): String {
        mediaIndex.ensureStarted()
        return mediaIndex.status().toString()
    }

    fun refreshMediaIndex(): String {
        mediaIndex.requestScan(force = true)
        return mediaIndex.status().toString()
    }

    override fun stop() {
        super.stop()
        if (mediaIndexDelegate.isInitialized()) {
            runCatching { mediaIndex.close() }
        }
    }

    override fun useGzipWhenAccepted(r: Response): Boolean {
        if (r.getHeader("content-range") != null || r.getHeader("accept-ranges") != null) {
            return false
        }
        val mime = r.mimeType?.lowercase(Locale.ROOT).orEmpty()
        if (mime.startsWith("video/") || mime.startsWith("audio/") || mime.startsWith("image/") || mime == "application/octet-stream") {
            return false
        }
        return super.useGzipWhenAccepted(r)
    }

    override fun serve(session: IHTTPSession): Response {
        // The native listener is cleartext HTTP. Trust only loopback or peers
        // arriving through Tailscale's encrypted WireGuard address ranges.
        if (!NetworkAccessPolicy.allowsCleartextPeer(session.remoteIpAddress)) {
            return jsonError(
                Response.Status.FORBIDDEN,
                "Cleartext access is limited to localhost and Tailscale peers"
            )
        }
        if (session.uri == "/health" && session.method == Method.GET) {
            return json(JSONObject().put("status", "ok").put("service", "pocketlink-native"))
        }
        if (!authorized(session)) {
            return jsonError(Response.Status.UNAUTHORIZED, "Unauthorized")
                .apply { addHeader("WWW-Authenticate", "Basic realm=\"PocketLink\"") }
        }

        return try {
            val query = session.parameters.mapValues { (_, values) -> values.firstOrNull().orEmpty() }
            val path = query["path"].orEmpty()
            val isGetOrHead = session.method == Method.GET || session.method == Method.HEAD
            when {
                session.uri == "/api/files/" && session.method == Method.GET ->
                    listInternal(path)
                (session.uri == "/api/files/download" || session.uri == "/api/files/stream") &&
                    isGetOrHead -> serveInternalFile(path, session, session.uri.endsWith("/stream"))
                session.uri == "/api/files/upload" && session.method == Method.POST ->
                    uploadInternal(path, session)
                session.uri == "/api/files/mkdir" && session.method == Method.POST ->
                    mutateInternal(session, "mkdir")
                session.uri == "/api/files/rename" && session.method == Method.PATCH ->
                    mutateInternal(session, "rename")
                session.uri == "/api/files/item" && session.method == Method.DELETE ->
                    deleteInternal(path)
                session.uri == "/api/storage/volumes" && session.method == Method.GET ->
                    listVolumes()
                session.uri == "/api/system/telemetry" && session.method == Method.GET ->
                    json(systemTelemetry.snapshot())
                session.uri == "/api/storage/list" && session.method == Method.GET ->
                    listExternal(query["volume"], path)
                session.uri == "/api/storage/download" && isGetOrHead ->
                    serveExternalFile(query["volume"], path, session)
                session.uri == "/api/storage/upload" && session.method == Method.POST ->
                    uploadExternal(query["volume"], path, session)
                session.uri == "/api/storage/mkdir" && session.method == Method.POST ->
                    mutateExternal(session, "mkdir")
                session.uri == "/api/storage/rename" && session.method == Method.POST ->
                    mutateExternal(session, "rename")
                session.uri == "/api/storage/item" && session.method == Method.DELETE ->
                    deleteExternal(query["volume"], path)
                session.uri == "/api/index/status" && session.method == Method.GET -> {
                    mediaIndex.ensureStarted()
                    json(mediaIndex.status())
                }
                session.uri == "/api/index/scan" && session.method == Method.POST -> {
                    mediaIndex.requestScan(force = true)
                    json(mediaIndex.status())
                }
                session.uri == "/api/index/media" && session.method == Method.GET -> {
                    mediaIndex.ensureStarted()
                    val category = query["category"]?.takeIf { it in setOf("music", "video", "photo") }
                        ?: return bad("Invalid media category")
                    val limit = query["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                    val offset = query["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    val items = JSONArray()
                    mediaIndex.media(category, query["q"].orEmpty(), limit, offset).forEach { item ->
                        items.put(JSONObject()
                            .put("volume", item.volume)
                            .put("path", item.path)
                            .put("name", item.name)
                            .put("type", item.type)
                            .put("size", item.size)
                            .put("modified", isoDate(item.modified))
                            .put("mime", item.mime)
                            .put("category", item.category))
                    }
                    json(JSONObject().put("items", items).put("status", mediaIndex.status()))
                }
                session.uri == "/api/index/files" && session.method == Method.GET -> {
                    mediaIndex.ensureStarted()
                    val limit = query["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
                    val offset = query["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    val items = JSONArray()
                    mediaIndex.files(query["q"].orEmpty(), limit, offset).forEach { item ->
                        items.put(JSONObject()
                            .put("volume", item.volume)
                            .put("path", item.path)
                            .put("name", item.name)
                            .put("type", item.type)
                            .put("size", item.size)
                            .put("modified", isoDate(item.modified))
                            .put("mime", item.mime))
                    }
                    json(JSONObject().put("items", items).put("status", mediaIndex.status()))
                }
                session.uri == "/api/media/metadata" && session.method == Method.GET ->
                    videoMetadata(query["volume"], path)
                session.uri == "/api/media/thumbnail" && session.method == Method.GET ->
                    videoThumbnail(query["volume"], path)
                else -> jsonError(Response.Status.NOT_FOUND, "Endpoint not found")
            }
        } catch (error: SecurityException) {
            jsonError(Response.Status.FORBIDDEN, "Android storage permission was revoked")
        } catch (error: Exception) {
            jsonError(Response.Status.INTERNAL_ERROR, error.message ?: "Storage operation failed")
        }
    }

    private fun authorized(session: IHTTPSession): Boolean {
        val header = session.headers["authorization"] ?: return false
        if (!header.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = try {
            String(Base64.decode(header.substringAfter(' ').trim(), Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val separator = decoded.indexOf(':')
        if (separator <= 0) return false
        val username = decoded.substring(0, separator)
        if (username != USERNAME) return false
        return credentials.verify(decoded.substring(separator + 1).toCharArray())
    }

    private fun listInternal(relative: String): Response {
        val listed = scopedStorage.listDirectory(relative) ?: return notFound("Folder not found")
        val items = JSONArray()
        listed.forEach { entry ->
            val obj = JSONObject()
                .put("name", entry.name)
                .put("type", if (entry.isDirectory) "directory" else "file")
                .put("size", entry.size)
                .put("modified", isoDate(entry.modifiedMs))
            entry.createdMs?.let { obj.put("created", isoDate(it)) }
            items.put(obj)
        }
        return json(JSONObject().put("path", relative).put("items", items))
    }

    private fun serveInternalFile(relative: String, session: IHTTPSession, inline: Boolean): Response {
        val target = scopedStorage.resolveReadable(relative) ?: return notFound("File not found")
        return when (target) {
            is InternalReadable.LocalFile -> fileResponse(target.file, session, inline)
            is InternalReadable.SafDoc -> contentUriResponse(
                uri = target.doc.uri,
                fileName = target.name,
                length = target.doc.length(),
                mime = target.doc.type?.takeIf { it.isNotBlank() } ?: mimeFor(target.name),
                session = session,
                inline = inline,
            )
            is InternalReadable.ContentItem -> contentUriResponse(
                uri = target.uri,
                fileName = target.name,
                length = target.size,
                mime = target.mime.takeIf { it.isNotBlank() } ?: mimeFor(target.name),
                session = session,
                inline = inline,
            )
        }
    }

    private fun contentUriResponse(
        uri: Uri,
        fileName: String,
        length: Long,
        mime: String,
        session: IHTTPSession,
        inline: Boolean,
    ): Response {
        val range = session.headers["range"]
        val isHead = session.method == Method.HEAD
        val response = if (range.isNullOrBlank()) {
            if (isHead) {
                DisconnectSafeResponse.headOnly(Response.Status.OK, mime, length)
            } else {
                val input = context.contentResolver.openInputStream(uri)
                    ?: return notFound("Cannot open file")
                val buffered = BufferedInputStream(input, STREAM_BUFFER_SIZE)
                if (length > 0L) {
                    DisconnectSafeResponse.fixedLength(Response.Status.OK, mime, buffered, length)
                } else {
                    DisconnectSafeResponse.chunked(Response.Status.OK, mime, buffered)
                }
            }
        } else {
            if (length <= 0L) {
                return jsonError(Response.Status.RANGE_NOT_SATISFIABLE, "Byte ranges are unavailable for this storage provider")
            }
            val bounds = parseRange(range, length)
                ?: return jsonError(Response.Status.RANGE_NOT_SATISFIABLE, "Invalid byte range")
                    .apply { addHeader("Content-Range", "bytes */$length") }
            val (start, end) = bounds
            val rangeLength = end - start + 1
            if (isHead) {
                DisconnectSafeResponse.headOnly(Response.Status.PARTIAL_CONTENT, mime, rangeLength)
                    .apply { addHeader("Content-Range", "bytes $start-$end/$length") }
            } else {
                val input = openExternalRangeStream(uri, start)
                    ?: return jsonError(Response.Status.RANGE_NOT_SATISFIABLE, "Could not seek to byte range")
                DisconnectSafeResponse.fixedLength(
                    Response.Status.PARTIAL_CONTENT, mime, input, rangeLength
                ).apply { addHeader("Content-Range", "bytes $start-$end/$length") }
            }
        }
        response.setGzipEncoding(false)
        if (length > 0L) response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Cache-Control", "no-transform")
        response.addHeader("Content-Disposition", "${if (inline) "inline" else "attachment"}; filename*=UTF-8''${java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")}")
        return response
    }

    private fun fileResponse(file: File, session: IHTTPSession, inline: Boolean): Response {
        val length = file.length()
        val mime = mimeFor(file.name)
        val range = session.headers["range"]
        val isHead = session.method == Method.HEAD
        val response: Response
        if (range.isNullOrBlank()) {
            response = if (isHead) {
                DisconnectSafeResponse.headOnly(Response.Status.OK, mime, length)
            } else {
                DisconnectSafeResponse.fixedLength(
                    Response.Status.OK, mime, BufferedInputStream(FileInputStream(file), STREAM_BUFFER_SIZE), length
                )
            }
        } else {
            val bounds = parseRange(range, length) ?: return jsonError(Response.Status.RANGE_NOT_SATISFIABLE, "Invalid byte range")
                .apply { addHeader("Content-Range", "bytes */$length") }
            val (start, end) = bounds
            val rangeLength = end - start + 1
            response = if (isHead) {
                DisconnectSafeResponse.headOnly(Response.Status.PARTIAL_CONTENT, mime, rangeLength)
                    .apply { addHeader("Content-Range", "bytes $start-$end/$length") }
            } else {
                val input = FileInputStream(file)
                try {
                    input.channel.position(start)
                } catch (_: Exception) {
                    input.close()
                    return jsonError(Response.Status.RANGE_NOT_SATISFIABLE, "Could not seek to byte range")
                }
                DisconnectSafeResponse.fixedLength(
                    Response.Status.PARTIAL_CONTENT, mime,
                    BufferedInputStream(input, STREAM_BUFFER_SIZE), rangeLength
                ).apply { addHeader("Content-Range", "bytes $start-$end/$length") }
            }
        }
        response.setGzipEncoding(false)
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Cache-Control", "no-transform")
        response.addHeader("Content-Disposition", "${if (inline) "inline" else "attachment"}; filename*=UTF-8''${java.net.URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")}")
        return response
    }

    private fun uploadInternal(relative: String, session: IHTTPSession): Response {
        val writable = scopedStorage.resolveWritableDir(relative)
            ?: return notFound("Destination folder is not writable or permission not granted")
        val (name, temp) = multipartFile(session) ?: return bad("Multipart field 'file' is required")
        if (!validName(name)) { temp.delete(); return bad("Invalid filename") }
        return when (writable) {
            is InternalWritableDir.LocalDir -> {
                val parent = writable.dir
                val target = File(parent, name)
                if (target.exists()) { temp.delete(); return jsonError(Response.Status.CONFLICT, "An item with this name already exists") }
                val staging = File(parent, ".pocketlink-upload-${UUID.randomUUID()}.part")
                try {
                    if (!temp.renameTo(staging)) temp.copyTo(staging, overwrite = false).also { temp.delete() }
                    if (!staging.renameTo(target)) {
                        staging.delete()
                        jsonError(Response.Status.INTERNAL_ERROR, "Could not finalize uploaded file")
                    } else json(JSONObject().put("ok", true).put("name", name)).also { markMediaIndexDirty() }
                } finally {
                    temp.delete()
                    if (staging.exists()) staging.delete()
                }
            }
            is InternalWritableDir.SafDir -> {
                val parent = writable.dir
                if (parent.findFile(name) != null) {
                    temp.delete()
                    return jsonError(Response.Status.CONFLICT, "An item with this name already exists")
                }
                val staged = parent.createFile(mimeFor(name), ".pocketlink-upload-${UUID.randomUUID()}.part")
                    ?: run { temp.delete(); return jsonError(Response.Status.INTERNAL_ERROR, "Could not stage upload") }
                try {
                    context.contentResolver.openOutputStream(staged.uri, "wt")?.use { output ->
                        temp.inputStream().use { input -> input.copyTo(output) }
                    } ?: return jsonError(Response.Status.INTERNAL_ERROR, "Cannot write file")
                    if (!staged.renameTo(name)) jsonError(Response.Status.INTERNAL_ERROR, "Could not finalize uploaded file")
                    else json(JSONObject().put("ok", true).put("name", name)).also { markMediaIndexDirty() }
                } finally {
                    temp.delete()
                    if (staged.exists()) staged.delete()
                }
            }
        }
    }

    private fun mutateInternal(session: IHTTPSession, operation: String): Response {
        val body = jsonBody(session) ?: return bad("Invalid JSON body")
        val relative = body.optString("path")
        val name = if (operation == "mkdir") body.optString("name") else body.optString("new_name")
        if (!validName(name)) return bad("Invalid name")

        if (operation == "mkdir") {
            val writable = scopedStorage.resolveWritableDir(relative)
                ?: return notFound("Folder not found or not writable")
            return when (writable) {
                is InternalWritableDir.LocalDir -> {
                    val target = File(writable.dir, name)
                    if (target.exists()) return jsonError(Response.Status.CONFLICT, "Name already exists")
                    if (target.mkdir()) {
                        markMediaIndexDirty()
                        json(JSONObject().put("ok", true).put("name", name))
                    } else jsonError(Response.Status.INTERNAL_ERROR, "Could not create folder")
                }
                is InternalWritableDir.SafDir -> {
                    if (writable.dir.findFile(name) != null) return jsonError(Response.Status.CONFLICT, "Name already exists")
                    val created = writable.dir.createDirectory(name)
                        ?: return jsonError(Response.Status.INTERNAL_ERROR, "Could not create folder")
                    markMediaIndexDirty()
                    json(JSONObject().put("ok", true).put("name", created.name ?: name))
                }
            }
        }

        val target = scopedStorage.resolveWritableTarget(relative) ?: return notFound("Item not found")
        return when (target) {
            is InternalWritableTarget.LocalTarget -> {
                val parent = target.file.parentFile ?: return bad("Invalid parent folder")
                val dest = File(parent, name)
                if (dest.exists()) return jsonError(Response.Status.CONFLICT, "Name already exists")
                if (target.file.renameTo(dest)) {
                    markMediaIndexDirty()
                    json(JSONObject().put("ok", true).put("name", name))
                } else jsonError(Response.Status.INTERNAL_ERROR, "Rename failed")
            }
            is InternalWritableTarget.SafTarget -> {
                if (target.doc.parentFile?.findFile(name) != null) return jsonError(Response.Status.CONFLICT, "Name already exists")
                if (target.doc.renameTo(name)) {
                    markMediaIndexDirty()
                    json(JSONObject().put("ok", true).put("name", target.doc.name ?: name))
                } else jsonError(Response.Status.INTERNAL_ERROR, "Rename failed")
            }
        }
    }

    private fun deleteInternal(relative: String): Response {
        val target = scopedStorage.resolveWritableTarget(relative) ?: return notFound("Item not found")
        return when (target) {
            is InternalWritableTarget.LocalTarget -> {
                val file = target.file
                if (file.isDirectory && (file.list()?.isNotEmpty() == true)) return bad("Folder is not empty")
                if (file.delete()) {
                    markMediaIndexDirty()
                    json(JSONObject().put("ok", true))
                } else jsonError(Response.Status.INTERNAL_ERROR, "Delete failed")
            }
            is InternalWritableTarget.SafTarget -> {
                if (target.doc.delete()) {
                    markMediaIndexDirty()
                    json(JSONObject().put("ok", true))
                } else jsonError(Response.Status.INTERNAL_ERROR, "Delete failed")
            }
        }
    }

    private fun listVolumes(): Response {
        val items = JSONArray()
        val ids = linkedSetOf<String>()
        prefs.all.keys.filter { it.startsWith("volume_uri_") }.forEach {
            ids.add(it.removePrefix("volume_uri_"))
        }
        if (prefs.getString("usb1", null) != null) ids.add("usb1")
        if (prefs.getString("usb2", null) != null) ids.add("usb2")
        discoveredRemovableVolumes().forEach { (id, _) -> ids.add(id) }
        ids.forEach { id ->
            val root = externalRoot(id)
            val available = root?.exists() == true
            val legacyName = when (id) { "usb1" -> "USB Storage 1"; "usb2" -> "USB Storage 2"; else -> id }
            val item = JSONObject()
                .put("id", id)
                .put("name", prefs.getString("volume_name_$id", null) ?: legacyName)
                .put("available", available)
                .put("writable", available && root?.canWrite() == true)

            if (available && root != null) {
                val capacity = if (root.uri.scheme == "file") {
                    try {
                        val stats = StatFs(root.uri.path ?: return@forEach)
                        externalVolumeCapacity(stats.totalBytes, stats.availableBytes)
                    } catch (_: Exception) { null }
                } else {
                    readExternalVolumeCapacity(root.uri)
                }
                capacity?.let {
                    item.put("total_bytes", it.totalBytes)
                        .put("used_bytes", it.usedBytes)
                        .put("available_bytes", it.availableBytes)
                        .put("usage_percent", it.usagePercent)
                }
            }
            items.put(item)
        }
        return json(JSONObject().put("items", items))
    }

    /**
     * Reads real filesystem capacity for Android's external-storage document provider.
     * Other SAF providers and Android versions without a public volume directory API
     * intentionally return no capacity rather than estimating it from directory contents.
     */
    private fun readExternalVolumeCapacity(treeUri: Uri): ExternalVolumeCapacity? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (treeUri.authority != "com.android.externalstorage.documents") return null

        return try {
            val documentId = DocumentsContract.getTreeDocumentId(treeUri)
            val volumeId = externalStorageVolumeId(documentId) ?: return null
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                ?: return null
            val storageVolume = storageManager.storageVolumes.firstOrNull { volume ->
                volume.isRemovable &&
                    volume.uuid?.equals(volumeId, ignoreCase = true) == true &&
                    volume.state in setOf(
                        Environment.MEDIA_MOUNTED,
                        Environment.MEDIA_MOUNTED_READ_ONLY
                    )
            } ?: return null
            val directory = storageVolume.directory ?: return null
            if (!directory.isDirectory) return null

            val stats = StatFs(directory.absolutePath)
            externalVolumeCapacity(stats.totalBytes, stats.availableBytes)
        } catch (_: Exception) {
            // Capacity is optional; a provider/stat failure must not break volume discovery.
            null
        }
    }

    private fun listExternal(volume: String?, relative: String): Response {
        val root = externalRoot(volume) ?: return notFound("Storage permission not granted")
        val directory = resolveDocument(root, relative) ?: return notFound("Folder not found")
        if (!directory.isDirectory) return bad("Not a directory")
        val items = JSONArray()
        directory.listFiles().filter { !it.name.orEmpty().startsWith(".") }
            .sortedWith(compareBy<DocumentFile>({ !it.isDirectory }, { it.name.orEmpty().lowercase(Locale.ROOT) }))
            .forEach { item ->
                items.put(JSONObject().put("name", item.name ?: "")
                    .put("type", if (item.isDirectory) "directory" else "file")
                    .put("size", item.length()).put("modified", isoDate(item.lastModified())))
            }
        return json(JSONObject().put("path", relative).put("items", items))
    }

    private fun serveExternalFile(volume: String?, relative: String, session: IHTTPSession): Response {
        val root = externalRoot(volume) ?: return notFound("Storage permission not granted")
        val item = resolveDocument(root, relative) ?: return notFound("File not found")
        if (!item.isFile) return bad("Not a file")
        val length = item.length()
        val mime = item.type?.takeIf { it.isNotBlank() } ?: mimeFor(item.name.orEmpty())
        val range = session.headers["range"]
        val isHead = session.method == Method.HEAD
        val response = if (range.isNullOrBlank()) {
            if (isHead) {
                DisconnectSafeResponse.headOnly(Response.Status.OK, mime, length)
            } else {
                val input = context.contentResolver.openInputStream(item.uri)
                    ?: return notFound("Cannot open file")
                val buffered = BufferedInputStream(input, STREAM_BUFFER_SIZE)
                if (length > 0L) {
                    DisconnectSafeResponse.fixedLength(Response.Status.OK, mime, buffered, length)
                } else {
                    DisconnectSafeResponse.chunked(Response.Status.OK, mime, buffered)
                }
            }
        } else {
            if (length <= 0L) {
                return jsonError(Response.Status.RANGE_NOT_SATISFIABLE, "Byte ranges are unavailable for this storage provider")
            }
            val bounds = parseRange(range, length)
                ?: return jsonError(Response.Status.RANGE_NOT_SATISFIABLE, "Invalid byte range")
                    .apply { addHeader("Content-Range", "bytes */$length") }
            val (start, end) = bounds
            val rangeLength = end - start + 1
            if (isHead) {
                DisconnectSafeResponse.headOnly(Response.Status.PARTIAL_CONTENT, mime, rangeLength)
                    .apply { addHeader("Content-Range", "bytes $start-$end/$length") }
            } else {
                val input = openExternalRangeStream(item.uri, start)
                    ?: return jsonError(Response.Status.RANGE_NOT_SATISFIABLE, "Could not seek to byte range")
                DisconnectSafeResponse.fixedLength(
                    Response.Status.PARTIAL_CONTENT, mime, input, rangeLength
                ).apply { addHeader("Content-Range", "bytes $start-$end/$length") }
            }
        }
        response.setGzipEncoding(false)
        if (length > 0L) response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Cache-Control", "no-transform")
        return response
    }

    /**
     * Prefer a seekable descriptor for SAF range requests. Some providers expose
     * non-seekable pipes, so retain a streaming skip fallback for compatibility.
     */
    private fun openExternalRangeStream(uri: Uri, offset: Long): BufferedInputStream? {
        val descriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Exception) {
            null
        }
        if (descriptor != null) {
            val fileInput = FileInputStream(descriptor.fileDescriptor)
            try {
                fileInput.channel.position(offset)
                val owned = object : FilterInputStream(fileInput) {
                    override fun close() {
                        try { super.close() } finally { descriptor.close() }
                    }
                }
                return BufferedInputStream(owned, STREAM_BUFFER_SIZE)
            } catch (_: Exception) {
                try { fileInput.close() } catch (_: Exception) { }
                try { descriptor.close() } catch (_: Exception) { }
            }
        }

        val input = context.contentResolver.openInputStream(uri) ?: return null
        var skipped = 0L
        val discard = ByteArray(STREAM_BUFFER_SIZE)
        while (skipped < offset) {
            val remaining = offset - skipped
            val count = try {
                val advanced = input.skip(remaining)
                if (advanced > 0L) advanced
                else input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt()).toLong()
            } catch (_: Exception) {
                -1L
            }
            if (count <= 0L) {
                input.close()
                return null
            }
            skipped += count
        }
        return BufferedInputStream(input, STREAM_BUFFER_SIZE)
    }

    private fun uploadExternal(volume: String?, relative: String, session: IHTTPSession): Response {
        val root = externalRoot(volume) ?: return notFound("Storage permission not granted")
        val parent = resolveDocument(root, relative) ?: return notFound("Destination folder not found")
        if (!parent.isDirectory) return bad("Destination is not a folder")
        val (name, temp) = multipartFile(session) ?: return bad("Multipart field 'file' is required")
        if (!validName(name)) { temp.delete(); return bad("Invalid filename") }
        if (parent.findFile(name) != null) { temp.delete(); return jsonError(Response.Status.CONFLICT, "Name already exists") }
        val staged = parent.createFile(mimeFor(name), ".pocketlink-upload-${UUID.randomUUID()}.part")
            ?: run { temp.delete(); return jsonError(Response.Status.INTERNAL_ERROR, "Could not stage upload") }
        return try {
            context.contentResolver.openOutputStream(staged.uri, "wt")?.use { output ->
                temp.inputStream().use { input -> input.copyTo(output) }
            } ?: return jsonError(Response.Status.INTERNAL_ERROR, "Cannot write file")
            if (!staged.renameTo(name)) jsonError(Response.Status.INTERNAL_ERROR, "Could not finalize uploaded file")
            else json(JSONObject().put("ok", true).put("name", name)).also { markMediaIndexDirty() }
        } finally {
            temp.delete()
            if (staged.exists()) staged.delete()
        }
    }

    private fun mutateExternal(session: IHTTPSession, operation: String): Response {
        val body = jsonBody(session) ?: return bad("Invalid JSON body")
        val root = externalRoot(body.optString("volume")) ?: return notFound("Storage permission not granted")
        val relative = body.optString("path")
        val name = if (operation == "mkdir") body.optString("name") else body.optString("new_name")
        if (!validName(name)) return bad("Invalid name")
        val target: DocumentFile
        if (operation == "mkdir") {
            val parent = resolveDocument(root, relative) ?: return notFound("Folder not found")
            if (parent.findFile(name) != null) return jsonError(Response.Status.CONFLICT, "Name already exists")
            target = parent.createDirectory(name) ?: return jsonError(Response.Status.INTERNAL_ERROR, "Could not create folder")
        } else {
            target = resolveDocument(root, relative) ?: return notFound("Item not found")
            if (target.parentFile?.findFile(name) != null) return jsonError(Response.Status.CONFLICT, "Name already exists")
            if (!target.renameTo(name)) return jsonError(Response.Status.INTERNAL_ERROR, "Rename failed")
        }
        return json(JSONObject().put("ok", true).put("name", target.name ?: name)).also { markMediaIndexDirty() }
    }

    private fun deleteExternal(volume: String?, relative: String): Response {
        val root = externalRoot(volume) ?: return notFound("Storage permission not granted")
        if (relative.isBlank()) return bad("Cannot delete storage root")
        val item = resolveDocument(root, relative) ?: return notFound("Item not found")
        return if (item.delete()) {
            markMediaIndexDirty()
            json(JSONObject().put("ok", true))
        } else jsonError(Response.Status.INTERNAL_ERROR, "Delete failed")
    }

    private fun withVideoSource(volume: String?, relative: String, action: (MediaMetadataRetriever) -> Response): Response {
        if (relative.isBlank()) return bad("Video path is required")
        val retriever = MediaMetadataRetriever()
        return try {
            if (volume.isNullOrBlank()) {
                when (val resolved = scopedStorage.resolveReadable(relative)) {
                    is InternalReadable.LocalFile -> {
                        if (!isVideoFile(resolved.file.name)) return bad("Not a readable video")
                        retriever.setDataSource(resolved.file.absolutePath)
                    }
                    is InternalReadable.SafDoc -> {
                        if (!isVideoFile(resolved.name)) return bad("Not a video file")
                        retriever.setDataSource(context, resolved.doc.uri)
                    }
                    is InternalReadable.ContentItem -> {
                        if (!isVideoFile(resolved.name)) return bad("Not a video file")
                        retriever.setDataSource(context, resolved.uri)
                    }
                    null -> return notFound("Video not found")
                }
            } else {
                val root = externalRoot(volume) ?: return notFound("Storage permission not granted")
                val document = resolveDocument(root, relative) ?: return notFound("Video not found")
                if (!document.isFile || !isVideoFile(document.name.orEmpty())) return bad("Not a video file")
                retriever.setDataSource(context, document.uri)
            }
            action(retriever)
        } catch (_: SecurityException) {
            jsonError(Response.Status.FORBIDDEN, "Storage permission was revoked")
        } catch (_: Exception) {
            notFound("Video metadata is unavailable")
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    private fun videoMetadata(volume: String?, relative: String): Response =
        withVideoSource(volume, relative) { retriever ->
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            json(JSONObject().put("duration_ms", duration)
                .put("width", if (rotation == 90 || rotation == 270) height else width)
                .put("height", if (rotation == 90 || rotation == 270) width else height))
        }

    private fun videoThumbnail(volume: String?, relative: String): Response =
        withVideoSource(volume, relative) { retriever ->
            val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@withVideoSource notFound("Video thumbnail unavailable")
            val thumbnail = if (frame.width > 640) {
                Bitmap.createScaledBitmap(
                    frame, 640, (frame.height * 640f / frame.width).toInt().coerceAtLeast(1), true
                )
            } else frame
            val output = ByteArrayOutputStream()
            try {
                if (!thumbnail.compress(Bitmap.CompressFormat.JPEG, 78, output)) {
                    return@withVideoSource jsonError(Response.Status.INTERNAL_ERROR, "Could not encode thumbnail")
                }
                val bytes = output.toByteArray()
                DisconnectSafeResponse.fixedLength(
                    Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()
                ).apply {
                    setGzipEncoding(false)
                    addHeader("Cache-Control", "private, max-age=86400")
                }
            } finally {
                if (thumbnail !== frame) thumbnail.recycle()
                frame.recycle()
                output.close()
            }
        }

    private fun isVideoFile(name: String): Boolean =
        name.substringAfterLast(".", "").lowercase(Locale.ROOT) in setOf(
            "mp4", "m4v", "mov", "mkv", "webm", "avi", "wmv", "mpeg", "mpg",
            "3gp", "ts", "m2ts", "mts", "flv", "f4v", "3g2", "m2v", "ogv", "vob", "rmvb"
        )
    private fun internalFile(relative: String): File? {
        val root = resolveInternalRoot()
        if (relative.isBlank()) return root
        val parts = relative.split('/')
        if (parts.any { !validName(it) }) return null
        val target = File(root, relative).canonicalFile
        val prefix = root.path + File.separator
        return if (target.path == root.path || target.path.startsWith(prefix)) target else null
    }

    private fun externalRoot(volume: String?): DocumentFile? {
        val id = volume?.takeIf {
            it == "usb1" || it == "usb2" ||
                Regex("usb-(?:[a-f0-9]{16}|manual-[a-f0-9]{16})").matches(it)
        } ?: return null
        val raw = prefs.getString("volume_uri_$id", null)
            ?: prefs.getString(id, null)
        if (raw != null) {
            DocumentFile.fromTreeUri(context, Uri.parse(raw))
                ?.takeIf { it.exists() && it.canRead() }
                ?.let { return it }
        }

        // StorageManager exposes mounted removable-volume paths to the Android
        // app process. Termux cannot inherit this app's all-files permission, so
        // all direct SD-card I/O stays inside this native server.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            !Environment.isExternalStorageManager()
        ) return null
        val volumeInfo = discoveredRemovableVolumes().firstOrNull { it.first == id } ?: return null
        val directory = volumeInfo.second.directory ?: return null
        if (!directory.isDirectory || !directory.canRead()) return null
        return DocumentFile.fromFile(directory)
    }

    private fun discoveredRemovableVolumes(): List<Pair<String, android.os.storage.StorageVolume>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptyList()
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return emptyList()
        return manager.storageVolumes.mapNotNull { volume ->
            if (!volume.isRemovable || volume.state !in setOf(
                    Environment.MEDIA_MOUNTED,
                    Environment.MEDIA_MOUNTED_READ_ONLY
                )
            ) return@mapNotNull null
            val directory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) volume.directory else null
            val identity = externalVolumeIdentity(
                volume.uuid,
                directory?.absolutePath,
                volume.getDescription(context).toString()
            )
            externalVolumeKey(identity) to volume
        }
    }

    private fun resolveDocument(root: DocumentFile, relative: String): DocumentFile? {
        var current = root
        if (relative.isBlank()) return current
        for (part in relative.split('/')) {
            if (!validName(part)) return null
            current = current.findFile(part) ?: return null
        }
        return current
    }

    private fun multipartFile(session: IHTTPSession): Pair<String, File>? {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val tempPath = files["file"] ?: files["content"] ?: return null
        val name = session.parameters["file"]?.firstOrNull()
            ?: session.parameters["filename"]?.firstOrNull()
            ?: return null
        return name to File(tempPath)
    }

    private fun jsonBody(session: IHTTPSession): JSONObject? {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"]?.let { File(it).readText() }
            ?: files["content"]?.let { File(it).readText() }
            ?: return null
        return try { JSONObject(raw) } catch (_: Exception) { null }
    }

    private fun fileJson(file: File): JSONObject {
        val value = JSONObject()
            .put("name", file.name)
            .put("type", if (file.isDirectory) "directory" else "file")
            .put("size", if (file.isFile) file.length() else 0L)
            .put("modified", isoDate(file.lastModified()))
        // Filesystem birth time is not available on every Android filesystem.
        // Only publish it when the provider supplies a valid value.
        if (file.isFile && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val created = java.nio.file.Files.readAttributes(
                    file.toPath(),
                    java.nio.file.attribute.BasicFileAttributes::class.java
                ).creationTime().toMillis()
                if (created > 0L) value.put("created", isoDate(created))
            } catch (_: Exception) {
                // Some Android/FUSE filesystems do not expose birth time.
            }
        }
        return value
    }

    private fun parseRange(value: String, length: Long): Pair<Long, Long>? {
        if (length <= 0L) return null
        val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(value) ?: return null
        val first = match.groupValues[1]
        val last = match.groupValues[2]
        val start: Long
        val end: Long
        if (first.isEmpty()) {
            val suffix = last.toLongOrNull()?.takeIf { it > 0L } ?: return null
            start = (length - suffix).coerceAtLeast(0L)
            end = length - 1
        } else {
            start = first.toLongOrNull() ?: return null
            end = (last.toLongOrNull() ?: (length - 1)).coerceAtMost(length - 1)
        }
        if (start < 0L || start >= length || end < start) return null
        return start to end
    }

    private fun validName(value: String): Boolean =
        value.isNotBlank() && value != "." && value != ".." &&
            !value.startsWith(".") && !value.contains('/') && !value.contains('\\') &&
            !value.any { it.code < 32 }

    private fun isoDate(timestamp: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(timestamp))
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
        "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "mp4" -> "video/mp4"; "aac" -> "audio/aac"
        "flac" -> "audio/flac"; "ogg", "opus" -> "audio/ogg"; "wav" -> "audio/wav"
        "mkv" -> "video/x-matroska"; "pdf" -> "application/pdf"; "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun bad(message: String) = jsonError(Response.Status.BAD_REQUEST, message)
    private fun notFound(message: String) = jsonError(Response.Status.NOT_FOUND, message)
    private fun jsonError(status: Response.Status, message: String) =
        json(JSONObject().put("detail", message), status)
    private fun json(value: JSONObject, status: Response.Status = Response.Status.OK) =
        DisconnectSafeResponse.fixedLength(status, "application/json; charset=utf-8", value.toString())

    companion object {
        const val PORT = 8000
        const val USERNAME = "admin"
        private const val STREAM_BUFFER_SIZE = 64 * 1024
    }
}
