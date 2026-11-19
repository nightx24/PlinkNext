package io.github.augztn.pocketlink.ssdbridge

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.security.SecureRandom
import java.util.Locale

data class ScopedFolderStatus(
    val key: String,
    val mountPath: String,
    val label: String,
    val mode: String, // "full" | "direct" | "saf" | "locked"
    val writable: Boolean,
    val isPreset: Boolean = true,
)

internal data class ScopedSafMount(
    val key: String,
    val mountPath: String, // Normalized relative path under internal root; "" means whole internal root
    val label: String,
    val uri: Uri,
    val doc: DocumentFile,
)

internal data class MediaStoreEntry(
    val virtualPath: String, // e.g. "MediaStore/Music/song.mp3"
    val folder: String,
    val name: String,
    val size: Long,
    val modifiedMs: Long,
    val mime: String,
    val category: String?,
    val uri: Uri,
    val realRelativePath: String?,
)

internal data class UnifiedListEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedMs: Long,
    val createdMs: Long? = null,
)

internal sealed class InternalReadable {
    data class LocalFile(val file: File) : InternalReadable()
    data class SafDoc(val doc: DocumentFile, val name: String) : InternalReadable()
    data class ContentItem(val uri: Uri, val name: String, val size: Long, val mime: String) : InternalReadable()
}

internal sealed class InternalWritableDir {
    data class LocalDir(val dir: File) : InternalWritableDir()
    data class SafDir(val dir: DocumentFile) : InternalWritableDir()
}

internal sealed class InternalWritableTarget {
    data class LocalTarget(val file: File) : InternalWritableTarget()
    data class SafTarget(val doc: DocumentFile) : InternalWritableTarget()
}

/**
 * Provides MT-Manager-style layered internal storage scanning and access
 * on Android / MTK devices even when MANAGE_EXTERNAL_STORAGE (All Files Access)
 * is not granted or restricted:
 * 1. Direct filesystem access (when full permission or OEM FUSE folder readability allows).
 * 2. Scoped SAF folder mounts (`ACTION_OPEN_DOCUMENT_TREE` with pre-targeted `EXTRA_INITIAL_URI`).
 * 3. Zero-permission MediaStore readable items fallback.
 * 4. Built-in `PocketLinkStorage` sandbox fallback.
 */
internal class ScopedStorageRegistry(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("ssd-bridge", Context.MODE_PRIVATE)

    fun hasFullFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    fun externalStorageDir(): File =
        runCatching { Environment.getExternalStorageDirectory().canonicalFile }
            .getOrElse { sandboxRootDir() }

    fun sandboxRootDir(): File =
        File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "PocketLinkStorage")
            .apply { mkdirs() }
            .canonicalFile

    fun createScopedFolderPickerIntent(mountPath: String?): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val docId = if (mountPath.isNullOrBlank()) {
                    "primary:"
                } else {
                    "primary:${normalizePath(mountPath)}"
                }
                val initialUri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
        }
        return intent
    }

    fun persistScopedFolderMount(requestedMountPath: String?, uri: Uri, flags: Int): Result<String> = runCatching {
        val takeFlags = (flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            .let { if (it != 0) it else Intent.FLAG_GRANT_READ_URI_PERMISSION }
        runCatching { appContext.contentResolver.takePersistableUriPermission(uri, takeFlags) }
            .onFailure {
                appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

        val doc = DocumentFile.fromTreeUri(appContext, uri)
            ?: error("Selected folder could not be accessed")
        val derivedMount = deriveMountPath(uri, requestedMountPath, doc.name)
        val label = derivedMount.ifBlank { "Internal Storage (SAF)" }

        val existingKeys = activeMountKeys().filter { key ->
            normalizePath(prefs.getString(KEY_MOUNT_PREFIX + key, "").orEmpty()) == derivedMount
        }
        val key = existingKeys.firstOrNull() ?: generateKey()
        prefs.edit().apply {
            existingKeys.drop(1).forEach { dup ->
                remove(KEY_URI_PREFIX + dup)
                remove(KEY_MOUNT_PREFIX + dup)
                remove(KEY_LABEL_PREFIX + dup)
            }
            putString(KEY_URI_PREFIX + key, uri.toString())
            putString(KEY_MOUNT_PREFIX + key, derivedMount)
            putString(KEY_LABEL_PREFIX + key, label)
        }.apply()

        label
    }

    fun removeScopedFolderMount(key: String) {
        prefs.edit()
            .remove(KEY_URI_PREFIX + key)
            .remove(KEY_MOUNT_PREFIX + key)
            .remove(KEY_LABEL_PREFIX + key)
            .apply()
    }

    fun activeSafMounts(): List<ScopedSafMount> {
        return activeMountKeys().mapNotNull { key ->
            val rawUri = prefs.getString(KEY_URI_PREFIX + key, null) ?: return@mapNotNull null
            val uri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return@mapNotNull null
            val doc = runCatching { DocumentFile.fromTreeUri(appContext, uri) }.getOrNull()
                ?.takeIf { it.exists() && it.isDirectory && it.canRead() }
                ?: return@mapNotNull null
            val mount = normalizePath(prefs.getString(KEY_MOUNT_PREFIX + key, "").orEmpty())
            val label = prefs.getString(KEY_LABEL_PREFIX + key, null)?.takeIf { it.isNotBlank() }
                ?: mount.ifBlank { doc.name ?: "Internal Root" }
            ScopedSafMount(
                key = key,
                mountPath = mount,
                label = label,
                uri = uri,
                doc = doc,
            )
        }
    }

    fun getScopedFolderStatuses(): List<ScopedFolderStatus> {
        val full = hasFullFilesAccess()
        val extRoot = externalStorageDir()
        val safMounts = activeSafMounts()
        val safByMount = safMounts.associateBy { it.mountPath }
        val result = mutableListOf<ScopedFolderStatus>()

        STANDARD_FOLDERS.forEach { folder ->
            val saf = safByMount[folder]
            val directDir = File(extRoot, folder)
            val directReadable = full || isDirectReadableDir(directDir)
            val mode = when {
                full -> "full"
                saf != null -> "saf"
                directReadable -> "direct"
                else -> "locked"
            }
            val writable = when (mode) {
                "full", "direct" -> directDir.canWrite()
                "saf" -> saf?.doc?.canWrite() == true
                else -> false
            }
            result += ScopedFolderStatus(
                key = saf?.key ?: "preset:$folder",
                mountPath = folder,
                label = folder,
                mode = mode,
                writable = writable,
                isPreset = true,
            )
        }

        safMounts
            .filter { it.mountPath !in STANDARD_FOLDERS }
            .forEach { custom ->
                result += ScopedFolderStatus(
                    key = custom.key,
                    mountPath = custom.mountPath,
                    label = custom.label,
                    mode = "saf",
                    writable = custom.doc.canWrite(),
                    isPreset = false,
                )
            }

        return result
    }

    fun storageModeTag(): String = when {
        hasFullFilesAccess() -> "full"
        activeSafMounts().isNotEmpty() || directReadablePublicDirs().isNotEmpty() -> "mt_scoped"
        else -> "sandbox_media"
    }

    fun directReadablePublicDirs(): List<Pair<String, File>> {
        if (hasFullFilesAccess()) return emptyList()
        val extRoot = externalStorageDir()
        return DIRECT_PROBE_FOLDERS.mapNotNull { folder ->
            val dir = File(extRoot, folder)
            if (isDirectReadableDir(dir)) folder to dir.canonicalFile else null
        }
    }

    fun listDirectory(relative: String): List<UnifiedListEntry>? {
        val clean = normalizePath(relative)
        if (hasFullFilesAccess()) {
            val target = resolveUnderBase(externalStorageDir(), clean) ?: return null
            if (!target.isDirectory) return null
            return (target.listFiles() ?: emptyArray())
                .asSequence()
                .filter { !it.name.startsWith(".") }
                .map { it.toUnifiedEntry() }
                .sortedWith(ENTRY_COMPARATOR)
                .toList()
        }

        val safMounts = activeSafMounts()
        val sandboxRoot = sandboxRootDir()
        val extRoot = externalStorageDir()

        if (clean.isEmpty()) {
            val byName = LinkedHashMap<String, UnifiedListEntry>()
            fun putIfAbsent(entry: UnifiedListEntry) {
                val key = entry.name.lowercase(Locale.ROOT)
                if (!byName.containsKey(key)) byName[key] = entry
            }

            // 1. Direct root children if readable on this OEM/MTK kernel
            if (isDirectReadableDir(extRoot)) {
                (extRoot.listFiles() ?: emptyArray())
                    .filter { !it.name.startsWith(".") }
                    .forEach { putIfAbsent(it.toUnifiedEntry()) }
            }

            // 2. Direct readable public directories
            directReadablePublicDirs().forEach { (folder, dir) ->
                putIfAbsent(UnifiedListEntry(folder, true, 0L, dir.lastModified()))
            }

            // 3. SAF mount root folders & top-level segments
            safMounts.forEach { mount ->
                if (mount.mountPath.isEmpty()) {
                    mount.doc.listFiles()
                        .filter { !it.name.orEmpty().startsWith(".") }
                        .forEach { child ->
                            val n = child.name ?: return@forEach
                            putIfAbsent(child.toUnifiedEntry(n))
                        }
                } else {
                    val topSegment = mount.mountPath.substringBefore('/')
                    putIfAbsent(UnifiedListEntry(topSegment, true, 0L, mount.doc.lastModified()))
                }
            }

            // 4. Built-in sandbox folders & files (Sample Media / PocketLink uploads)
            (sandboxRoot.listFiles() ?: emptyArray())
                .filter { !it.name.startsWith(".") }
                .forEach { putIfAbsent(it.toUnifiedEntry()) }

            // 5. Zero-permission MediaStore virtual folder when readable items exist
            if (queryReadableMediaStore(limit = 1).isNotEmpty()) {
                putIfAbsent(UnifiedListEntry(MEDIA_STORE_FOLDER, true, 0L, System.currentTimeMillis()))
            }

            return byName.values.sortedWith(ENTRY_COMPARATOR)
        }

        // Virtual "MediaStore" browse path
        if (clean == MEDIA_STORE_FOLDER || clean.startsWith("$MEDIA_STORE_FOLDER/")) {
            return listMediaStoreVirtual(clean)
        }

        // Check SAF mount that covers this path directly
        val matchedSaf = bestSafMountFor(clean, safMounts)
        if (matchedSaf != null) {
            val subPath = relativeUnderMount(clean, matchedSaf.mountPath)
            val resolvedDoc = resolveDocumentPath(matchedSaf.doc, subPath)
            if (resolvedDoc != null && resolvedDoc.isDirectory) {
                val entries = resolvedDoc.listFiles()
                    .asSequence()
                    .filter { !it.name.orEmpty().startsWith(".") }
                    .mapNotNull { doc -> doc.name?.let { doc.toUnifiedEntry(it) } }
                    .toMutableList()
                // Merge sandbox entries for matching standard folders (e.g. Music, Pictures, Documents)
                val sandboxSub = resolveUnderBase(sandboxRoot, clean)
                if (sandboxSub != null && sandboxSub.isDirectory) {
                    val existing = entries.mapTo(HashSet()) { it.name.lowercase(Locale.ROOT) }
                    (sandboxSub.listFiles() ?: emptyArray())
                        .filter { !it.name.startsWith(".") && it.name.lowercase(Locale.ROOT) !in existing }
                        .forEach { entries += it.toUnifiedEntry() }
                }
                return entries.sortedWith(ENTRY_COMPARATOR)
            }
        }

        // Check Direct readable path under externalStorageDir
        val directTarget = resolveUnderBase(extRoot, clean)
        if (directTarget != null && isDirectReadableDir(directTarget)) {
            val entries = (directTarget.listFiles() ?: emptyArray())
                .asSequence()
                .filter { !it.name.startsWith(".") }
                .map { it.toUnifiedEntry() }
                .toMutableList()
            val sandboxSub = resolveUnderBase(sandboxRoot, clean)
            if (sandboxSub != null && sandboxSub.isDirectory) {
                val existing = entries.mapTo(HashSet()) { it.name.lowercase(Locale.ROOT) }
                (sandboxSub.listFiles() ?: emptyArray())
                    .filter { !it.name.startsWith(".") && it.name.lowercase(Locale.ROOT) !in existing }
                    .forEach { entries += it.toUnifiedEntry() }
            }
            return entries.sortedWith(ENTRY_COMPARATOR)
        }

        // Check virtual intermediate parent folders for nested SAF mounts (e.g. "Android" for "Android/data")
        val prefix = "$clean/"
        val nestedMounts = safMounts.filter { it.mountPath.startsWith(prefix) }
        if (nestedMounts.isNotEmpty()) {
            val byName = LinkedHashMap<String, UnifiedListEntry>()
            nestedMounts.forEach { mount ->
                val childSeg = mount.mountPath.removePrefix(prefix).substringBefore('/')
                if (childSeg.isNotBlank() && !byName.containsKey(childSeg.lowercase(Locale.ROOT))) {
                    byName[childSeg.lowercase(Locale.ROOT)] = UnifiedListEntry(
                        name = childSeg,
                        isDirectory = true,
                        size = 0L,
                        modifiedMs = mount.doc.lastModified(),
                    )
                }
            }
            val sandboxSub = resolveUnderBase(sandboxRoot, clean)
            if (sandboxSub != null && sandboxSub.isDirectory) {
                (sandboxSub.listFiles() ?: emptyArray())
                    .filter { !it.name.startsWith(".") }
                    .forEach { byName.putIfAbsent(it.name.lowercase(Locale.ROOT), it.toUnifiedEntry()) }
            }
            return byName.values.sortedWith(ENTRY_COMPARATOR)
        }

        // Sandbox fallback
        val sandboxTarget = resolveUnderBase(sandboxRoot, clean)
        if (sandboxTarget != null && sandboxTarget.isDirectory) {
            return (sandboxTarget.listFiles() ?: emptyArray())
                .asSequence()
                .filter { !it.name.startsWith(".") }
                .map { it.toUnifiedEntry() }
                .sortedWith(ENTRY_COMPARATOR)
                .toList()
        }

        return null
    }

    fun resolveReadable(relative: String): InternalReadable? {
        val clean = normalizePath(relative)
        if (clean.isEmpty()) return null
        if (hasFullFilesAccess()) {
            val target = resolveUnderBase(externalStorageDir(), clean)
            return if (target != null && isDirectReadableFile(target)) InternalReadable.LocalFile(target) else null
        }

        if (clean.startsWith("$MEDIA_STORE_FOLDER/")) {
            val item = queryReadableMediaStore(limit = MAX_MEDIASTORE_ITEMS)
                .firstOrNull { it.virtualPath == clean } ?: return null
            return InternalReadable.ContentItem(item.uri, item.name, item.size, item.mime)
        }

        val safMounts = activeSafMounts()
        val matchedSaf = bestSafMountFor(clean, safMounts)
        if (matchedSaf != null) {
            val subPath = relativeUnderMount(clean, matchedSaf.mountPath)
            val doc = resolveDocumentPath(matchedSaf.doc, subPath)
            if (doc != null && doc.isFile && doc.canRead()) {
                return InternalReadable.SafDoc(doc, doc.name ?: clean.substringAfterLast('/'))
            }
        }

        val directTarget = resolveUnderBase(externalStorageDir(), clean)
        if (directTarget != null && isDirectReadableFile(directTarget)) {
            return InternalReadable.LocalFile(directTarget)
        }

        val sandboxTarget = resolveUnderBase(sandboxRootDir(), clean)
        if (sandboxTarget != null && isDirectReadableFile(sandboxTarget)) {
            return InternalReadable.LocalFile(sandboxTarget)
        }

        return null
    }

    fun resolveWritableDir(relativeParent: String): InternalWritableDir? {
        val clean = normalizePath(relativeParent)
        if (hasFullFilesAccess()) {
            val target = resolveUnderBase(externalStorageDir(), clean) ?: return null
            return if (target.isDirectory && target.canWrite()) InternalWritableDir.LocalDir(target) else null
        }
        val safMounts = activeSafMounts()
        val matchedSaf = bestSafMountFor(clean, safMounts)
        if (matchedSaf != null) {
            val subPath = relativeUnderMount(clean, matchedSaf.mountPath)
            val doc = resolveDocumentPath(matchedSaf.doc, subPath)
            if (doc != null && doc.isDirectory && doc.canWrite()) {
                return InternalWritableDir.SafDir(doc)
            }
        }
        val directTarget = resolveUnderBase(externalStorageDir(), clean)
        if (directTarget != null && directTarget.isDirectory && directTarget.canWrite()) {
            return InternalWritableDir.LocalDir(directTarget)
        }
        val sandboxTarget = resolveUnderBase(sandboxRootDir(), clean)
        if (sandboxTarget != null) {
            if (!sandboxTarget.exists() && clean.isNotEmpty()) {
                sandboxTarget.mkdirs()
            }
            if (sandboxTarget.isDirectory && sandboxTarget.canWrite()) {
                return InternalWritableDir.LocalDir(sandboxTarget)
            }
        }
        return null
    }

    fun resolveWritableTarget(relative: String): InternalWritableTarget? {
        val clean = normalizePath(relative)
        if (clean.isEmpty()) return null
        if (hasFullFilesAccess()) {
            val target = resolveUnderBase(externalStorageDir(), clean) ?: return null
            if (target == externalStorageDir()) return null
            return if (target.exists()) InternalWritableTarget.LocalTarget(target) else null
        }
        val safMounts = activeSafMounts()
        val matchedSaf = bestSafMountFor(clean, safMounts)
        if (matchedSaf != null) {
            val subPath = relativeUnderMount(clean, matchedSaf.mountPath)
            if (subPath.isNotEmpty()) {
                val doc = resolveDocumentPath(matchedSaf.doc, subPath)
                if (doc != null && doc.exists()) return InternalWritableTarget.SafTarget(doc)
            }
        }
        val directTarget = resolveUnderBase(externalStorageDir(), clean)
        if (directTarget != null && directTarget.exists() && directTarget.parentFile?.canWrite() == true) {
            return InternalWritableTarget.LocalTarget(directTarget)
        }
        val sandboxTarget = resolveUnderBase(sandboxRootDir(), clean)
        if (sandboxTarget != null && sandboxTarget.exists()) {
            return InternalWritableTarget.LocalTarget(sandboxTarget)
        }
        return null
    }

    fun queryReadableMediaStore(limit: Int = MAX_MEDIASTORE_ITEMS): List<MediaStoreEntry> {
        val items = mutableListOf<MediaStoreEntry>()
        val queries: MutableList<Triple<Uri, String, String?>> = mutableListOf(
            Triple(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "Audio", "music"),
            Triple(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Video", "video"),
            Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Images", "photo"),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queries += Triple(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "Downloads", null)
        }

        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
        }.toTypedArray()

        for ((uriRoot, folder, category) in queries) {
            if (items.size >= limit) break
            runCatching {
                appContext.contentResolver.query(
                    uriRoot,
                    projection,
                    null,
                    null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val modCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    } else -1

                    while (cursor.moveToNext() && items.size < limit) {
                        val id = if (idCol >= 0) cursor.getLong(idCol) else continue
                        val name = (if (nameCol >= 0) cursor.getString(nameCol) else null)
                            ?.takeIf { it.isNotBlank() && !it.startsWith(".") } ?: continue
                        val itemUri = ContentUris.withAppendedId(uriRoot, id)
                        // Verify actual readability without broad storage permissions
                        val canOpen = runCatching {
                            appContext.contentResolver.openAssetFileDescriptor(itemUri, "r")?.use { true } ?: false
                        }.getOrDefault(false)
                        if (!canOpen) continue

                        val size = (if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L).coerceAtLeast(0L)
                        val modSec = if (modCol >= 0) cursor.getLong(modCol) else 0L
                        val modifiedMs = if (modSec > 0L) modSec * 1000L else System.currentTimeMillis()
                        val mime = (if (mimeCol >= 0) cursor.getString(mimeCol) else null)
                            ?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                        val relDir = if (relCol >= 0) cursor.getString(relCol)?.trim('/') else null
                        val relFull = if (!relDir.isNullOrBlank()) "$relDir/$name" else name

                        items += MediaStoreEntry(
                            virtualPath = "$MEDIA_STORE_FOLDER/$folder/${id}_$name",
                            folder = folder,
                            name = name,
                            size = size,
                            modifiedMs = modifiedMs,
                            mime = mime,
                            category = category,
                            uri = itemUri,
                            realRelativePath = relFull,
                        )
                    }
                }
            }
        }
        return items
    }

    private fun listMediaStoreVirtual(cleanPath: String): List<UnifiedListEntry>? {
        val entries = queryReadableMediaStore(MAX_MEDIASTORE_ITEMS)
        if (cleanPath == MEDIA_STORE_FOLDER) {
            val folders = entries.map { it.folder }.distinct().sorted()
            return folders.map { folderName ->
                UnifiedListEntry(folderName, true, 0L, System.currentTimeMillis())
            }
        }
        val folder = cleanPath.removePrefix("$MEDIA_STORE_FOLDER/")
        if ('/' in folder) return null
        return entries
            .filter { it.folder.equals(folder, ignoreCase = true) }
            .map { entry ->
                UnifiedListEntry(
                    name = entry.virtualPath.substringAfterLast('/'),
                    isDirectory = false,
                    size = entry.size,
                    modifiedMs = entry.modifiedMs,
                )
            }
            .sortedWith(ENTRY_COMPARATOR)
    }

    private fun bestSafMountFor(relative: String, mounts: List<ScopedSafMount>): ScopedSafMount? {
        return mounts
            .filter { m ->
                m.mountPath.isEmpty() ||
                    relative == m.mountPath ||
                    relative.startsWith("${m.mountPath}/")
            }
            .maxByOrNull { it.mountPath.length }
    }

    private fun relativeUnderMount(relative: String, mountPath: String): String {
        if (mountPath.isEmpty()) return relative
        if (relative == mountPath) return ""
        return relative.removePrefix("$mountPath/")
    }

    private fun resolveDocumentPath(root: DocumentFile, relative: String): DocumentFile? {
        var current = root
        if (relative.isBlank()) return current
        for (part in relative.split('/')) {
            if (!isSafeSegment(part)) return null
            current = current.findFile(part) ?: return null
        }
        return current
    }

    private fun resolveUnderBase(base: File, relative: String): File? {
        if (relative.isBlank()) return base
        val parts = relative.split('/')
        if (parts.any { !isSafeSegment(it) }) return null
        val target = runCatching { File(base, relative).canonicalFile }.getOrNull() ?: return null
        val prefix = base.path + File.separator
        return if (target.path == base.path || target.path.startsWith(prefix)) target else null
    }

    private fun deriveMountPath(uri: Uri, requestedMountPath: String?, fallbackName: String?): String {
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        if (!treeId.isNullOrBlank()) {
            if (treeId == "primary:") return ""
            if (treeId.startsWith("primary:")) {
                return normalizePath(treeId.removePrefix("primary:"))
            }
        }
        val req = normalizePath(requestedMountPath.orEmpty())
        if (req.isNotEmpty()) return req
        return normalizePath(fallbackName ?: "Folder")
    }

    private fun activeMountKeys(): List<String> =
        prefs.all.keys
            .filter { it.startsWith(KEY_URI_PREFIX) }
            .map { it.removePrefix(KEY_URI_PREFIX) }
            .sorted()

    private fun isDirectReadableDir(file: File): Boolean =
        runCatching { file.exists() && file.isDirectory && file.canRead() && file.listFiles() != null }.getOrDefault(false)

    private fun isDirectReadableFile(file: File): Boolean =
        runCatching { file.exists() && file.isFile && file.canRead() }.getOrDefault(false)

    private fun File.toUnifiedEntry(): UnifiedListEntry {
        var created: Long? = null
        if (isFile && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            created = runCatching {
                java.nio.file.Files.readAttributes(
                    toPath(),
                    java.nio.file.attribute.BasicFileAttributes::class.java
                ).creationTime().toMillis().takeIf { it > 0L }
            }.getOrNull()
        }
        return UnifiedListEntry(
            name = name,
            isDirectory = isDirectory,
            size = if (isFile) length() else 0L,
            modifiedMs = lastModified(),
            createdMs = created,
        )
    }

    private fun DocumentFile.toUnifiedEntry(resolvedName: String): UnifiedListEntry =
        UnifiedListEntry(
            name = resolvedName,
            isDirectory = isDirectory,
            size = if (isFile) length() else 0L,
            modifiedMs = lastModified(),
        )

    private fun generateKey(): String {
        val bytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
        return "mnt-" + bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MEDIA_STORE_FOLDER = "MediaStore"
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val KEY_URI_PREFIX = "internal_saf_uri_"
        private const val KEY_MOUNT_PREFIX = "internal_saf_mount_"
        private const val KEY_LABEL_PREFIX = "internal_saf_label_"
        private const val MAX_MEDIASTORE_ITEMS = 1200

        val STANDARD_FOLDERS = listOf(
            "Download",
            "Documents",
            "Music",
            "Movies",
            "Pictures",
            "DCIM",
            "Recordings",
            "Podcasts",
            "Audiobooks",
            "Android/data",
        )

        private val DIRECT_PROBE_FOLDERS = listOf(
            "Download",
            "Documents",
            "Music",
            "Movies",
            "Pictures",
            "DCIM",
            "Recordings",
            "Podcasts",
            "Audiobooks",
            "Ringtones",
        )

        private val ENTRY_COMPARATOR = compareBy<UnifiedListEntry>(
            { !it.isDirectory },
            { it.name.lowercase(Locale.ROOT) }
        )

        fun normalizePath(raw: String): String =
            raw.replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/")

        private fun isSafeSegment(segment: String): Boolean =
            segment.isNotBlank() && segment != "." && segment != ".." &&
                !segment.startsWith(".") && !segment.contains('/') &&
                !segment.contains('\\') && !segment.any { it.code < 32 }
    }
}
