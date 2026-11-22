package io.github.augztn.pocketlink.ssdbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class IndexedFile(
    val volume: String,
    val path: String,
    val name: String,
    val type: String,
    val size: Long,
    val modified: Long,
    val mime: String,
    val category: String?,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val hasLyrics: Boolean = false,
)

internal data class IndexStamp(val size: Long, val modified: Long, val category: String? = null)

internal class MediaIndexDatabase(context: Context) :
    SQLiteOpenHelper(context, "pocketlink_index.db", null, 2) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE files (
                volume TEXT NOT NULL, path TEXT NOT NULL, name TEXT NOT NULL,
                type TEXT NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL,
                mime TEXT NOT NULL, scan_id TEXT NOT NULL,
                PRIMARY KEY(volume, path)
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE media (
                volume TEXT NOT NULL, path TEXT NOT NULL, name TEXT NOT NULL,
                category TEXT NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL,
                mime TEXT NOT NULL, scan_id TEXT NOT NULL,
                title TEXT, artist TEXT, album TEXT,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                sample_rate INTEGER NOT NULL DEFAULT 0,
                bit_depth INTEGER NOT NULL DEFAULT 0,
                has_lyrics INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(volume, path)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX files_name_idx ON files(name COLLATE NOCASE)")
        db.execSQL("CREATE INDEX files_modified_idx ON files(modified DESC)")
        db.execSQL("CREATE INDEX media_category_name_idx ON media(category, name COLLATE NOCASE)")
        db.execSQL("CREATE INDEX media_modified_idx ON media(category, modified DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            listOf(
                "ALTER TABLE media ADD COLUMN title TEXT",
                "ALTER TABLE media ADD COLUMN artist TEXT",
                "ALTER TABLE media ADD COLUMN album TEXT",
                "ALTER TABLE media ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE media ADD COLUMN sample_rate INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE media ADD COLUMN bit_depth INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE media ADD COLUMN has_lyrics INTEGER NOT NULL DEFAULT 0",
                "UPDATE media SET modified = 0 WHERE category = 'music'",
            ).forEach { sql -> runCatching { db.execSQL(sql) } }
        }
    }

    fun upsertFiles(batch: List<IndexedFile>, scanId: String) {
        if (batch.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val statement = db.compileStatement("""
                INSERT OR REPLACE INTO files(volume,path,name,type,size,modified,mime,scan_id)
                VALUES(?,?,?,?,?,?,?,?)
            """.trimIndent())
            try {
                batch.forEach { item ->
                    statement.clearBindings()
                    statement.bindString(1, item.volume)
                    statement.bindString(2, item.path)
                    statement.bindString(3, item.name)
                    statement.bindString(4, item.type)
                    statement.bindLong(5, item.size)
                    statement.bindLong(6, item.modified)
                    statement.bindString(7, item.mime)
                    statement.bindString(8, scanId)
                    statement.executeInsert()
                }
            } finally { statement.close() }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun upsertMedia(batch: List<IndexedFile>, scanId: String) {
        val mediaItems = batch.filter { it.category != null && it.type == "file" }
        if (mediaItems.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val statement = db.compileStatement("""
                INSERT OR REPLACE INTO media(
                    volume,path,name,category,size,modified,mime,scan_id,
                    title,artist,album,duration_ms,sample_rate,bit_depth,has_lyrics
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent())
            try {
                mediaItems.forEach { item ->
                    statement.clearBindings()
                    statement.bindString(1, item.volume)
                    statement.bindString(2, item.path)
                    statement.bindString(3, item.name)
                    statement.bindString(4, item.category!!)
                    statement.bindLong(5, item.size)
                    statement.bindLong(6, item.modified)
                    statement.bindString(7, item.mime)
                    statement.bindString(8, scanId)
                    if (item.title.isNullOrBlank()) statement.bindNull(9) else statement.bindString(9, item.title)
                    if (item.artist.isNullOrBlank()) statement.bindNull(10) else statement.bindString(10, item.artist)
                    if (item.album.isNullOrBlank()) statement.bindNull(11) else statement.bindString(11, item.album)
                    statement.bindLong(12, item.durationMs)
                    statement.bindLong(13, item.sampleRateHz.toLong())
                    statement.bindLong(14, item.bitDepth.toLong())
                    statement.bindLong(15, if (item.hasLyrics) 1L else 0L)
                    statement.executeInsert()
                }
            } finally { statement.close() }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun finishFilesSource(volume: String, scanId: String) {
        writableDatabase.delete("files", "volume=? AND scan_id<>?", arrayOf(volume, scanId))
    }

    fun finishMediaSource(volume: String, scanId: String) {
        writableDatabase.delete("media", "volume=? AND scan_id<>?", arrayOf(volume, scanId))
    }

    fun fileSnapshot(volume: String): Map<String, IndexStamp> {
        val result = mutableMapOf<String, IndexStamp>()
        readableDatabase.query("files", arrayOf("path", "size", "modified"),
            "volume=?", arrayOf(volume), null, null, null).use { cursor ->
            while (cursor.moveToNext()) result[cursor.getString(0)] =
                IndexStamp(cursor.getLong(1), cursor.getLong(2))
        }
        return result
    }

    fun mediaSnapshot(volume: String): Map<String, IndexStamp> {
        val result = mutableMapOf<String, IndexStamp>()
        readableDatabase.query("media", arrayOf("path", "size", "modified", "category"),
            "volume=?", arrayOf(volume), null, null, null).use { cursor ->
            while (cursor.moveToNext()) result[cursor.getString(0)] =
                IndexStamp(cursor.getLong(1), cursor.getLong(2), cursor.getString(3))
        }
        return result
    }

    fun markFilesSeen(volume: String, paths: List<String>, scanId: String) =
        markSeen("files", volume, paths, scanId)

    fun markMediaSeen(volume: String, paths: List<String>, scanId: String) =
        markSeen("media", volume, paths, scanId)

    private fun markSeen(table: String, volume: String, paths: List<String>, scanId: String) {
        if (paths.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val statement = db.compileStatement("UPDATE $table SET scan_id=? WHERE volume=? AND path=?")
            try {
                paths.forEach { path ->
                    statement.clearBindings()
                    statement.bindString(1, scanId)
                    statement.bindString(2, volume)
                    statement.bindString(3, path)
                    statement.executeUpdateDelete()
                }
            } finally { statement.close() }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun queryMedia(category: String, query: String, limit: Int, offset: Int): List<IndexedFile> {
        val rows = mutableListOf<IndexedFile>()
        val where = if (query.isBlank()) {
            "category=?"
        } else {
            "category=? AND (name LIKE ? ESCAPE '\\' OR path LIKE ? ESCAPE '\\' OR title LIKE ? ESCAPE '\\' OR artist LIKE ? ESCAPE '\\' OR album LIKE ? ESCAPE '\\')"
        }
        val args = if (query.isBlank()) arrayOf(category) else {
            val escaped = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            arrayOf(category, escaped, escaped, escaped, escaped, escaped)
        }
        readableDatabase.query(
            "media",
            arrayOf("volume", "path", "name", "category", "size", "modified", "mime", "title", "artist", "album", "duration_ms", "sample_rate", "bit_depth", "has_lyrics"),
            where, args, null, null, "name COLLATE NOCASE ASC", "$offset,$limit"
        ).use { cursor ->
            while (cursor.moveToNext()) rows += IndexedFile(
                volume = cursor.getString(0),
                path = cursor.getString(1),
                name = cursor.getString(2),
                type = "file",
                size = cursor.getLong(4),
                modified = cursor.getLong(5),
                mime = cursor.getString(6),
                category = cursor.getString(3),
                title = cursor.getString(7)?.takeIf { it.isNotBlank() },
                artist = cursor.getString(8)?.takeIf { it.isNotBlank() },
                album = cursor.getString(9)?.takeIf { it.isNotBlank() },
                durationMs = cursor.getLong(10),
                sampleRateHz = cursor.getInt(11),
                bitDepth = cursor.getInt(12),
                hasLyrics = cursor.getInt(13) != 0,
            )
        }
        return rows
    }

    fun searchFiles(query: String, limit: Int, offset: Int): List<IndexedFile> {
        val rows = mutableListOf<IndexedFile>()
        val where = if (query.isBlank()) null else "name LIKE ? ESCAPE '\\' OR path LIKE ? ESCAPE '\\'"
        val escaped = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        readableDatabase.query(
            "files", arrayOf("volume","path","name","type","size","modified","mime"),
            where, if (where == null) null else arrayOf(escaped, escaped),
            null, null, "name COLLATE NOCASE ASC", "$offset,$limit"
        ).use { cursor ->
            while (cursor.moveToNext()) rows += IndexedFile(
                cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                cursor.getLong(4), cursor.getLong(5), cursor.getString(6), null
            )
        }
        return rows
    }

    fun counts(): Map<String, Int> {
        val db = readableDatabase
        fun count(table: String): Int = db.rawQuery("SELECT COUNT(*) FROM $table", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        fun countCategory(category: String): Int =
            db.rawQuery("SELECT COUNT(*) FROM media WHERE category=?", arrayOf(category)).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        return mapOf(
            "files" to count("files"),
            "media" to count("media"),
            "photo" to countCategory("photo"),
            "video" to countCategory("video"),
            "music" to countCategory("music")
        )
    }
}

internal class MediaIndexScanner(
    context: Context,
    private val scopedStorage: ScopedStorageRegistry,
    private val externalRoots: () -> List<Pair<String, DocumentFile>>
) {
    private val appContext = context.applicationContext
    private val database = MediaIndexDatabase(appContext)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PocketLink-MediaIndex").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)
    private val dirty = AtomicBoolean(false)
    @Volatile private var started = false
    @Volatile private var scanned = 0
    @Volatile private var skipped = 0
    @Volatile private var state = "idle"
    @Volatile private var lastError: String? = null
    @Volatile private var lastCompletedAt: Long = 0L
    @Volatile private var lastScanStartedAt: Long = 0L
    @Volatile private var currentSource: String? = null

    fun markDirty() {
        dirty.set(true)
    }

    fun ensureStarted() {
        val now = System.currentTimeMillis()
        if (!started) {
            requestScan(force = false)
        } else if (!running.get() &&
            (dirty.get() || now - lastScanStartedAt >= RESCAN_INTERVAL_MS)) {
            // Discover changes made by PocketLink immediately, and external
            // changes periodically. Unchanged rows are not rewritten.
            requestScan(force = true)
        }
    }

    @Synchronized
    fun requestScan(force: Boolean) {
        if (running.get()) {
            // A manual refresh is idempotent while a scan is active. Mutations
            // set dirty separately and still schedule one follow-up pass.
            return
        }
        if (started && !force && state == "complete") return
        started = true
        lastScanStartedAt = System.currentTimeMillis()
        dirty.set(false)
        running.set(true)
        state = "scanning"
        scanned = 0
        skipped = 0
        lastError = null
        executor.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                val sources = mutableListOf<Pair<String, Any>>()
                sources += "internal" to Unit
                externalRoots().forEach { (id, root) -> sources += id to root }
                sources.forEach { (id, root) ->
                    currentSource = if (id == "internal" && !scopedStorage.hasFullFilesAccess()) {
                        "internal (MT scoped)"
                    } else id
                    try {
                        scanSource(id, root)
                    } catch (error: Exception) {
                        skipped++
                        lastError = error.message ?: "One storage source could not be indexed"
                    } finally {
                        currentSource = null
                    }
                }
                if (lastError == null && skipped > 0) lastError = "Some files or folders could not be indexed."
                state = if (lastError == null && skipped == 0) "complete" else "partial"
                lastCompletedAt = System.currentTimeMillis()
            } catch (error: Exception) {
                state = "failed"
                lastError = error.message ?: "Index scan failed"
            } finally {
                val scanAgain = synchronized(this) {
                    running.set(false)
                    dirty.getAndSet(false)
                }
                if (scanAgain) requestScan(force = true)
            }
        }
    }

    private fun scanSource(volume: String, root: Any) {
        val scanId = UUID.randomUUID().toString()
        val fileSnapshot = database.fileSnapshot(volume)
        val mediaSnapshot = database.mediaSnapshot(volume)
        val batch = ArrayList<IndexedFile>(200)
        val unchangedFiles = ArrayList<String>(200)
        val unchangedMedia = ArrayList<String>(200)
        var complete = true
        var filesHealthy = true
        var mediaHealthy = true
        fun flush() {
            val snapshot = batch.toList()
            if (snapshot.isNotEmpty()) {
                try { database.upsertFiles(snapshot, scanId) } catch (error: Exception) {
                    filesHealthy = false
                    skipped++
                    lastError = error.message ?: "File index update failed"
                }
                try { database.upsertMedia(snapshot, scanId) } catch (error: Exception) {
                    mediaHealthy = false
                    skipped++
                    lastError = error.message ?: "Media index update failed"
                }
            }
            try { database.markFilesSeen(volume, unchangedFiles.toList(), scanId) } catch (error: Exception) {
                filesHealthy = false
                skipped++
                lastError = error.message ?: "File index update failed"
            }
            try { database.markMediaSeen(volume, unchangedMedia.toList(), scanId) } catch (error: Exception) {
                mediaHealthy = false
                skipped++
                lastError = error.message ?: "Media index update failed"
            }
            batch.clear()
            unchangedFiles.clear()
            unchangedMedia.clear()
        }
        fun add(
            path: String,
            name: String,
            directory: Boolean,
            size: Long,
            modified: Long,
            mime: String,
            audioMetaProvider: (() -> ExtractedAudioMetadata)? = null,
        ) {
            val safeSize = size.coerceAtLeast(0L)
            val safeModified = modified.coerceAtLeast(0L)
            val category = if (directory) null else categoryFor(name)
            val fileUnchanged = safeModified > 0L && fileSnapshot[path] == IndexStamp(safeSize, safeModified)
            val mediaUnchanged = if (safeModified <= 0L) {
                false
            } else if (category == null) {
                mediaSnapshot[path] == null
            } else {
                mediaSnapshot[path] == IndexStamp(safeSize, safeModified, category)
            }
            scanned++
            if (fileUnchanged && mediaUnchanged) {
                unchangedFiles += path
                if (category != null) unchangedMedia += path
            } else {
                val meta = if (category == "music" && !directory && audioMetaProvider != null) {
                    runCatching { audioMetaProvider() }.getOrNull()
                } else null
                batch += IndexedFile(
                    volume = volume,
                    path = path,
                    name = name,
                    type = if (directory) "directory" else "file",
                    size = safeSize,
                    modified = safeModified,
                    mime = mime,
                    category = category,
                    title = meta?.title?.takeIf { it.isNotBlank() },
                    artist = meta?.artist?.takeIf { it.isNotBlank() },
                    album = meta?.album?.takeIf { it.isNotBlank() },
                    durationMs = meta?.durationMs ?: 0L,
                    sampleRateHz = meta?.sampleRateHz ?: 0,
                    bitDepth = meta?.bitDepth ?: 0,
                    hasLyrics = meta?.hasEmbeddedLyrics == true,
                )
            }
            if (batch.size >= 200 || unchangedFiles.size >= 200) flush()
        }

        val seenPaths = HashSet<String>(512)
        val seenNames = HashSet<String>(512)
        fun walkFileTree(base: File, pathPrefix: String, maxDepth: Int = MAX_DEPTH) {
            val stack = ArrayDeque<Pair<File, Int>>()
            stack.add(base to 0)
            while (stack.isNotEmpty()) {
                val (directory, depth) = stack.removeLast()
                waitForPlaybackToPause()
                val children = try { directory.listFiles() } catch (_: Exception) { null }
                if (children == null) {
                    complete = false
                    skipped++
                    continue
                }
                children.forEach { child ->
                    if (child.name.startsWith(".")) return@forEach
                    val canonical = try { child.canonicalFile } catch (_: Exception) { null }
                    if (canonical == null || !(canonical.path == base.path ||
                            canonical.path.startsWith(base.path + File.separator))) {
                        complete = false
                        skipped++
                        return@forEach
                    }
                    val sub = canonical.relativeTo(base).invariantSeparatorsPath
                    val relative = if (pathPrefix.isEmpty()) sub else "$pathPrefix/$sub"
                    if (seenPaths.add(relative)) {
                        if (child.isFile) seenNames.add(child.name.lowercase())
                        add(
                            path = relative,
                            name = child.name,
                            directory = child.isDirectory,
                            size = if (child.isFile) child.length() else 0L,
                            modified = child.lastModified(),
                            mime = mimeForName(child.name),
                            audioMetaProvider = if (child.isFile) {
                                { AudioMetadataExtractor.extractFromFile(child, includeLyrics = true) }
                            } else null,
                        )
                    }
                    if (child.isDirectory) {
                        if (depth < maxDepth) stack.add(child to depth + 1)
                        else { complete = false; skipped++ }
                    }
                }
            }
        }

        fun walkDocumentTree(docRoot: DocumentFile, pathPrefix: String, maxDepth: Int = MAX_DEPTH) {
            val stack = ArrayDeque<Pair<Pair<DocumentFile, String>, Int>>()
            stack.add((docRoot to pathPrefix) to 0)
            while (stack.isNotEmpty()) {
                val (entry, depth) = stack.removeLast()
                val (directory, parentPath) = entry
                waitForPlaybackToPause()
                val children = try { directory.listFiles() } catch (_: Exception) { null }
                if (children == null) {
                    complete = false
                    skipped++
                    continue
                }
                children.forEach { child ->
                    val name = child.name ?: return@forEach
                    if (name.startsWith(".")) return@forEach
                    val relative = if (parentPath.isEmpty()) name else "$parentPath/$name"
                    if (seenPaths.add(relative)) {
                        if (child.isFile) seenNames.add(name.lowercase())
                        val fileLen = if (child.isFile) child.length() else 0L
                        add(
                            path = relative,
                            name = name,
                            directory = child.isDirectory,
                            size = fileLen,
                            modified = child.lastModified(),
                            mime = child.type ?: mimeForName(name),
                            audioMetaProvider = if (child.isFile) {
                                { AudioMetadataExtractor.extractFromUri(appContext, child.uri, name, fileLen, includeLyrics = true) }
                            } else null,
                        )
                    }
                    if (child.isDirectory) {
                        if (depth < maxDepth) stack.add((child to relative) to depth + 1)
                        else { complete = false; skipped++ }
                    }
                }
            }
        }

        when {
            volume == "internal" && scopedStorage.hasFullFilesAccess() -> {
                walkFileTree(scopedStorage.externalStorageDir(), "")
            }
            volume == "internal" -> {
                // MT-Manager-style scoped scan:
                // 1) Mounted internal SAF trees (e.g. Music, Movies, Pictures, DCIM, Download, Documents, Android/data)
                scopedStorage.activeSafMounts().forEach { mount ->
                    runCatching { walkDocumentTree(mount.doc, mount.mountPath) }
                        .onFailure { skipped++ }
                }
                // 2) Directly readable public folders if OS/OEM kernel permits
                scopedStorage.directReadablePublicDirs().forEach { (folder, dir) ->
                    if (seenPaths.none { it == folder || it.startsWith("$folder/") }) {
                        walkFileTree(dir, folder)
                    }
                }
                // 3) Built-in PocketLinkStorage sandbox (sample media & uploads)
                val sandbox = scopedStorage.sandboxRootDir()
                if (sandbox.exists() && sandbox.isDirectory) {
                    walkFileTree(sandbox, "")
                }
                // 4) Zero-permission MediaStore fallback for unique accessible media items
                scopedStorage.queryReadableMediaStore().forEach { ms ->
                    val alreadyIndexed = ms.realRelativePath?.let { it in seenPaths } == true ||
                        (ms.size > 0L && ms.name.lowercase() in seenNames && ms.realRelativePath?.contains('/') == true)
                    if (!alreadyIndexed && seenPaths.add(ms.virtualPath)) {
                        add(
                            path = ms.virtualPath,
                            name = ms.name,
                            directory = false,
                            size = ms.size,
                            modified = ms.modifiedMs,
                            mime = ms.mime,
                            audioMetaProvider = {
                                AudioMetadataExtractor.extractFromUri(appContext, ms.uri, ms.name, ms.size, includeLyrics = true)
                            },
                        )
                    }
                }
            }
            root is File -> walkFileTree(root.canonicalFile, "")
            root is DocumentFile -> walkDocumentTree(root, "")
        }
        flush()
        // Each index is committed and pruned independently. A media-index
        // failure must not invalidate the general file index, or vice versa.
        if (complete && filesHealthy) database.finishFilesSource(volume, scanId)
        if (complete && mediaHealthy) database.finishMediaSource(volume, scanId)
    }

    /**
     * Storage providers can perform blocking I/O. Yield between directory reads
     * while audio is playing so indexing does not compete with active playback.
     */
    private fun waitForPlaybackToPause() {
        while (MediaPlaybackBridge.isPlaying) {
            try {
                Thread.sleep(1_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    fun status(): org.json.JSONObject {
        val counts = database.counts()
        return org.json.JSONObject()
            .put("state", state)
            .put("scanned", scanned)
            .put("skipped", skipped)
            .put("last_completed_at", lastCompletedAt)
            .put("last_scan_started_at", lastScanStartedAt)
            .put("elapsed_ms", if (state == "scanning") System.currentTimeMillis() - lastScanStartedAt else (lastCompletedAt - lastScanStartedAt).coerceAtLeast(0L))
            .put("current_source", currentSource)
            .put("last_error", lastError)
            .put("storage_mode", scopedStorage.storageModeTag())
            .put("file_count", counts["files"] ?: 0)
            .put("media_count", counts["media"] ?: 0)
            .put("photo_count", counts["photo"] ?: 0)
            .put("video_count", counts["video"] ?: 0)
            .put("music_count", counts["music"] ?: 0)
    }

    fun media(category: String, query: String, limit: Int, offset: Int) =
        database.queryMedia(category, query, limit, offset)

    fun files(query: String, limit: Int, offset: Int) =
        database.searchFiles(query, limit, offset)

    fun close() {
        runCatching { executor.shutdownNow() }
        runCatching { database.close() }
    }

    companion object {
        private const val RESCAN_INTERVAL_MS = 10 * 60 * 1000L
        private const val MAX_DEPTH = 64
        private val AUDIO = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "aiff", "aif", "alac", "wv", "ape", "dsf", "dff")
        private val VIDEO = setOf("mp4","m4v","mov","mkv","webm","avi","wmv","mpeg","mpg","3gp","ts","m2ts","mts","flv","f4v","3g2","m2v","ogv","vob","rmvb")
        private val PHOTO = setOf("jpg","jpeg","png","webp","gif","bmp","heic","heif","avif")

        private fun categoryFor(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
            in AUDIO -> "music"
            in VIDEO -> "video"
            in PHOTO -> "photo"
            else -> null
        }

        private fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"; "m4a", "alac" -> "audio/mp4"; "aac" -> "audio/aac"; "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"; "wav" -> "audio/wav"; "aiff", "aif" -> "audio/aiff"
            "wv" -> "audio/x-wavpack"; "ape" -> "audio/x-ape"; "dsf", "dff" -> "audio/dsd"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"; "jpg","jpeg" -> "image/jpeg"; "png" -> "image/png"
            "webp" -> "image/webp"; "gif" -> "image/gif"; "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }
}
