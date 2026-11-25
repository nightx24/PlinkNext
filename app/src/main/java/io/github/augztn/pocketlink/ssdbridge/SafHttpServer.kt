package io.github.augztn.pocketlink.ssdbridge

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class SafHttpServer(
    private val context: Context,
    private val token: String,
    listenPort: Int = DEFAULT_PORT,
) : NanoHTTPD("127.0.0.1", listenPort) {
    private val prefs = context.getSharedPreferences("ssd-bridge", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_PORT = 8765
    }

    override fun useGzipWhenAccepted(r: Response): Boolean {
        if (r.getHeader("content-range") != null || r.getHeader("accept-ranges") != null) {
            return false
        }
        return super.useGzipWhenAccepted(r)
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/health" && session.method == Method.GET) {
            return json(JSONObject().put("status", "ok").put("service", "saf-bridge"))
        }
        val supplied = session.headers["authorization"] ?: ""
        if (token.isBlank() || !MessageDigest.isEqual(
                supplied.toByteArray(Charsets.UTF_8),
                "Bearer $token".toByteArray(Charsets.UTF_8)
            )) return text(Response.Status.UNAUTHORIZED, "Unauthorized")

        return try {
            val q = session.parameters.mapValues { (_, values) -> values.firstOrNull().orEmpty() }
            if (session.uri == "/v1/volumes" && session.method == Method.GET) return volumes()
            val volume = q["volume"] ?: return bad("volume is required")
            val root = rootFor(volume) ?: return jsonError(Response.Status.NOT_FOUND, "Storage permission not granted")
            val path = q["path"].orEmpty()
            when {
                session.uri == "/v1/list" && session.method == Method.GET -> {
                    val dir = resolve(root, path) ?: return jsonError(Response.Status.NOT_FOUND, "Folder not found")
                    if (!dir.isDirectory) return jsonError(Response.Status.BAD_REQUEST, "Not a directory")
                    val items = JSONArray()
                    dir.listFiles().forEach { file -> items.put(JSONObject()
                        .put("name", file.name ?: "").put("type", if (file.isDirectory) "directory" else "file")
                        .put("size", file.length()).put("modified", file.lastModified())) }
                    json(JSONObject().put("volume", volume).put("path", path).put("items", items))
                }
                session.uri == "/v1/read" && session.method == Method.GET -> {
                    val file = resolve(root, path) ?: return jsonError(Response.Status.NOT_FOUND, "File not found")
                    if (!file.isFile) return jsonError(Response.Status.BAD_REQUEST, "Not a file")
                    val length = file.length()
                    val input = context.contentResolver.openInputStream(file.uri)
                        ?: return jsonError(Response.Status.INTERNAL_ERROR, "Cannot open file")
                    val range = session.headers["range"]
                    if (range == null) return DisconnectSafeResponse.fixedLength(Response.Status.OK, file.type ?: "application/octet-stream", input, length)
                        .apply { setGzipEncoding(false); addHeader("Accept-Ranges", "bytes") }
                    val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(range)
                        ?: run { input.close(); return jsonError(Response.Status.RANGE_NOT_SATISFIABLE, "Invalid byte range") }
                    if (length <= 0L) { input.close(); return jsonError(Response.Status.RANGE_NOT_SATISFIABLE, "Range outside empty file") }
                    val first = match.groupValues[1]; val last = match.groupValues[2]
                    val start: Long
                    val requestedEnd: Long
                    if (first.isEmpty()) {
                        val suffix = last.toLongOrNull()
                        if (suffix == null || suffix <= 0L) { input.close(); return jsonError(Response.Status.RANGE_NOT_SATISFIABLE, "Invalid suffix range") }
                        start = (length - suffix).coerceAtLeast(0L); requestedEnd = length - 1
                    } else {
                        start = first.toLongOrNull() ?: -1L
                        requestedEnd = last.toLongOrNull() ?: length - 1
                    }
                    if (start < 0L || start >= length || requestedEnd < start) { input.close(); return jsonError(Response.Status.RANGE_NOT_SATISFIABLE, "Range outside file") }
                    val end = minOf(requestedEnd, length - 1)
                    var skipped = 0L
                    while (skipped < start) { val count = input.skip(start - skipped); if (count <= 0) break; skipped += count }
                    if (skipped != start) { input.close(); return jsonError(Response.Status.RANGE_NOT_SATISFIABLE, "Could not seek to byte range") }
                    return DisconnectSafeResponse.fixedLength(Response.Status.PARTIAL_CONTENT, file.type ?: "application/octet-stream", input, end - start + 1)
                        .apply { setGzipEncoding(false); addHeader("Accept-Ranges", "bytes"); addHeader("Content-Range", "bytes $start-$end/$length") }
                }
                session.uri == "/v1/write" && session.method == Method.PUT -> {
                    val name = path.substringAfterLast('/')
                    if (!validName(name)) return bad("Invalid filename")
                    val parentPath = path.substringBeforeLast('/', "")
                    val parent = resolve(root, parentPath) ?: return jsonError(Response.Status.NOT_FOUND, "Parent folder not found")
                    if (!parent.isDirectory) return bad("Parent is not a folder")
                    if (parent.findFile(name) != null) {
                        return jsonError(Response.Status.CONFLICT, "An item with this name already exists")
                    }
                    val bodyFiles = HashMap<String, String>()
                    session.parseBody(bodyFiles)
                    val bodyPath = bodyFiles["content"]
                        ?: return jsonError(Response.Status.BAD_REQUEST, "Upload body is missing")
                    val bodyFile = bodyPath.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
                    val temporaryName = ".pocketlink-upload-${java.util.UUID.randomUUID()}.part"
                    val temporary = parent.createFile(mimeFor(name), temporaryName)
                        ?: return jsonError(Response.Status.INTERNAL_ERROR, "Could not stage upload")
                    try {
                        context.contentResolver.openOutputStream(temporary.uri, "wt")?.use { output ->
                            bodyFile?.inputStream()?.use { input -> input.copyTo(output) }
                        } ?: return jsonError(Response.Status.INTERNAL_ERROR, "Cannot write file")
                        if (!temporary.renameTo(name)) {
                            return jsonError(Response.Status.INTERNAL_ERROR, "Could not finalize uploaded file")
                        }
                    } finally {
                        bodyFile?.delete()
                        if (temporary.exists()) temporary.delete()
                    }
                    json(JSONObject().put("ok", true).put("name", name))
                }
                session.uri == "/v1/mkdir" && session.method == Method.POST -> {
                    val name = q["name"] ?: return bad("name is required")
                    if (!validName(name)) return bad("Invalid folder name")
                    val parent = resolve(root, path) ?: return jsonError(Response.Status.NOT_FOUND, "Parent folder not found")
                    if (parent.findFile(name) != null) return bad("Name already exists")
                    val created = parent.createDirectory(name) ?: return jsonError(Response.Status.INTERNAL_ERROR, "Could not create folder")
                    json(JSONObject().put("ok", true).put("name", created.name))
                }
                session.uri == "/v1/rename" && session.method == Method.POST -> {
                    if (path.isBlank()) return bad("Cannot rename a storage root")
                    val name = q["name"] ?: return bad("name is required")
                    if (!validName(name)) return bad("Invalid name")
                    val item = resolve(root, path) ?: return jsonError(Response.Status.NOT_FOUND, "Item not found")
                    if (!item.renameTo(name)) return jsonError(Response.Status.INTERNAL_ERROR, "Rename failed")
                    json(JSONObject().put("ok", true).put("name", name))
                }
                session.uri == "/v1/item" && session.method == Method.DELETE -> {
                    if (path.isBlank()) return bad("Cannot delete a storage root")
                    val item = resolve(root, path) ?: return jsonError(Response.Status.NOT_FOUND, "Item not found")
                    if (!item.delete()) return jsonError(Response.Status.INTERNAL_ERROR, "Delete failed")
                    json(JSONObject().put("ok", true))
                }
                else -> jsonError(Response.Status.NOT_FOUND, "Unknown endpoint")
            }
        } catch (error: SecurityException) {
            jsonError(Response.Status.FORBIDDEN, "Android storage permission was revoked")
        } catch (error: Exception) {
            jsonError(Response.Status.INTERNAL_ERROR, error.message ?: "Storage operation failed")
        }
    }

    private fun rootFor(id: String): DocumentFile? {
        val key = when (id) { "usb1" -> "usb1"; "usb2" -> "usb2"; else -> return null }
        val value = prefs.getString(key, null) ?: return null
        return DocumentFile.fromTreeUri(context, Uri.parse(value))
    }
    private fun resolve(root: DocumentFile, path: String): DocumentFile? {
        var current = root
        if (path.isBlank()) return current
        for (part in path.split('/')) { if (!validName(part)) return null; current = current.findFile(part) ?: return null }
        return current
    }
    private fun validName(value: String): Boolean = value.isNotBlank() && value != "." && value != ".." &&
        !value.contains('/') && !value.contains('\\') && !value.any { it.code < 32 }
    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "flac" -> "audio/flac"
        "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }
    private fun volumes(): Response {
        val result = JSONArray()
        listOf("usb1", "usb2").forEach { id ->
            val root = rootFor(id)
            result.put(JSONObject().put("id", id).put("name", if (id == "usb1") "USB Storage 1" else "USB Storage 2")
                .put("available", root != null && root.exists()).put("writable", root?.canWrite() == true))
        }
        return json(JSONObject().put("items", result))
    }
    private fun bad(message: String) = jsonError(Response.Status.BAD_REQUEST, message)
    private fun jsonError(status: Response.Status, message: String) = json(JSONObject().put("detail", message), status)
    private fun json(value: JSONObject, status: Response.Status = Response.Status.OK) = DisconnectSafeResponse.fixedLength(status, "application/json; charset=utf-8", value.toString())
    private fun text(status: Response.Status, value: String) = DisconnectSafeResponse.fixedLength(status, "text/plain; charset=utf-8", value)
}
