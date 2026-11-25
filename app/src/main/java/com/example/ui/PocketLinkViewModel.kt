package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.memory.MemoryCache
import com.example.data.model.AppSettingsState
import com.example.data.model.AppThemeMode
import com.example.data.model.HomeActivityItem
import com.example.data.model.IndexedFile
import com.example.data.model.MediaCategory
import com.example.data.model.NetworkRouteType
import com.example.data.model.ServerProfile
import com.example.data.model.YtAudioQuality
import com.example.data.model.classifyNetworkRoute
import com.example.data.model.parseServerEndpoint
import com.example.data.network.ApiHttpException
import com.example.data.network.LocalLosslessEntry
import com.example.data.network.LocalLosslessMatcher
import com.example.data.network.PocketLinkApi
import com.example.data.network.TrackLyrics
import com.example.data.network.YtHomeFeed
import com.example.data.network.YtMusicClient
import com.example.data.network.YtTrack
import com.example.data.store.AppLogger
import com.example.data.store.DiagnosticLogGenerator
import com.example.data.store.SecureCredentialStore
import com.example.data.store.SettingsRepository
import com.example.media.AudioPlaybackController
import com.example.media.TransferManager
import io.github.augztn.pocketlink.ssdbridge.AudioMetadataExtractor
import io.github.augztn.pocketlink.ssdbridge.LocalHostController
import io.github.augztn.pocketlink.ssdbridge.LocalHostStatus
import io.github.augztn.pocketlink.ssdbridge.MediaPlaybackBridge
import io.github.augztn.pocketlink.ssdbridge.ResolvedExternalStream
import io.github.augztn.pocketlink.ssdbridge.SampleMediaSeeder
import io.github.augztn.pocketlink.ssdbridge.tunnel.CloudflareTunnelConfig
import io.github.augztn.pocketlink.ssdbridge.tunnel.CloudflareTunnelMode
import io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelManager
import io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelProbeResult
import io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelStatusSnapshot
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

data class YtMusicUiState(
    val homeFeed: YtHomeFeed = YtHomeFeed(),
    val loadingHome: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<YtTrack> = emptyList(),
    val searching: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val errorMessage: String? = null,
    val lastSearchedQuery: String = "",
    val localLosslessEntries: List<LocalLosslessEntry> = emptyList(),
    val scanningLossless: Boolean = false,
    val losslessScannedOnce: Boolean = false,
    val loadingMore: Boolean = false,
)

class PocketLinkViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    val settingsRepository = SettingsRepository(appContext)
    val credentialStore = SecureCredentialStore(appContext)
    val hostController = LocalHostController(appContext)
    val audioController = AudioPlaybackController(appContext)
    val transferManager = TransferManager(appContext)
    val ytMusicClient: YtMusicClient = sharedYtClient

    val settings: StateFlow<AppSettingsState> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettingsState())

    private val _connection = MutableStateFlow(
        ConnectionUiState(profiles = credentialStore.loadProfiles())
    )
    val connection: StateFlow<ConnectionUiState> = _connection.asStateFlow()

    private val _hostStatus = MutableStateFlow(LocalHostStatus())
    val hostStatus: StateFlow<LocalHostStatus> = _hostStatus.asStateFlow()

    val tunnelManager: TunnelManager = hostController.tunnelManager
    val tunnelStatus: StateFlow<TunnelStatusSnapshot> = tunnelManager.status
    val tunnelConfig: StateFlow<CloudflareTunnelConfig> = tunnelManager.config

    private val _ytMusic = MutableStateFlow(YtMusicUiState())
    val ytMusic: StateFlow<YtMusicUiState> = _ytMusic.asStateFlow()

    private val _lyricsState = MutableStateFlow<TrackLyrics?>(null)
    val lyricsState: StateFlow<TrackLyrics?> = _lyricsState.asStateFlow()

    private var suggestionJob: Job? = null
    private var radioJob: Job? = null
    private var loadMoreDiscoverJob: Job? = null
    private var lyricsJob: Job? = null
    private var losslessScanJob: Job? = null

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
        sharedYtClient.isUnmeteredNetworkProvider = { isUnmeteredNetwork() }
        MediaPlaybackBridge.ytMediaClientProvider = { sharedYtClient.mediaClient }
        MediaPlaybackBridge.ytStreamResolver = { videoId ->
            val info = sharedYtClient.resolveAudioStreamBlocking(videoId)
            ResolvedExternalStream(
                url = info.url,
                userAgent = info.userAgent,
                headers = info.extraHeaders,
                badgeLabel = info.badgeLabel,
                sourceName = info.clientName,
                useQueryRange = info.useQueryRange,
                isHls = info.isHls,
                expiresAtMs = info.expiresAtMs,
            )
        }
        MediaPlaybackBridge.onYtStreamError = { videoId ->
            sharedYtClient.invalidateStream(videoId, markLastClientFailed = true)
        }
        MediaPlaybackBridge.onResolvedStreamMeta = { videoId, badge, source ->
            audioController.updateResolvedStreamMeta(videoId, badge, source)
        }
        audioController.onNearQueueEnd = { queue, currentIndex, ended ->
            triggerAutoplayRecommendations(
                queue = queue,
                currentIndex = currentIndex,
                playImmediatelyIfEnded = ended,
            )
        }
        refreshHostStatus()
        observeYtSettings()
        observeActiveTrackLyrics()
        observeLosslessLibraryTriggers()
    }

    private fun isUnmeteredNetwork(): Boolean = runCatching {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching true
        val active = cm.activeNetwork ?: return@runCatching true
        val caps = cm.getNetworkCapabilities(active) ?: return@runCatching true
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(true)

    private fun observeYtSettings() {
        viewModelScope.launch {
            settings.collect { s ->
                val qualityChanged = ytMusicClient.audioQuality != s.ytAudioQuality
                val resolverChanged = ytMusicClient.resolverProfile != s.ytResolverProfile
                val countryChanged = ytMusicClient.countryCode != s.ytCountryCode
                ytMusicClient.audioQuality = s.ytAudioQuality
                ytMusicClient.resolverProfile = s.ytResolverProfile
                ytMusicClient.countryCode = s.ytCountryCode
                MediaPlaybackBridge.applySkipSilence(s.ytSkipSilence)
                if (qualityChanged || resolverChanged || countryChanged) {
                    ytMusicClient.clearCaches()
                }
                if (qualityChanged || resolverChanged) {
                    AppLogger.info(
                        "Discover stream quality updated",
                        "requested=${s.ytAudioQuality.shortLabel}, effective=${ytMusicClient.effectiveAudioQuality().shortLabel}, profile=${s.ytResolverProfile}"
                    )
                    audioController.reloadCurrentDiscoverTrackAtPosition()
                }
                if (!s.ytStreamingEnabled) {
                    audioController.stopYouTubePlaybackIfActive()
                }
                if (!s.ytLyricsEnabled) {
                    _lyricsState.value = null
                }
                if (s.ytLocalLosslessEnabled && !_ytMusic.value.losslessScannedOnce && !_ytMusic.value.scanningLossless) {
                    refreshLocalLosslessLibrary(force = false)
                }
            }
        }
    }

    private fun observeLosslessLibraryTriggers() {
        viewModelScope.launch {
            _connection
                .map { it.activeApi?.baseUrl }
                .distinctUntilChanged()
                .collect {
                    if (settings.value.ytLocalLosslessEnabled) {
                        refreshLocalLosslessLibrary(force = true)
                    }
                }
        }
    }

    fun setDiscoverLocalLosslessEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateYtStreamingOptions(localLosslessEnabled = enabled)
            if (enabled) {
                refreshLocalLosslessLibrary(force = true)
            }
        }
    }

    fun refreshLocalLosslessLibrary(force: Boolean = false) {
        if (!force && (_ytMusic.value.scanningLossless || _ytMusic.value.losslessScannedOnce)) return
        losslessScanJob?.cancel()
        losslessScanJob = viewModelScope.launch {
            _ytMusic.update { it.copy(scanningLossless = true) }
            val scanned = withContext(Dispatchers.IO) {
                LocalLosslessMatcher.scanLosslessLibrary(appContext, _connection.value.activeApi)
            }
            AppLogger.info("Discover lossless library scan complete (${scanned.size} lossless files)")
            _ytMusic.update {
                it.copy(
                    localLosslessEntries = scanned,
                    scanningLossless = false,
                    losslessScannedOnce = true,
                )
            }
        }
    }

    fun resolveDiscoverTrack(track: YtTrack): IndexedFile =
        LocalLosslessMatcher.resolveTrackForPlayback(
            track = track,
            enabled = settings.value.ytLocalLosslessEnabled,
            library = _ytMusic.value.localLosslessEntries,
        )

    fun setYtAudioQuality(quality: YtAudioQuality) {
        viewModelScope.launch {
            settingsRepository.updateYtStreamingOptions(audioQuality = quality)
        }
    }

    fun setAutoplayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateYtStreamingOptions(autoQueueRelated = enabled)
            if (enabled) {
                val st = audioController.state.value
                if (st.queue.isNotEmpty() && st.repeatMode == com.example.media.RepeatModeState.OFF) {
                    val remaining = st.queue.lastIndex - st.currentIndex
                    if (remaining <= 3) {
                        triggerAutoplayRecommendations(st.queue, st.currentIndex, playImmediatelyIfEnded = false)
                    }
                }
            }
        }
    }

    fun requestMoreAutoplaySuggestions() {
        val st = audioController.state.value
        if (st.queue.isEmpty()) return
        triggerAutoplayRecommendations(
            queue = st.queue,
            currentIndex = st.currentIndex.coerceAtLeast(0),
            playImmediatelyIfEnded = false,
            forceFetch = true,
        )
    }

    /**
     * Spotify-style infinite Autoplay engine:
     * When ANY playlist (Local/Host music or Discover) approaches its end (<= 3 tracks left)
     * or finishes, resolves related YouTube Music radio recommendations seeded from the
     * playlist's tail/active tracks, checks for local lossless matches if enabled, and
     * appends them seamlessly so music plays infinitely.
     */
    private fun triggerAutoplayRecommendations(
        queue: List<IndexedFile>,
        currentIndex: Int,
        playImmediatelyIfEnded: Boolean,
        forceFetch: Boolean = false,
    ) {
        if (!forceFetch && !settings.value.ytAutoQueueRelated) return
        if (!settings.value.ytStreamingEnabled) return
        if (queue.isEmpty()) return
        if (radioJob?.isActive == true && !playImmediatelyIfEnded) return

        radioJob?.cancel()
        radioJob = viewModelScope.launch {
            audioController.setLoadingRecommendations(true)
            try {
                val existingVideoIds = queue
                    .filter { it.isYouTubeStream || it.isLocalLosslessMatch }
                    .mapTo(HashSet()) { it.path }

                // Pick candidate seeds from the tail of the playlist/queue first, then active track
                val candidateSeeds = buildList {
                    queue.lastOrNull()?.let { add(it) }
                    queue.getOrNull(currentIndex)?.let { if (it !in this) add(it) }
                    if (queue.size >= 2) {
                        queue[queue.lastIndex - 1].let { if (it !in this) add(it) }
                    }
                }

                val collected = ArrayList<YtTrack>()
                val seenIds = HashSet<String>(existingVideoIds)

                for (seed in candidateSeeds) {
                    if (collected.size >= 16) break
                    val seedVideoId = if (seed.isYouTubeStream || seed.isLocalLosslessMatch) {
                        seed.path
                    } else {
                        val rawTitle = seed.title?.takeIf { it.isNotBlank() } ?: seed.displayTitle
                        val rawArtist = seed.artist?.takeIf { it.isNotBlank() }.orEmpty()
                        val (parsedArtist, parsedTitle) = if (rawArtist.isBlank()) {
                            LocalLosslessMatcher.extractArtistAndTitleFromPath(seed.path, seed.name)
                        } else {
                            rawArtist to rawTitle
                        }
                        runCatching {
                            ytMusicClient.findSeedVideoIdForTrack(parsedTitle, parsedArtist)
                        }.getOrNull()
                    }

                    if (!seedVideoId.isNullOrBlank()) {
                        seenIds.add(seedVideoId)
                        val batch = runCatching {
                            ytMusicClient.fetchRadioNext(
                                videoId = seedVideoId,
                                limit = 20,
                                excludeVideoIds = seenIds,
                            )
                        }.getOrDefault(emptyList())
                        for (track in batch) {
                            if (seenIds.add(track.videoId)) {
                                collected.add(track)
                            }
                        }
                    }
                }

                // Fallback: if radio returned nothing for an unusual local filename, search by artist/title
                if (collected.isEmpty()) {
                    val anchor = candidateSeeds.firstOrNull()
                    val fallbackQuery = listOfNotNull(
                        anchor?.artist?.takeIf { it.isNotBlank() },
                        anchor?.displayTitle?.takeIf { it.isNotBlank() },
                    ).joinToString(" ").trim()
                    if (fallbackQuery.isNotBlank()) {
                        val searchBatch = runCatching {
                            ytMusicClient.searchSongs(fallbackQuery, limit = 15)
                        }.getOrDefault(emptyList())
                        for (t in searchBatch) {
                            if (seenIds.add(t.videoId)) collected.add(t)
                        }
                    }
                }

                if (collected.isNotEmpty()) {
                    val losslessEnabled = settings.value.ytLocalLosslessEnabled
                    val library = _ytMusic.value.localLosslessEntries
                    val resolved = collected.map { candidate ->
                        LocalLosslessMatcher.resolveTrackForPlayback(
                            track = candidate,
                            enabled = losslessEnabled,
                            library = library,
                        ).copy(isAutoplayRecommendation = true)
                    }
                    val added = audioController.appendAutoplayTracks(
                        extraTracks = resolved,
                        autoPlayIfEnded = playImmediatelyIfEnded,
                    )
                    if (added > 0) {
                        AppLogger.info(
                            "Autoplay queued +$added suggested tracks (∞)",
                            "queueSize=${audioController.state.value.queue.size}"
                        )
                    }
                }
            } finally {
                audioController.setLoadingRecommendations(false)
            }
        }
    }

    /**
     * Loads additional Discover tracks (charts or search) so the Discover feed scrolls
     * continuously without stopping at a fixed track cap.
     */
    fun loadMoreDiscoverTracks() {
        val cur = _ytMusic.value
        if (!settings.value.ytStreamingEnabled || cur.loadingMore || cur.loadingHome || cur.searching) return
        val showingSearch = cur.lastSearchedQuery.isNotBlank() || cur.searchResults.isNotEmpty()
        val existingList = if (showingSearch) cur.searchResults else cur.homeFeed.trending
        if (existingList.isEmpty()) return

        loadMoreDiscoverJob?.cancel()
        loadMoreDiscoverJob = viewModelScope.launch {
            _ytMusic.update { it.copy(loadingMore = true) }
            val excludeIds = (existingList + cur.homeFeed.quickPicks).mapTo(HashSet()) { it.videoId }
            val extra = runCatching {
                ytMusicClient.fetchMoreDiscoverTracks(
                    seedTracks = existingList,
                    query = if (showingSearch) cur.lastSearchedQuery else "",
                    excludeVideoIds = excludeIds,
                    limit = 24,
                )
            }.getOrDefault(emptyList())

            _ytMusic.update { state ->
                if (extra.isEmpty()) {
                    state.copy(loadingMore = false)
                } else if (showingSearch) {
                    val existing = state.searchResults.mapTo(HashSet()) { it.videoId }
                    state.copy(
                        searchResults = state.searchResults + extra.filter { it.videoId !in existing },
                        loadingMore = false,
                    )
                } else {
                    val existing = state.homeFeed.trending.mapTo(HashSet()) { it.videoId }
                    state.copy(
                        homeFeed = state.homeFeed.copy(
                            trending = state.homeFeed.trending + extra.filter { it.videoId !in existing }
                        ),
                        loadingMore = false,
                    )
                }
            }
        }
    }

    fun generateDiagnosticLogReport(probeOutput: String? = null): String {
        val entries = _ytMusic.value.localLosslessEntries
        return DiagnosticLogGenerator.generateReport(
            context = appContext,
            settings = settings.value,
            effectiveQuality = ytMusicClient.effectiveAudioQuality(),
            playbackState = audioController.state.value,
            lastTrace = ytMusicClient.lastResolutionTrace,
            localLosslessCount = entries.size,
            localLosslessTaggedCount = entries.count { it.metadataSource != "FILENAME" },
            localLosslessWithLyricsCount = entries.count { it.hasEmbeddedLyrics },
            activeLyricsSource = _lyricsState.value?.let { "${it.source} (${it.lines.size} lines)" },
            hostStatus = _hostStatus.value,
            tunnelStatus = tunnelStatus.value,
            probeOutput = probeOutput,
        )
    }

    fun runDiscoverStreamProbe(onDone: (String) -> Unit) {
        val currentTrack = audioController.state.value.currentTrack
        val targetVideoId = when {
            currentTrack != null && (currentTrack.isYouTubeStream || currentTrack.isLocalLosslessMatch) -> currentTrack.path
            _ytMusic.value.searchResults.isNotEmpty() -> _ytMusic.value.searchResults.first().videoId
            _ytMusic.value.homeFeed.trending.isNotEmpty() -> _ytMusic.value.homeFeed.trending.first().videoId
            else -> "dQw4w9WgXcQ"
        }
        viewModelScope.launch {
            val result = ytMusicClient.probeQualityTiers(targetVideoId)
            onDone(result)
        }
    }

    private fun observeActiveTrackLyrics() {
        viewModelScope.launch {
            audioController.state
                .map { it.currentTrack }
                .distinctUntilChanged()
                .collect { track ->
                    lyricsJob?.cancel()
                    if (track == null || !settings.value.ytLyricsEnabled) {
                        _lyricsState.value = null
                        return@collect
                    }
                    _lyricsState.value = null
                    lyricsJob = launch {
                        val fetched = resolveLyricsForTrack(track)
                        if (audioController.state.value.currentTrack?.path == track.path) {
                            _lyricsState.value = fetched
                        }
                    }
                }
        }
    }

    /**
     * BitChord + Telegram-Music-Addon 3-stage lyrics detection:
     * 1) Embedded container tags (FLAC VORBIS_COMMENT / ID3v2 USLT / MP4 ©lyr) or sidecar .lrc
     *    on local files or connected PocketLink host files.
     * 2) Exact LRCLIB /api/get using clean title + primary artist + album + duration.
     * 3) Scored LRCLIB /api/search ranking synced LRC, title/artist/album similarity, and duration proximity.
     */
    private suspend fun resolveLyricsForTrack(track: IndexedFile): TrackLyrics? = withContext(Dispatchers.IO) {
        // Stage 1A: Local device file or content URI embedded tags / sidecar .lrc
        val uriText = track.streamUri.orEmpty()
        if (uriText.startsWith("file://", ignoreCase = true)) {
            val localFile = runCatching { Uri.parse(uriText).path?.let(::File) }.getOrNull()
            if (localFile != null && localFile.isFile) {
                val meta = AudioMetadataExtractor.extractFromFile(localFile, includeLyrics = true)
                val embedded = meta.embeddedLyrics?.let {
                    YtMusicClient.parseRawLyricsText(it, meta.source.ifBlank { "Embedded" })
                }
                if (embedded != null) return@withContext embedded
            }
        } else if (uriText.startsWith("content://", ignoreCase = true)) {
            val meta = AudioMetadataExtractor.extractFromUri(
                context = appContext,
                uri = Uri.parse(uriText),
                fileName = track.name,
                sizeBytes = track.size,
                includeLyrics = true,
            )
            val embedded = meta.embeddedLyrics?.let {
                YtMusicClient.parseRawLyricsText(it, meta.source.ifBlank { "Embedded" })
            }
            if (embedded != null) return@withContext embedded
        }

        // Stage 1B: Connected PocketLink host file (or Host-matched Discover Lossless file) embedded tags / .lrc
        val api = _connection.value.activeApi
        if (api != null && !track.isYouTubeStream) {
            val hostPath = track.sourceFilePath?.takeIf { it.isNotBlank() }
                ?: track.path.takeIf { !track.isLocalLosslessMatch }
            val hostVolume = track.sourceVolume ?: track.volume.takeIf { !track.isLocalLosslessMatch }
            if (!hostPath.isNullOrBlank()) {
                val hostResult = api.fetchHostAudioLyrics(hostPath, hostVolume)
                if (hostResult != null) {
                    val parsed = YtMusicClient.parseRawLyricsText(hostResult.first, hostResult.second)
                    if (parsed != null) return@withContext parsed
                }
            }
        }

        // Stage 2 & 3: Metadata-aware LRCLIB exact + scored search
        val effectiveDurationMs = track.durationMs.takeIf { it > 0L }
            ?: audioController.state.value.durationMs.takeIf { it > 0L }
            ?: YtMusicClient.parseDurationTextToMs(track.durationText)

        // If a Host audio file has no embedded artist tag, try splitting "Artist - Title" from filename
        val rawTitle = track.title?.takeIf { it.isNotBlank() } ?: track.displayTitle
        val rawArtist = track.artist?.takeIf { it.isNotBlank() }.orEmpty()
        val (resolvedArtist, resolvedTitle) = if (rawArtist.isBlank() && !track.isYouTubeStream) {
            LocalLosslessMatcher.extractArtistAndTitleFromPath(track.path, track.name)
        } else {
            rawArtist to rawTitle
        }

        runCatching {
            ytMusicClient.fetchLyrics(
                trackName = resolvedTitle,
                artistName = resolvedArtist,
                albumName = track.album.orEmpty(),
                durationMs = effectiveDurationMs,
            )
        }.getOrNull()
    }

    fun loadYtHomeFeed(forceRefresh: Boolean = false) {
        if (!settings.value.ytStreamingEnabled) return
        val cur = _ytMusic.value
        if (!forceRefresh && (cur.loadingHome || (cur.homeFeed.trending.isNotEmpty() && cur.errorMessage == null))) {
            return
        }
        viewModelScope.launch {
            _ytMusic.update { it.copy(loadingHome = true, errorMessage = null) }
            runCatching { ytMusicClient.fetchHomeFeed(forceRefresh = forceRefresh) }
                .onSuccess { feed ->
                    _ytMusic.update {
                        it.copy(
                            homeFeed = feed,
                            loadingHome = false,
                            errorMessage = if (feed.trending.isEmpty() && feed.quickPicks.isEmpty()) {
                                "No Discover tracks returned for ${settings.value.ytCountryCode}."
                            } else null,
                        )
                    }
                }
                .onFailure { err ->
                    AppLogger.warning("Discover home load failed", err.message ?: err.javaClass.simpleName)
                    _ytMusic.update {
                        it.copy(
                            loadingHome = false,
                            errorMessage = err.message ?: "Could not load Discover charts.",
                        )
                    }
                }
        }
    }

    fun updateYtSearchQuery(query: String) {
        _ytMusic.update {
            it.copy(
                searchQuery = query,
                suggestions = if (query.trim().length < 2) emptyList() else it.suggestions,
                searchResults = if (query.isBlank()) emptyList() else it.searchResults,
                lastSearchedQuery = if (query.isBlank()) "" else it.lastSearchedQuery,
                errorMessage = if (query.isBlank()) null else it.errorMessage,
            )
        }
        suggestionJob?.cancel()
        if (!settings.value.ytStreamingEnabled || query.trim().length < 2) return
        suggestionJob = viewModelScope.launch {
            delay(260L)
            val list = runCatching { ytMusicClient.fetchSearchSuggestions(query) }.getOrDefault(emptyList())
            if (_ytMusic.value.searchQuery == query) {
                _ytMusic.update { it.copy(suggestions = list) }
            }
        }
    }

    fun searchYtMusic(query: String = _ytMusic.value.searchQuery) {
        if (!settings.value.ytStreamingEnabled) return
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _ytMusic.update { it.copy(searchResults = emptyList(), lastSearchedQuery = "", suggestions = emptyList(), errorMessage = null) }
            return
        }
        suggestionJob?.cancel()
        viewModelScope.launch {
            _ytMusic.update {
                it.copy(
                    searchQuery = trimmed,
                    searching = true,
                    suggestions = emptyList(),
                    errorMessage = null,
                )
            }
            runCatching { ytMusicClient.searchSongs(trimmed, limit = 30) }
                .onSuccess { results ->
                    _ytMusic.update {
                        it.copy(
                            searchResults = results,
                            searching = false,
                            lastSearchedQuery = trimmed,
                            errorMessage = if (results.isEmpty()) "No songs matched \"$trimmed\"." else null,
                        )
                    }
                }
                .onFailure { err ->
                    AppLogger.warning("Discover search failed", err.message ?: err.javaClass.simpleName)
                    _ytMusic.update {
                        it.copy(
                            searching = false,
                            errorMessage = err.message ?: "Search failed. Check your internet connection.",
                        )
                    }
                }
        }
    }

    fun playYtTrack(track: YtTrack, contextQueue: List<YtTrack> = listOf(track)) {
        if (!settings.value.ytStreamingEnabled) return
        val list = if (contextQueue.any { it.videoId == track.videoId }) contextQueue else listOf(track) + contextQueue
        val idx = list.indexOfFirst { it.videoId == track.videoId }.coerceAtLeast(0)
        val losslessEnabled = settings.value.ytLocalLosslessEnabled

        viewModelScope.launch {
            val library = if (losslessEnabled && !_ytMusic.value.losslessScannedOnce) {
                _ytMusic.update { it.copy(scanningLossless = true) }
                val fresh = withContext(Dispatchers.IO) {
                    LocalLosslessMatcher.scanLosslessLibrary(appContext, _connection.value.activeApi)
                }
                _ytMusic.update {
                    it.copy(
                        localLosslessEntries = fresh,
                        scanningLossless = false,
                        losslessScannedOnce = true,
                    )
                }
                fresh
            } else {
                _ytMusic.value.localLosslessEntries
            }

            val indexedQueue = list.map { candidate ->
                LocalLosslessMatcher.resolveTrackForPlayback(
                    track = candidate,
                    enabled = losslessEnabled,
                    library = library,
                )
            }
            val selected = indexedQueue.getOrNull(idx)
            if (selected?.isLocalLosslessMatch == true) {
                AppLogger.info("Discover playing local lossless file", "${selected.displayTitle} [${selected.localLosslessBadge}]")
            }
            audioController.playQueue(indexedQueue, idx)

            // Proactively append Spotify-style Autoplay recommendations after the playlist so
            // playback continues seamlessly and infinitely when the queue ends.
            if (settings.value.ytAutoQueueRelated) {
                triggerAutoplayRecommendations(
                    queue = indexedQueue,
                    currentIndex = idx,
                    playImmediatelyIfEnded = false,
                )
            }
        }
    }

    /**
     * Starts a local/host playlist and pre-loads Spotify-style Autoplay recommendations
     * right after the playlist finishes (when Autoplay is enabled).
     */
    fun playHostOrLocalPlaylist(tracks: List<IndexedFile>, startIndex: Int) {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return
        audioController.playQueue(tracks, startIndex)
        if (settings.value.ytAutoQueueRelated && settings.value.ytStreamingEnabled) {
            triggerAutoplayRecommendations(
                queue = tracks,
                currentIndex = startIndex,
                playImmediatelyIfEnded = false,
            )
        }
    }

    fun refreshLyrics() {
        val track = audioController.state.value.currentTrack ?: return
        if (!settings.value.ytLyricsEnabled) return
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            val fetched = resolveLyricsForTrack(track)
            if (audioController.state.value.currentTrack?.path == track.path) {
                _lyricsState.value = fetched
            }
        }
    }

    fun refreshHostStatus() {
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { hostController.getStatus() }
            _hostStatus.value = status
        }
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

    fun ensureLocalHostPasswordProfile(): ServerProfile {
        val generated = hostController.generateLocalPasswordIfUnconfigured()
        if (generated != null) {
            val id = credentialStore.createProfileId("127.0.0.1", 8000, "admin")
            val profile = ServerProfile(
                id = id,
                name = "This Device",
                host = "127.0.0.1",
                port = 8000,
                username = "admin",
                password = generated,
            )
            credentialStore.saveProfile(profile)
            _connection.update { it.copy(profiles = credentialStore.loadProfiles()) }
            return profile
        }
        val existing = credentialStore.loadProfiles().firstOrNull {
            (it.host == "127.0.0.1" || it.host == "localhost") && it.port == 8000 && it.password.isNotEmpty()
        }
        if (existing != null) {
            if (!hostController.verifyServerPassword(existing.password)) {
                if (existing.password.length >= 12) {
                    hostController.setServerPassword(existing.password)
                } else {
                    val synced = generateAndSaveHostPassword().getOrElse { "pocketlink-auto-auth" }
                    return existing.copy(password = synced)
                }
            }
            return existing
        }

        val syncedPassword = generateAndSaveHostPassword().getOrElse { "pocketlink-auto-auth" }
        val id = credentialStore.createProfileId("127.0.0.1", 8000, "admin")
        val profile = ServerProfile(
            id = id,
            name = "This Device",
            host = "127.0.0.1",
            port = 8000,
            username = "admin",
            password = syncedPassword,
        )
        credentialStore.saveProfile(profile)
        _connection.update { it.copy(profiles = credentialStore.loadProfiles()) }
        return profile
    }

    fun resolveLocalHostPassword(): String = ensureLocalHostPasswordProfile().password

    fun quickStartLocalDevice() {
        if (_connection.value.isConnecting) return
        viewModelScope.launch {
            _connection.update { it.copy(isConnecting = true, errorMessage = null) }
            withContext(Dispatchers.IO) {
                SampleMediaSeeder.seedIfNeeded(appContext, force = false)
            }
            val localProfile = ensureLocalHostPasswordProfile()

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

            val activePort = io.github.augztn.pocketlink.ssdbridge.BridgeService.activeApiPort
                .takeIf { it > 0 } ?: 8000
            val probeApi = PocketLinkApi("127.0.0.1:$activePort", localProfile.username, localProfile.password)
            var ready = false
            for (attempt in 0 until 25) {
                val ok = runCatching { probeApi.checkHealth() }.isSuccess
                if (ok) {
                    ready = true
                    break
                }
                delay(60L)
            }
            refreshHostStatus()

            if (!ready) {
                _connection.update {
                    it.copy(
                        isConnecting = false,
                        errorMessage = _hostStatus.value.error ?: "Local server did not respond on 127.0.0.1:$activePort.",
                    )
                }
                return@launch
            }

            viewModelScope.launch {
                runCatching { probeApi.startMediaIndexScan() }
            }
            _connection.update { it.copy(isConnecting = false) }
            connect(
                name = localProfile.name,
                rawHost = localProfile.host,
                portText = activePort.toString(),
                username = localProfile.username,
                password = localProfile.password,
                profileId = localProfile.id,
            )
        }
    }

    /**
     * One-tap zero-config Cloudflare Tunnel connection:
     * - If a remote Host device's Cloudflare URL is saved or an active remote Host device is
     *   discovered on the network, connects directly to that remote Host device.
     * - Otherwise, starts this device's Host Server + Cloudflare Tunnel and connects.
     */
    fun quickConnectCloudflareTunnel() {
        if (_connection.value.isConnecting) return
        viewModelScope.launch {
            _connection.update { it.copy(isConnecting = true, errorMessage = null) }
            val cfg = tunnelManager.ensureAutoProvisioned()

            // 1. Check if user has a saved remote Cloudflare Host profile (from another device)
            val savedRemoteCf = credentialStore.loadProfiles().firstOrNull { profile ->
                val sub = io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelManager
                    .extractAutoCloudflareSubdomain(profile.host)
                sub != null && !io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelManager
                    .isThisDeviceAutoPublicUrl(profile.host)
            }
            if (savedRemoteCf != null) {
                _connection.update { it.copy(isConnecting = false) }
                connect(
                    name = savedRemoteCf.name.ifBlank { "PocketLink Host" },
                    rawHost = savedRemoteCf.host,
                    portText = savedRemoteCf.port.toString(),
                    username = savedRemoteCf.username,
                    password = savedRemoteCf.password.ifBlank { "pocketlink-auto-auth" },
                    profileId = savedRemoteCf.id,
                )
                return@launch
            }

            // 2. Discover any active remote Host phone on the network
            val discoveredRemote = withContext(Dispatchers.IO) {
                io.github.augztn.pocketlink.ssdbridge.tunnel.PocketLinkPeerBridge.discoverAnyRemoteHost()
            }
            if (discoveredRemote != null) {
                _connection.update { it.copy(isConnecting = false) }
                connect(
                    name = discoveredRemote.deviceName.ifBlank { "PocketLink Host" },
                    rawHost = discoveredRemote.publicUrl,
                    portText = "443",
                    username = "admin",
                    password = "pocketlink-auto-auth",
                    profileId = null,
                )
                return@launch
            }

            // 3. No remote Host found: start this device as Host + Cloudflare Tunnel
            withContext(Dispatchers.IO) {
                SampleMediaSeeder.seedIfNeeded(appContext, force = false)
            }
            val localProfile = ensureLocalHostPasswordProfile()
            val cfUrl = cfg.activePublicUrl.ifBlank { cfg.autoPublicUrl }

            hostController.startServer()
            hostController.startTunnel()

            val activePort = io.github.augztn.pocketlink.ssdbridge.BridgeService.activeApiPort
                .takeIf { it > 0 } ?: 8000
            val probeApi = PocketLinkApi("127.0.0.1:$activePort", localProfile.username, localProfile.password)
            var ready = false
            for (attempt in 0 until 30) {
                val ok = runCatching { probeApi.checkHealth() }.isSuccess
                if (ok) {
                    ready = true
                    break
                }
                delay(80L)
            }
            refreshHostStatus()

            if (!ready) {
                _connection.update {
                    it.copy(
                        isConnecting = false,
                        errorMessage = _hostStatus.value.error ?: "Local host server did not start on port $activePort.",
                    )
                }
                return@launch
            }

            viewModelScope.launch {
                runCatching { probeApi.startMediaIndexScan() }
            }
            _connection.update { it.copy(isConnecting = false) }
            connect(
                name = "This Device (Cloudflare)",
                rawHost = cfUrl,
                portText = "443",
                username = localProfile.username,
                password = localProfile.password,
                profileId = null,
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

    fun saveCloudflareTunnelConfig(
        mode: CloudflareTunnelMode,
        workerPublicUrl: String? = null,
        workerSecret: String? = null,
        zeroTrustPublicUrl: String? = null,
        zeroTrustToken: String? = null,
        autoStartWithServer: Boolean? = null,
    ): Result<CloudflareTunnelConfig> = tunnelManager.saveCloudflareConfig(
        mode = mode,
        workerPublicUrl = workerPublicUrl,
        workerSecret = workerSecret,
        zeroTrustPublicUrl = zeroTrustPublicUrl,
        zeroTrustToken = zeroTrustToken,
        autoStartWithServer = autoStartWithServer,
    )

    fun generateCloudflareWorkerSecret(): String = tunnelManager.generateWorkerSecret()

    fun readCloudflareWorkerSecret(): String = tunnelManager.readWorkerSecret()

    fun clearCloudflareSecret(mode: CloudflareTunnelMode) {
        tunnelManager.clearSavedSecret(mode)
    }

    fun startCloudflareTunnel(): Result<Unit> {
        ensureLocalHostPasswordProfile()
        val res = hostController.startTunnel()
        viewModelScope.launch {
            delay(350L)
            refreshHostStatus()
        }
        return res
    }

    fun stopCloudflareTunnel() {
        hostController.stopTunnel()
    }

    fun verifyCloudflareTunnel(onDone: (Result<TunnelProbeResult>) -> Unit) {
        viewModelScope.launch {
            val result = tunnelManager.verifyPublicEndpoint()
            onDone(result)
        }
    }

    fun clearTunnelLogs() {
        tunnelManager.clearLogs()
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
            playHostOrLocalPlaylist(tracks, startIndex)
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
                playHostOrLocalPlaylist(tracks, 0)
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

        val route = classifyNetworkRoute(endpoint.baseUrl)
        val isThisDeviceOwnAutoCloudflare = route == NetworkRouteType.CLOUDFLARE &&
            io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelManager.isThisDeviceAutoPublicUrl(endpoint.baseUrl)
        val effectivePassword = when {
            isThisDeviceOwnAutoCloudflare || route == NetworkRouteType.LOCALHOST -> {
                if (password.isNotEmpty() && hostController.verifyServerPassword(password)) {
                    password
                } else {
                    resolveLocalHostPassword()
                }
            }
            password.isNotEmpty() -> password
            else -> "pocketlink-auto-auth"
        }

        if (route == NetworkRouteType.PUBLIC) {
            _connection.update {
                it.copy(
                    errorMessage = "Plain HTTP is not allowed over the public internet. Use an https:// Cloudflare Tunnel URL, Wi-Fi LAN IP, or Tailscale IP (100.x.y.z)."
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
                if (isThisDeviceOwnAutoCloudflare || route == NetworkRouteType.LOCALHOST) {
                    hostController.startServer()
                    if (isThisDeviceOwnAutoCloudflare) {
                        hostController.startTunnel()
                    }
                    val activePort = io.github.augztn.pocketlink.ssdbridge.BridgeService.activeApiPort
                        .takeIf { it > 0 } ?: 8000
                    val probe = PocketLinkApi("127.0.0.1:$activePort", trimmedUser, effectivePassword)
                    for (attempt in 0 until 20) {
                        if (runCatching { probe.checkHealth() }.isSuccess) break
                        delay(70L)
                    }
                    refreshHostStatus()
                }
                val api = PocketLinkApi(endpoint.baseUrl, trimmedUser, effectivePassword)
                api.authenticate()
                val id = credentialStore.createProfileId(endpoint.hostForProfile, endpoint.port, trimmedUser)
                val profile = ServerProfile(
                    id = id,
                    name = name.trim().ifBlank { if (route == NetworkRouteType.CLOUDFLARE) "Cloudflare Tunnel" else "" },
                    host = endpoint.hostForProfile,
                    port = endpoint.port,
                    username = trimmedUser,
                    password = effectivePassword,
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
                val currentHostStatus = withContext(Dispatchers.IO) { hostController.getStatus() }
                _hostStatus.value = currentHostStatus
                val tailscaleActive = currentHostStatus.tailscaleIps.isNotEmpty()
                val msg = when {
                    route == NetworkRouteType.LOCALHOST && !currentHostStatus.running ->
                        "127.0.0.1 connects to THIS phone only, and the local server is stopped. " +
                            "To connect to another phone, enter THAT phone's Tailscale IP (100.x.y.z), or tap Local Mode below."
                    route == NetworkRouteType.TAILSCALE && !tailscaleActive ->
                        "Tailscale VPN does not look active on this phone. Open the Tailscale app, connect VPN to the same account as the host, and try again."
                    route == NetworkRouteType.TAILSCALE ->
                        "Could not reach ${endpoint.hostForProfile}:${endpoint.port} over Tailscale. " +
                            "Check that: (1) Host server is started on the other phone, (2) both phones are signed into the same Tailscale account, and (3) you entered the OTHER phone's 100.x.y.z IP."
                    route == NetworkRouteType.CLOUDFLARE ->
                        "Could not reach Cloudflare Tunnel (${endpoint.baseUrl}). " +
                            "Check that the Host phone's PocketLink server and Cloudflare Tunnel are active, then try again."
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
        audioController.stop()
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

    fun setHeaderBlurEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setHeaderBlurEnabled(enabled) }
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
        audioController.releaseController()
        runCatching { _imageLoader.value.shutdown() }
        super.onCleared()
    }

    companion object {
        private val sharedYtClient = YtMusicClient()
    }
}
