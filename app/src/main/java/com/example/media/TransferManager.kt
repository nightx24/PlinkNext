package com.example.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.example.data.model.TransferDirection
import com.example.data.model.TransferItem
import com.example.data.model.TransferStatus
import com.example.data.network.PocketLinkApi
import com.example.data.store.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class TransferManager(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()

    private val _transfers = MutableStateFlow<List<TransferItem>>(emptyList())
    val transfers: StateFlow<List<TransferItem>> = _transfers.asStateFlow()

    val downloadsDir: File
        get() = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir,
            "PocketLink"
        ).apply { mkdirs() }

    fun enqueueDownload(
        api: PocketLinkApi,
        remotePath: String,
        volume: String?,
        fileName: String,
        expectedSize: Long = 0L,
        onCompleted: ((File) -> Unit)? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        val safeName = fileName.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "download" }
        val target = uniqueTargetFile(downloadsDir, safeName)
        val item = TransferItem(
            id = id,
            fileName = fileName,
            remotePath = remotePath,
            volume = volume,
            direction = TransferDirection.DOWNLOAD,
            status = TransferStatus.RUNNING,
            bytesTransferred = 0L,
            totalBytes = expectedSize,
            localFilePath = target.absolutePath,
        )
        upsert(item)

        val job = scope.launch {
            try {
                var lastEmit = 0L
                api.downloadToFile(remotePath, volume, target) { read, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 120L || (total > 0 && read >= total)) {
                        lastEmit = now
                        updateItem(id) {
                            it.copy(
                                bytesTransferred = read,
                                totalBytes = if (total > 0) total else it.totalBytes,
                                updatedAtMs = now,
                            )
                        }
                    }
                }
                updateItem(id) {
                    it.copy(
                        status = TransferStatus.COMPLETED,
                        bytesTransferred = target.length(),
                        totalBytes = target.length(),
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
                AppLogger.info("Download completed", fileName)
                onCompleted?.invoke(target)
            } catch (cancel: CancellationException) {
                target.delete()
                updateItem(id) { it.copy(status = TransferStatus.CANCELLED, updatedAtMs = System.currentTimeMillis()) }
                throw cancel
            } catch (error: Exception) {
                target.delete()
                AppLogger.error("Download failed", "$fileName: ${error.message}")
                updateItem(id) {
                    it.copy(
                        status = TransferStatus.FAILED,
                        errorMessage = error.message ?: "Download failed",
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
            } finally {
                jobs.remove(id)
            }
        }
        jobs[id] = job
        return id
    }

    fun enqueueUpload(
        api: PocketLinkApi,
        uri: Uri,
        remoteDirPath: String,
        volume: String?,
        onCompleted: (() -> Unit)? = null,
    ): String {
        val (fileName, fileSize) = queryUriMetadata(uri)
        val id = UUID.randomUUID().toString()
        val remoteTarget = if (remoteDirPath.isBlank()) fileName else "$remoteDirPath/$fileName"
        val item = TransferItem(
            id = id,
            fileName = fileName,
            remotePath = remoteTarget,
            volume = volume,
            direction = TransferDirection.UPLOAD,
            status = TransferStatus.RUNNING,
            bytesTransferred = 0L,
            totalBytes = fileSize,
        )
        upsert(item)

        val job = scope.launch {
            try {
                var lastEmit = 0L
                api.uploadStream(
                    remoteDirPath = remoteDirPath,
                    volume = volume,
                    fileName = fileName,
                    contentLength = fileSize,
                    openInputStream = { appContext.contentResolver.openInputStream(uri) },
                ) { sent, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 120L || (total > 0 && sent >= total)) {
                        lastEmit = now
                        updateItem(id) {
                            it.copy(
                                bytesTransferred = sent,
                                totalBytes = if (total > 0) total else it.totalBytes,
                                updatedAtMs = now,
                            )
                        }
                    }
                }
                updateItem(id) {
                    it.copy(
                        status = TransferStatus.COMPLETED,
                        bytesTransferred = fileSize,
                        totalBytes = fileSize,
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
                AppLogger.info("Upload completed", fileName)
                onCompleted?.invoke()
            } catch (cancel: CancellationException) {
                updateItem(id) { it.copy(status = TransferStatus.CANCELLED, updatedAtMs = System.currentTimeMillis()) }
                throw cancel
            } catch (error: Exception) {
                AppLogger.error("Upload failed", "$fileName: ${error.message}")
                updateItem(id) {
                    it.copy(
                        status = TransferStatus.FAILED,
                        errorMessage = error.message ?: "Upload failed",
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
            } finally {
                jobs.remove(id)
            }
        }
        jobs[id] = job
        return id
    }

    fun cancel(id: String) {
        jobs[id]?.cancel()
    }

    fun clearFinished() {
        _transfers.update { list ->
            list.filter { it.status == TransferStatus.RUNNING || it.status == TransferStatus.QUEUED }
        }
    }

    fun openDownloadedFile(context: Context, localPath: String): Boolean {
        val file = File(localPath)
        if (!file.exists()) return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(Intent.createChooser(intent, "Open ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    fun shareDownloadedFile(context: Context, localPath: String): Boolean {
        val file = File(localPath)
        if (!file.exists()) return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(Intent.createChooser(intent, "Share ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    private fun queryUriMetadata(uri: Uri): Pair<String, Long> {
        var name = "upload_${System.currentTimeMillis()}"
        var size = -1L
        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) {
                    cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { name = it }
                }
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                    size = cursor.getLong(sizeIdx)
                }
            }
        }
        return name to size
    }

    private fun uniqueTargetFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var idx = 1
        while (candidate.exists()) {
            val nextName = if (ext.isEmpty()) "$stem ($idx)" else "$stem ($idx).$ext"
            candidate = File(dir, nextName)
            idx++
        }
        return candidate
    }

    private fun upsert(item: TransferItem) {
        _transfers.update { listOf(item) + it }
    }

    private fun updateItem(id: String, transform: (TransferItem) -> TransferItem) {
        _transfers.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }
}
