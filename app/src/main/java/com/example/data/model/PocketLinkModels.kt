package com.example.data.model

import java.net.URI
import java.text.DecimalFormat
import java.util.Locale

enum class AppThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

enum class NetworkRouteType(val label: String, val detail: String) {
    LOCALHOST("This Device", "Loopback connection"),
    TAILSCALE("Tailscale", "Private encrypted tunnel"),
    HTTPS("HTTPS", "Encrypted remote proxy"),
    PRIVATE_LAN("Private LAN", "Local network"),
    PUBLIC("Remote", "Direct host"),
}

enum class MediaCategory(val apiValue: String, val label: String) {
    MUSIC("music", "Music"),
    VIDEO("video", "Videos"),
    PHOTO("photo", "Photos"),
}

enum class FileSortField(val label: String) {
    NAME("Name"),
    MODIFIED("Date modified"),
    SIZE("Size"),
}

enum class TransferDirection {
    UPLOAD,
    DOWNLOAD,
}

enum class TransferStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class FileEntry(
    val name: String,
    val type: String,
    val size: Long,
    val modified: String,
    val created: String? = null,
) {
    val isDirectory: Boolean get() = type == "directory"
    val extension: String get() = name.substringAfterLast('.', "").lowercase(Locale.US)
}

data class DirectoryListing(
    val path: String,
    val items: List<FileEntry>,
)

data class StorageVolume(
    val id: String,
    val name: String,
    val available: Boolean,
    val writable: Boolean,
    val totalBytes: Long? = null,
    val usedBytes: Long? = null,
    val availableBytes: Long? = null,
    val usagePercent: Double? = null,
)

data class HostSystemTelemetry(
    val sampledAtMs: Long? = null,
    val manufacturer: String? = null,
    val deviceModel: String? = null,
    val androidVersion: String? = null,
    val cpuModel: String? = null,
    val cpuCores: Int? = null,
    val cpuUsagePercent: Double? = null,
    val cpuUsageStatus: String = "unavailable",
    val memoryTotalBytes: Long? = null,
    val memoryUsedBytes: Long? = null,
    val memoryAvailableBytes: Long? = null,
    val memoryUsagePercent: Double? = null,
    val storageTotalBytes: Long? = null,
    val storageUsedBytes: Long? = null,
    val storageAvailableBytes: Long? = null,
    val storageUsagePercent: Double? = null,
    val batteryPercent: Int? = null,
    val batteryStatus: String = "unknown",
) {
    val isCharging: Boolean get() = batteryStatus == "charging"
    val deviceDisplayName: String
        get() = listOfNotNull(manufacturer, deviceModel).joinToString(" ").ifBlank { "PocketLink Host" }
}

data class VideoMetadata(
    val durationMs: Long,
    val width: Int,
    val height: Int,
) {
    val resolution: String
        get() = if (width > 0 && height > 0) "${width}×${height}" else ""
}

data class IndexedFile(
    val volume: String,
    val path: String,
    val name: String,
    val type: String,
    val size: Long,
    val modified: String,
    val mime: String,
    val category: String? = null,
) {
    val isDirectory: Boolean get() = type == "directory"
    fun toFileEntry(): FileEntry = FileEntry(name = name, type = type, size = size, modified = modified)
    val parentPath: String
        get() {
            val sep = path.lastIndexOf('/')
            return if (sep < 0) "" else path.substring(0, sep)
        }
}

data class MediaIndexStatus(
    val state: String,
    val scanned: Int,
    val skipped: Int,
    val lastCompletedAt: Long,
    val fileCount: Int,
    val mediaCount: Int,
    val lastError: String? = null,
    val lastScanStartedAt: Long = 0L,
    val elapsedMs: Long = 0L,
    val currentSource: String? = null,
) {
    val isScanning: Boolean get() = state == "scanning"
}

data class IndexedMediaPage(
    val items: List<IndexedFile>,
    val total: Int,
    val status: MediaIndexStatus,
)

data class RecentFile(
    val file: FileEntry,
    val path: String,
    val volume: String?,
    val volumeName: String,
) {
    val parentPath: String
        get() {
            val sep = path.lastIndexOf('/')
            return if (sep < 0) "" else path.substring(0, sep)
        }
}

data class ServerProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String = "",
) {
    val address: String get() = "$host:$port"
    val displayName: String get() = name.ifBlank { address }
}

data class TransferItem(
    val id: String,
    val fileName: String,
    val remotePath: String,
    val volume: String?,
    val direction: TransferDirection,
    val status: TransferStatus,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val localFilePath: String? = null,
    val errorMessage: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    val progress: Float
        get() = if (totalBytes > 0L) (bytesTransferred.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

data class HomeActivityItem(
    val kind: String, // "track" | "folder" | "file"
    val title: String,
    val path: String,
    val serverUrl: String,
    val volume: String?,
    val updatedAtMs: Long,
) {
    val isFolder: Boolean get() = kind == "folder"
    val parentPath: String
        get() {
            val sep = path.lastIndexOf('/')
            return if (sep < 0) "" else path.substring(0, sep)
        }
}

data class AppLogEntry(
    val timestampMs: Long,
    val level: String,
    val message: String,
    val details: String? = null,
)

data class AppSettingsState(
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val amoled: Boolean = false,
    val useLiquidGlass: Boolean = true,
    val navigationOrder: List<String> = DEFAULT_NAVIGATION_ORDER,
    val visibleNavigationItems: Set<String> = DEFAULT_NAVIGATION_ORDER.toSet(),
    val quickActions: List<String> = DEFAULT_QUICK_ACTIONS,
    val homeSectionOrder: List<String> = DEFAULT_HOME_SECTION_ORDER,
    val showQuickActions: Boolean = true,
    val showLibrary: Boolean = true,
    val showRecentFiles: Boolean = true,
    val showGlobalSearch: Boolean = true,
    val showActiveTransfers: Boolean = true,
    val showMiniPlayer: Boolean = true,
    val enableNowPlayingGestures: Boolean = true,
    val showStatsForNerds: Boolean = true,
    val videoControlsTimeoutSeconds: Int = 4,
    val videoSeekSeconds: Int = 10,
    val videoDefaultSpeed: Float = 1.0f,
    val videoFitMode: String = "contain",
    val videoGesturesEnabled: Boolean = true,
    val videoPinchZoomEnabled: Boolean = true,
    val videoTapToTogglePlayback: Boolean = false,
    val videoAutoFullscreen: Boolean = false,
    val floatingShortcutEnabled: Boolean = false,
    val floatingShortcutTapAction: String = "menu",
    val floatingShortcutLongPressEnabled: Boolean = true,
    val floatingShortcuts: List<String> = DEFAULT_FLOATING_SHORTCUTS,
    val photoFavorites: Set<String> = emptySet(),
    val homeActivity: List<HomeActivityItem> = emptyList(),
) {
    companion object {
        val DEFAULT_QUICK_ACTIONS = listOf("upload", "drives", "favorites", "transfers")
        val ALL_QUICK_ACTIONS = listOf(
            "files", "upload", "favorites", "photos", "music", "videos", "drives", "documents", "transfers"
        )
        val DEFAULT_HOME_SECTION_ORDER = listOf(
            "search", "quick", "transfers", "continue", "recent"
        )
        val DEFAULT_NAVIGATION_ORDER = listOf("home", "explore", "files", "browse")
        val DEFAULT_FLOATING_SHORTCUTS = listOf(
            "explore", "music", "files", "videos", "photos", "documents"
        )
        val ALL_FLOATING_SHORTCUTS = listOf(
            "explore", "files", "music", "videos", "photos", "documents", "favorites", "upload"
        )
    }
}

object MediaExtensions {
    val audio = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "wma", "aiff", "alac")
    val video = setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "wmv", "mpeg", "mpg", "3gp", "ts", "m2ts", "mts", "flv")
    val photo = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp", "avif", "dng")
    val document = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "json", "xml", "zip", "rar", "7z", "tar", "gz", "epub")
    val pdf = setOf("pdf")
    val office = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp")
    val text = setOf("txt", "md", "csv", "json", "xml", "log", "yaml", "yml")
    val archive = setOf("zip", "rar", "7z", "tar", "gz")

    fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase(Locale.US) in audio
    fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase(Locale.US) in video
    fun isPhoto(name: String) = name.substringAfterLast('.', "").lowercase(Locale.US) in photo
    fun isDocument(name: String) = name.substringAfterLast('.', "").lowercase(Locale.US) in document
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    val fmt = if (index == 0) DecimalFormat("#,##0") else DecimalFormat("#,##0.0")
    return "${fmt.format(value)} ${units[index]}"
}

fun formatDurationMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

data class ParsedServerEndpoint(
    val hostForProfile: String,
    val port: Int,
    val baseUrl: String,
)

fun parseServerEndpoint(rawHost: String, rawPortText: String): ParsedServerEndpoint {
    val cleaned = rawHost
        .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
        .trim()
        .removeSuffix("/health")
        .trimEnd('/')
    val explicitPort = rawPortText.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    require(cleaned.isNotEmpty()) { "Enter the server Tailscale IP or address." }

    if (cleaned.startsWith("http://", ignoreCase = true) || cleaned.startsWith("https://", ignoreCase = true)) {
        val uri = runCatching { URI(cleaned) }.getOrNull()
        val scheme = uri?.scheme?.lowercase(Locale.US)
        val uriHost = uri?.host?.removePrefix("[")?.removeSuffix("]")
        if (!scheme.isNullOrBlank() && !uriHost.isNullOrBlank()) {
            val resolvedPort = when {
                uri.port in 1..65535 -> uri.port
                explicitPort != null && (scheme == "http" || explicitPort != 8000) -> explicitPort
                scheme == "https" -> 443
                else -> 8000
            }
            val formattedHost = if (uriHost.contains(':')) "[$uriHost]" else uriHost
            val defaultSchemePort = (scheme == "https" && resolvedPort == 443) || (scheme == "http" && resolvedPort == 80)
            val url = if (defaultSchemePort && uri.port <= 0) "$scheme://$formattedHost" else "$scheme://$formattedHost:$resolvedPort"
            return ParsedServerEndpoint(
                hostForProfile = if (scheme == "https") "$scheme://$formattedHost" else uriHost,
                port = resolvedPort,
                baseUrl = url,
            )
        }
    }

    // Handle "[ipv6]:port", "[ipv6]", "host:port", bare "ipv6", or bare "host"
    var parsedHost = cleaned
    var parsedPort = explicitPort ?: 8000
    if (cleaned.startsWith("[")) {
        val closeIdx = cleaned.indexOf(']')
        if (closeIdx > 1) {
            parsedHost = cleaned.substring(1, closeIdx)
            val remainder = cleaned.substring(closeIdx + 1)
            if (remainder.startsWith(":")) {
                remainder.substring(1).toIntOrNull()?.takeIf { it in 1..65535 }?.let { parsedPort = it }
            }
        }
    } else {
        val colonCount = cleaned.count { it == ':' }
        if (colonCount == 1) {
            val hostPart = cleaned.substringBefore(':').trim()
            val portPart = cleaned.substringAfter(':').trim().toIntOrNull()
            if (hostPart.isNotEmpty() && portPart != null && portPart in 1..65535) {
                parsedHost = hostPart
                parsedPort = portPart
            }
        }
    }

    val urlHost = if (parsedHost.contains(':')) "[$parsedHost]" else parsedHost
    return ParsedServerEndpoint(
        hostForProfile = parsedHost,
        port = parsedPort,
        baseUrl = "http://$urlHost:$parsedPort",
    )
}

fun classifyNetworkRoute(baseUrl: String): NetworkRouteType {
    val parsed = runCatching { URI(baseUrl) }.getOrNull()
    val scheme = parsed?.scheme?.lowercase(Locale.US) ?: "http"
    val host = parsed?.host
        ?.removePrefix("[")
        ?.removeSuffix("]")
        ?.lowercase(Locale.US)
        ?: return NetworkRouteType.PUBLIC
    if (host == "127.0.0.1" || host == "localhost" || host == "::1") {
        return NetworkRouteType.LOCALHOST
    }
    if (scheme == "https") {
        return NetworkRouteType.HTTPS
    }
    if (host.endsWith(".ts.net") || isTailscaleIpv4Host(host) || host.startsWith("fd7a:115c:a1e0")) {
        return NetworkRouteType.TAILSCALE
    }
    if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
        return NetworkRouteType.PRIVATE_LAN
    }
    return NetworkRouteType.PUBLIC
}

private fun isTailscaleIpv4Host(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    val first = parts[0].toIntOrNull() ?: return false
    val second = parts[1].toIntOrNull() ?: return false
    return first == 100 && second in 64..127
}
