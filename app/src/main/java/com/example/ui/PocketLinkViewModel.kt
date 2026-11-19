package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.memory.MemoryCache
import com.example.data.model.AppSettingsState
import com.example.data.model.AppThemeMode
import com.example.data.model.HomeActivityItem
import com.example.data.model.MediaCategory
import com.example.data.model.NetworkRouteType
import com.example.data.model.ServerProfile
import com.example.data.model.classifyNetworkRoute
import com.example.data.model.parseServerEndpoint
import com.example.data.network.ApiHttpException
import com.example.data.network.PocketLinkApi
import com.example.data.store.AppLogger
import com.example.data.store.SecureCredentialStore
import com.example.data.store.SettingsRepository
import com.example.media.AudioPlaybackController
import com.example.media.TransferManager
import io.github.augztn.pocketlink.ssdbridge.LocalHostController
import io.github.augztn.pocketlink.ssdbridge.LocalHostStatus
import io.github.augztn.pocketlink.ssdbridge.SampleMediaSeeder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConnectionUiState(
    val profiles: List<ServerProfile> = emptyList(),
    val isConnecting: Boolean = false,
    val connectingProfileId: String? = null,
    val errorMessage: String? = null,
    val activeApi: PocketLinkApi? = null,
    val activeProfile: ServerProfile? = null,
)

class PocketLinkViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    val settingsRepository = SettingsRepository(appContext)
    val credentialStore = SecureCredentialStore(appContext)
    val hostController = LocalHostController(appContext)
    val audioController = AudioPlaybackController(appContext)
    val transferManager = TransferManager(appContext)

    val settings: StateFlow<AppSettingsState> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettingsState())

    private val _connection = MutableStateFlow(
        ConnectionUiState(profiles = credentialStore.loadProfiles())
    )
    val connection: StateFlow<ConnectionUiState> = _connection.asStateFlow()

    private val _hostStatus = MutableStateFlow(hostController.getStatus())
    val hostStatus: StateFlow<LocalHostStatus> = _hostStatus.asStateFlow()

    private val _imageLoader = MutableStateFlow(
        ImageLoader.Builder(appContext)
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizePercent(0.16)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
    )
    val imageLoader: StateFlow<ImageLoader> = _imageLoader.asStateFlow()

    init {
        AppLogger.info("PocketLink initialized")
        refreshHostStatus()
    }

    fun refreshHostStatus() {
        _hostStatus.value = hostController.getStatus()
    }

    fun setHostPassword(password: String): Result<Unit> {
        val result = hostController.setServerPassword(password)
        if (result.isSuccess) {
            val id = credentialStore.createProfileId("127.0.0.1", 8000, "admin")
            credentialStore.saveProfile(
                ServerProfile(
                    id = id,
                    name = "This Device",
                    host = "127.0.0.1",
                    port = 8000,
                    username = "admin",
                    password = password,
                )
            )
            _connection.update { it.copy(profiles = credentialStore.loadProfiles()) }
        }
        refreshHostStatus()
        return result
    }

    fun generateAndSaveHostPassword(): Result<String> = runCatching {
        val chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = java.security.SecureRandom()
        val password = buildString(16) {
            repeat(16) { append(chars[random.nextInt(chars.length)]) }
        }
        setHostPassword(password).getOrThrow()
        password
    }

    fun startLocalHost(): Result<Unit> {
        val generated = hostController.generateLocalPasswordIfUnconfigured()
        if (generated != null) {
            val id = credentialStore.createProfileId("127.0.0.1", 8000, "admin")
            credentialStore.saveProfile(
                ServerProfile(
                    id = id,
                    name = "This Device",
                    host = "127.0.0.1",
                    port = 8000,
                    username = "admin",
                    password = generated,
                )
            )
            _connection.update { it.copy(profiles = credentialStore.loadProfiles()) }
        }
        val result = hostController.startServer()
        viewModelScope.launch {
            delay(250L)
            refreshHostStatus()
            delay(650L)
            refreshHostStatus()
        }
        return result
    }

    fun seedSampleMedia(force: Boolean = true, onDone: (Result<Int>) -> Unit = {}) {
        viewModelScope.launch {
            val res = runCatching {
                withContext(Dispatchers.IO) {
                    SampleMediaSeeder.seedIfNeeded(appContext, force = force)
                }
            }
            refreshHostStatus()
            onDone(res)
        }
    }

    fun quickStartLocalDevice() {
        if (_connection.value.isConnecting) return
        viewModelScope.launch {
            _connection.update { it.copy(isConnecting = true, errorMessage = null) }
            withContext(Dispatchers.IO) {
                SampleMediaSeeder.seedIfNeeded(appContext, force = false)
            }
            val generatedPassword = hostController.generateLocalPasswordIfUnconfigured()
            if (generatedPassword != null) {
                val id = credentialStore.createProfileId("127.0.0.1", 8000, "admin")
                credentialStore.saveProfile(
                    ServerProfile(
                        id = id,
                        name = "This Device",
                        host = "127.0.0.1",
                        port = 8000,
                        username = "admin",
                        password = generatedPassword,
                    )
                )
                _connection.update { it.copy(profiles = credentialStore.loadProfiles()) }
            }

            val localProfile = credentialStore.loadProfiles().firstOrNull {
                (it.host == "127.0.0.1" || it.host == "localhost") && it.port == 8000 && it.password.isNotEmpty()
            }
            if (localProfile == null) {
                _connection.update {
                    it.copy(
                        isConnecting = false,
                        errorMessage = "Enter the admin password you configured for this device (or update it in Host Setup) to connect locally.",
                    )
                }
                return@launch
            }

            val startResult = hostController.startServer()
            if (startResult.isFailure) {
                _connection.update {
                    it.copy(
                        isConnecting = false,
                        errorMessage = startResult.exceptionOrNull()?.message ?: "Could not start local PocketLink server.",
                    )
                }
                return@launch
            }

            val probeApi = PocketLinkApi("127.0.0.1:8000", localProfile.username, localProfile.password)
            var ready = false
            for (attempt in 0 until 15) {
                val ok = runCatching { probeApi.checkHealth() }.isSuccess
                if (ok) {
                    ready = true
                    break
                }
                delay(200L)
            }
            runCatching { probeApi.startMediaIndexScan() }
            refreshHostStatus()

            if (!ready) {
                _connection.update {
                    it.copy(
                        isConnecting = false,
                        errorMessage = _hostStatus.value.error ?: "Local server did not respond on 127.0.0.1:8000.",
                    )
                }
                return@launch
            }

            _connection.update { it.copy(isConnecting = false) }
            connect(
                name = localProfile.name,
                rawHost = localProfile.host,
                portText = localProfile.port.toString(),
                username = localProfile.username,
                password = localProfile.password,
                profileId = localProfile.id,
            )
        }
    }

    fun stopLocalHost() {
        hostController.stopServer()
        viewModelScope.launch {
            delay(250L)
            refreshHostStatus()
        }
    }

    fun refreshHostMediaIndex(): Result<Unit> {
        val res = hostController.refreshMediaIndex()
        refreshHostStatus()
        return res
    }

    fun persistSafVolume(volumeId: String, uri: Uri, flags: Int): Result<String> {
        val res = hostController.persistVolumeUri(volumeId, uri, flags)
        refreshHostStatus()
        return res
    }

    fun removeSafVolume(volumeId: String) {
        hostController.removeVolume(volumeId)
        refreshHostStatus()
    }

    fun createScopedFolderPickerIntent(mountPath: String?): Intent =
        hostController.createScopedFolderPickerIntent(mountPath)

    fun persistScopedFolderMount(requestedMountPath: String?, uri: Uri, flags: Int): Result<String> {
        val res = hostController.persistScopedFolderMount(requestedMountPath, uri, flags)
        refreshHostStatus()
        return res
    }

    fun removeScopedFolderMount(key: String) {
        hostController.removeScopedFolderMount(key)
        refreshHostStatus()
    }

    fun skipTrackFromDock(next: Boolean, onMessage: (String) -> Unit = {}) {
        val current = audioController.state.value
        if (current.queue.isNotEmpty()) {
            if (next) audioController.next() else audioController.previous()
            return
        }
        val activeApi = _connection.value.activeApi ?: return
        viewModelScope.launch {
            val indexed = runCatching { activeApi.indexedMedia(MediaCategory.MUSIC, limit = 50, offset = 0).items }.getOrNull().orEmpty()
            val tracks = indexed.ifEmpty {
                runCatching { activeApi.walkMediaFallback(MediaCategory.MUSIC) }.getOrNull().orEmpty()
            }
            if (tracks.isEmpty()) {
                onMessage("No music tracks found on this server yet.")
                return@launch
            }
            val startIndex = if (next) 0 else tracks.lastIndex
            audioController.playQueue(tracks, startIndex)
        }
    }

    fun openOrStartMusicFromDock(onOpenNowPlaying: () -> Unit, onOpenMusicLibrary: () -> Unit) {
        val current = audioController.state.value
        if (current.currentTrack != null) {
            onOpenNowPlaying()
            return
        }
        val activeApi = _connection.value.activeApi ?: return
        viewModelScope.launch {
            val indexed = runCatching { activeApi.indexedMedia(MediaCategory.MUSIC, limit = 50, offset = 0).items }.getOrNull().orEmpty()
            val tracks = indexed.ifEmpty {
                runCatching { activeApi.walkMediaFallback(MediaCategory.MUSIC) }.getOrNull().orEmpty()
            }
            if (tracks.isNotEmpty()) {
                audioController.playQueue(tracks, 0)
                onOpenNowPlaying()
            } else {
                onOpenMusicLibrary()
            }
        }
    }

    fun connect(
        name: String,
        rawHost: String,
        portText: String,
        username: String,
        password: String,
        profileId: String? = null,
    ) {
        if (_connection.value.isConnecting) return
        val trimmedUser = username.trim().ifBlank { "admin" }
        val endpoint = runCatching { parseServerEndpoint(rawHost, portText) }.getOrElse { err ->
            _connection.update {
                it.copy(errorMessage = err.message ?: "Enter a valid server Tailscale IP, port (1–65535), and password.")
            }
            return
        }

        if (password.isEmpty()) {
            _connection.update {
                it.copy(errorMessage = "Enter the Host device's admin password.")
            }
            return
        }

        val route = classifyNetworkRoute(endpoint.baseUrl)
        if (route == NetworkRouteType.PRIVATE_LAN || route == NetworkRouteType.PUBLIC) {
            _connection.update {
                it.copy(
                    errorMessage = "Plain HTTP connects over Tailscale (100.x.y.z or *.ts.net) or 127.0.0.1 only. " +
                        "Open Tailscale on both devices, find the Host's 100.x.y.z IP in Host Setup, or use an https:// URL."
                )
            }
            return
        }

        viewModelScope.launch {
            _connection.update {
                it.copy(isConnecting = true, connectingProfileId = profileId, errorMessage = null)
            }
            AppLogger.info("Connecting to server", "${endpoint.baseUrl} as $trimmedUser")
            try {
                val api = PocketLinkApi(endpoint.baseUrl, trimmedUser, password)
                api.authenticate()
                val id = credentialStore.createProfileId(endpoint.hostForProfile, endpoint.port, trimmedUser)
                val profile = ServerProfile(
                    id = id,
                    name = name.trim(),
                    host = endpoint.hostForProfile,
                    port = endpoint.port,
                    username = trimmedUser,
                    password = password,
                )
                credentialStore.saveProfile(profile)
                audioController.attachApi(api)
                val prevLoader = _imageLoader.value
                _imageLoader.value = ImageLoader.Builder(appContext)
                    .okHttpClient(api.httpClient)
                    .memoryCache {
                        MemoryCache.Builder(appContext)
                            .maxSizePercent(0.16)
                            .build()
                    }
                    .respectCacheHeaders(false)
                    .crossfade(true)
                    .build()
                runCatching { prevLoader.shutdown() }
                _connection.value = ConnectionUiState(
                    profiles = credentialStore.loadProfiles(),
                    isConnecting = false,
                    connectingProfileId = null,
                    errorMessage = null,
                    activeApi = api,
                    activeProfile = profile,
                )
                AppLogger.info("Connected to ${profile.displayName}", api.routeType.label)
            } catch (e: ApiHttpException) {
                val msg = when (e.statusCode) {
                    401 -> "Incorrect username or password (401). Enter the exact admin password set on the Host device."
                    403 -> "The server denied access (403). PocketLink only allows connections over Tailscale or localhost."
                    404 -> "PocketLink API not found (404) on ${endpoint.hostForProfile}:${endpoint.port}."
                    else -> e.message ?: "Server returned HTTP ${e.statusCode}."
                }
                AppLogger.warning("Connection failed (${e.statusCode})", msg)
                _connection.update { it.copy(isConnecting = false, connectingProfileId = null, errorMessage = msg) }
            } catch (e: Exception) {
                val tailscaleActive = hostController.getStatus().tailscaleIps.isNotEmpty()
                val msg = when {
                    route == NetworkRouteType.LOCALHOST && !hostController.getStatus().running ->
                        "127.0.0.1 connects to THIS phone only, and the local server is stopped. " +
                            "To connect to another phone, enter THAT phone's Tailscale IP (100.x.y.z), or tap Local Mode below."
                    route == NetworkRouteType.TAILSCALE && !tailscaleActive ->
                        "Tailscale VPN does not look active on this phone. Open the Tailscale app, connect VPN to the same account as the host, and try again."
                    route == NetworkRouteType.TAILSCALE ->
                        "Could not reach ${endpoint.hostForProfile}:${endpoint.port} over Tailscale. " +
                            "Check that: (1) Host server is started on the other phone, (2) both phones are signed into the same Tailscale account, and (3) you entered the OTHER phone's 100.x.y.z IP."
                    else -> e.message?.takeIf { it.isNotBlank() }
                        ?: "Could not reach ${endpoint.hostForProfile}:${endpoint.port}. Check the address, port, and network."
                }
                AppLogger.warning("Connection error", "${e.javaClass.simpleName}: ${e.message}")
                _connection.update { it.copy(isConnecting = false, connectingProfileId = null, errorMessage = msg) }
            }
        }
    }

    fun deleteProfile(id: String) {
        credentialStore.deleteProfile(id)
        _connection.update { it.copy(profiles = credentialStore.loadProfiles()) }
    }

    fun disconnect() {
        audioController.releasePlayer()
        _connection.update {
            it.copy(
                activeApi = null,
                activeProfile = null,
                errorMessage = null,
                isConnecting = false,
                connectingProfileId = null,
            )
        }
    }

    fun clearConnectionError() {
        _connection.update { it.copy(errorMessage = null) }
    }

    fun recordActivity(kind: String, title: String, path: String, volume: String?) {
        val serverUrl = _connection.value.activeApi?.baseUrl ?: return
        viewModelScope.launch {
            settingsRepository.recordActivity(
                HomeActivityItem(
                    kind = kind,
                    title = title,
                    path = path,
                    serverUrl = serverUrl,
                    volume = volume,
                    updatedAtMs = System.currentTimeMillis(),
                )
            )
        }
    }

    fun togglePhotoFavorite(volume: String?, path: String) {
        val key = "${volume ?: "internal"}::$path"
        viewModelScope.launch { settingsRepository.togglePhotoFavorite(key) }
    }

    fun isPhotoFavorite(volume: String?, path: String): Boolean {
        val key = "${volume ?: "internal"}::$path"
        return key in settings.value.photoFavorites
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setAmoled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAmoled(enabled) }
    }

    fun setUseLiquidGlass(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setUseLiquidGlass(enabled) }
    }

    fun setNavigationLayout(order: List<String>, visibleItems: Set<String>) {
        viewModelScope.launch { settingsRepository.setNavigationLayout(order, visibleItems) }
    }

    fun resetNavigationLayout() {
        viewModelScope.launch { settingsRepository.resetNavigationLayout() }
    }

    fun clearCache(onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                }.isSuccess
            }
            onDone(ok)
        }
    }

    fun createVolumePickerIntent(volumeId: String?): Intent = hostController.createVolumePickerIntent(volumeId)

    override fun onCleared() {
        audioController.releasePlayer()
        runCatching { _imageLoader.value.shutdown() }
        super.onCleared()
    }
}
