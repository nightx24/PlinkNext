package io.github.augztn.pocketlink.ssdbridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.Settings
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelManager
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.security.SecureRandom

data class DiscoveredVolumeInfo(
    val id: String,
    val name: String,
    val selected: Boolean,
    val access: String,
    val readable: Boolean,
    val writable: Boolean,
    val state: String,
    val isManual: Boolean = false,
)

data class LocalHostStatus(
    val running: Boolean = false,
    val apiRunning: Boolean = false,
    val bridgeRunning: Boolean = false,
    val error: String? = null,
    val internalStorageGranted: Boolean = false,
    val backgroundAccessGranted: Boolean = false,
    val serverPasswordConfigured: Boolean = false,
    val discoveredVolumes: List<DiscoveredVolumeInfo> = emptyList(),
    val tailscaleIps: List<String> = emptyList(),
    val tailscaleInstalled: Boolean = false,
    val bridgeToken: String = "",
    val mediaIndexState: String = "idle",
    val mediaIndexScanned: Int = 0,
    val mediaIndexSkipped: Int = 0,
    val mediaIndexFileCount: Int = 0,
    val mediaIndexMediaCount: Int = 0,
    val mediaIndexLastCompletedAt: Long = 0L,
    val mediaIndexLastError: String? = null,
    val mediaIndexCurrentSource: String? = null,
    val scopedStorageMode: String = "sandbox_media",
    val scopedFolders: List<ScopedFolderStatus> = emptyList(),
)

class LocalHostController(private val context: Context) {
    private val appContext = context.applicationContext
    private val prefs by lazy {
        appContext.getSharedPreferences("ssd-bridge", Context.MODE_PRIVATE)
    }
    private val scopedStorage by lazy { ScopedStorageRegistry(appContext) }
    val tunnelManager: TunnelManager by lazy { TunnelManager.getInstance(appContext) }

    fun getStatus(): LocalHostStatus {
        val discovered = discoverPhysicalVolumes().map { (id, volume) ->
            val directory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) volume.directory else null
            val directAccess = internalStorageGranted() &&
                directory?.isDirectory == true &&
                directory.canRead() &&
                directory.listFiles() != null
            val safSelected = prefs.getString("volume_uri_$id", null) != null
            DiscoveredVolumeInfo(
                id = id,
                name = volume.getDescription(appContext),
                selected = directAccess || safSelected,
                access = when {
                    directAccess -> "direct"
                    safSelected -> "saf"
                    else -> "not_granted"
                },
                readable = directAccess || safSelected,
                writable = if (directAccess) directory?.canWrite() == true else safSelected,
                state = volume.state,
                isManual = false,
            )
        }

        val discoveredIds = discovered.map { it.id }.toSet()
        val manualVolumes = prefs.all.keys
            .filter { it.startsWith("volume_uri_") }
            .map { it.removePrefix("volume_uri_") }
            .filter { it !in discoveredIds }
            .map { id ->
                val uriStr = prefs.getString("volume_uri_$id", null)
                val docName = uriStr?.let { raw ->
                    runCatching { DocumentFile.fromTreeUri(appContext, Uri.parse(raw))?.name }.getOrNull()
                }
                val savedName = prefs.getString("volume_name_$id", null)
                DiscoveredVolumeInfo(
                    id = id,
                    name = docName ?: savedName ?: id,
                    selected = uriStr != null,
                    access = if (docName != null) "saf" else "revoked",
                    readable = docName != null,
                    writable = docName != null,
                    state = if (docName != null) Environment.MEDIA_MOUNTED else "unavailable",
                    isManual = true,
                )
            }

        val indexJson = BridgeService.mediaIndexStatus()?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }

        return LocalHostStatus(
            running = BridgeService.isRunning,
            apiRunning = BridgeService.apiRunning,
            bridgeRunning = BridgeService.bridgeRunning,
            error = BridgeService.lastError,
            internalStorageGranted = internalStorageGranted(),
            backgroundAccessGranted = backgroundAccessGranted(),
            serverPasswordConfigured = ServerCredentials(appContext).isConfigured(),
            discoveredVolumes = discovered + manualVolumes,
            tailscaleIps = detectTailscaleIps(),
            tailscaleInstalled = isTailscaleInstalled(),
            bridgeToken = ensureToken(),
            mediaIndexState = indexJson?.optString("state", "idle") ?: "idle",
            mediaIndexScanned = indexJson?.optInt("scanned", 0) ?: 0,
            mediaIndexSkipped = indexJson?.optInt("skipped", 0) ?: 0,
            mediaIndexFileCount = indexJson?.optInt("file_count", 0) ?: 0,
            mediaIndexMediaCount = indexJson?.optInt("media_count", 0) ?: 0,
            mediaIndexLastCompletedAt = indexJson?.optLong("last_completed_at", 0L) ?: 0L,
            mediaIndexLastError = indexJson?.optString("last_error")?.takeIf { it.isNotBlank() && it != "null" },
            mediaIndexCurrentSource = indexJson?.optString("current_source")?.takeIf { it.isNotBlank() && it != "null" },
            scopedStorageMode = scopedStorage.storageModeTag(),
            scopedFolders = scopedStorage.getScopedFolderStatuses(),
        )
    }

    fun internalStorageGranted(): Boolean = scopedStorage.hasFullFilesAccess()

    fun backgroundAccessGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
    }

    fun setServerPassword(password: String): Result<Unit> = runCatching {
        ServerCredentials(appContext).setPassword(password)
    }

    fun generateLocalPasswordIfUnconfigured(): String? {
        val credentials = ServerCredentials(appContext)
        if (credentials.isConfigured()) return null
        val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
        val generated = Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        credentials.setPassword(generated)
        return generated
    }

    fun startServer(): Result<Unit> = runCatching {
        ensureToken()
        Thread {
            runCatching { SampleMediaSeeder.seedIfNeeded(appContext, force = false) }
        }.start()
        BridgeService.lastError = null
        val intent = Intent(appContext, BridgeService::class.java)
        ContextCompat.startForegroundService(appContext, intent)
    }

    fun stopServer() {
        tunnelManager.stopActiveTunnel()
        appContext.stopService(Intent(appContext, BridgeService::class.java))
    }

    fun startTunnel(): Result<Unit> = runCatching {
        if (!BridgeService.isRunning) {
            startServer().getOrThrow()
        }
        val intent = Intent(appContext, BridgeService::class.java).apply {
            action = BridgeService.ACTION_START_TUNNEL
        }
        ContextCompat.startForegroundService(appContext, intent)
        tunnelManager.startActiveTunnel(NativeApiServer.PORT).getOrThrow()
    }

    fun stopTunnel() {
        tunnelManager.stopActiveTunnel()
        if (BridgeService.isRunning) {
            runCatching {
                val intent = Intent(appContext, BridgeService::class.java).apply {
                    action = BridgeService.ACTION_STOP_TUNNEL
                }
                appContext.startService(intent)
            }
        }
    }

    fun refreshMediaIndex(): Result<Unit> = runCatching {
        val status = BridgeService.refreshMediaIndex()
        check(status != null) { "Start the PocketLink server before scanning media and files." }
    }

    fun isTailscaleInstalled(): Boolean =
        runCatching {
            appContext.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE) != null
        }.getOrDefault(false)

    fun createOpenTailscaleIntent(): Intent? =
        runCatching {
            appContext.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }.getOrNull()

    fun createInternalStoragePermissionIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || internalStorageGranted()) return null
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${appContext.packageName}")
        )
    }

    fun createBackgroundOptimizationIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || backgroundAccessGranted()) return null
        return Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${appContext.packageName}")
        )
    }

    fun createVolumePickerIntent(volumeId: String?): Intent {
        val matched = volumeId?.let { id ->
            discoverPhysicalVolumes().firstOrNull { it.first == id }?.second
        }
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && matched != null) {
            matched.createOpenDocumentTreeIntent()
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        }
        return intent.apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
    }

    fun generateManualVolumeId(): String {
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "usb-manual-$hex"
    }

    fun persistVolumeUri(volumeId: String, uri: Uri, flags: Int): Result<String> = runCatching {
        val takeFlags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            if (takeFlags != 0) takeFlags else Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val rootName = DocumentFile.fromTreeUri(appContext, uri)?.name ?: volumeId
        prefs.edit()
            .putString("volume_uri_$volumeId", uri.toString())
            .putString("volume_name_$volumeId", rootName)
            .apply()
        rootName
    }

    fun removeVolume(volumeId: String) {
        prefs.edit()
            .remove("volume_uri_$volumeId")
            .remove("volume_name_$volumeId")
            .apply()
    }

    fun createScopedFolderPickerIntent(mountPath: String?): Intent =
        scopedStorage.createScopedFolderPickerIntent(mountPath)

    fun persistScopedFolderMount(requestedMountPath: String?, uri: Uri, flags: Int): Result<String> =
        scopedStorage.persistScopedFolderMount(requestedMountPath, uri, flags).also { res ->
            if (res.isSuccess) BridgeService.refreshMediaIndex()
        }

    fun removeScopedFolderMount(key: String) {
        scopedStorage.removeScopedFolderMount(key)
        BridgeService.refreshMediaIndex()
    }

    private fun discoverPhysicalVolumes(): List<Pair<String, StorageVolume>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptyList()
        val manager = appContext.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return emptyList()
        return manager.storageVolumes.mapNotNull { volume ->
            if (!volume.isRemovable || volume.state !in setOf(
                    Environment.MEDIA_MOUNTED,
                    Environment.MEDIA_MOUNTED_READ_ONLY
                )
            ) {
                return@mapNotNull null
            }
            val identity = externalVolumeIdentity(
                uuid = volume.uuid,
                directoryPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) volume.directory?.absolutePath else null,
                description = volume.getDescription(appContext)
            )
            externalVolumeKey(identity) to volume
        }
    }

    private fun ensureToken(): String {
        prefs.getString("token", null)?.let { return it }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        prefs.edit().putString("token", token).apply()
        return token
    }

    private fun detectTailscaleIps(): List<String> = runCatching {
        val ips = mutableListOf<String>()
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.inetAddresses.toList()) {
                val host = addr.hostAddress?.substringBefore('%') ?: continue
                if (addr is Inet4Address) {
                    val b = addr.address
                    val first = b[0].toInt() and 0xff
                    val second = b[1].toInt() and 0xff
                    if (first == 100 && second in 64..127) {
                        ips.add(host)
                    }
                } else if (addr is Inet6Address) {
                    val b = addr.address
                    if ((b[0].toInt() and 0xff) == 0xfd &&
                        (b[1].toInt() and 0xff) == 0x7a &&
                        (b[2].toInt() and 0xff) == 0x11 &&
                        (b[3].toInt() and 0xff) == 0x5c &&
                        (b[4].toInt() and 0xff) == 0xa1 &&
                        (b[5].toInt() and 0xff) == 0xe0
                    ) {
                        ips.add(host)
                    }
                }
            }
        }
        ips.distinct()
    }.getOrDefault(emptyList())

    companion object {
        const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
    }
}
