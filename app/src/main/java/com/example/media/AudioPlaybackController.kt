package com.example.media

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.model.IndexedFile
import com.example.data.network.PocketLinkApi
import com.example.data.store.AppLogger
import com.google.common.util.concurrent.ListenableFuture
import io.github.augztn.pocketlink.ssdbridge.MediaPlaybackBridge
import io.github.augztn.pocketlink.ssdbridge.MediaPlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RepeatModeState {
    OFF,
    ALL,
    ONE,
}

data class AudioPlaybackState(
    val queue: List<IndexedFile> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatModeState = RepeatModeState.OFF,
    val errorMessage: String? = null,
    val streamUrl: String = "",
    val routeLabel: String = "",
    val streamBadge: String = "",
    val actualAudioFormat: String = "",
    val isLoadingRecommendations: Boolean = false,
) {
    val currentTrack: IndexedFile?
        get() = queue.getOrNull(currentIndex)
}

class AudioPlaybackController(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pendingActions = mutableListOf<(MediaController) -> Unit>()

    private var api: PocketLinkApi? = null
    private var positionJob: Job? = null
    private val retryCountByVideoId = mutableMapOf<String, Int>()

    /**
     * Fired whenever playback approaches the end of the queue (remaining <= 3 tracks)
     * or reaches the end (`ended = true`) with Repeat OFF, allowing Spotify-style infinite
     * autoplay recommendations to append seamlessly for both Discover and local/host playlists.
     */
    @Volatile
    var onNearQueueEnd: ((queue: List<IndexedFile>, currentIndex: Int, ended: Boolean) -> Unit)? = null

    private val _state = MutableStateFlow(AudioPlaybackState())
    val state: StateFlow<AudioPlaybackState> = _state.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val activeController = controller
            val activeUrl = activeController?.currentMediaItem?.localConfiguration?.uri?.toString()
                ?: _state.value.streamUrl
            _state.update {
                it.copy(
                    isPlaying = isPlaying,
                    streamUrl = activeUrl,
                    errorMessage = if (isPlaying) null else it.errorMessage,
                )
            }
            if (isPlaying) {
                _state.value.currentTrack?.path?.let { retryCountByVideoId.remove(it) }
                startPositionPolling()
            } else {
                stopPositionPolling()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val activeController = controller ?: return
            val buffering = playbackState == Player.STATE_BUFFERING
            val duration = activeController.duration.takeIf { it > 0L } ?: _state.value.durationMs
            val index = activeController.currentMediaItemIndex.takeIf {
                it in _state.value.queue.indices
            } ?: _state.value.currentIndex
            val position = if (playbackState == Player.STATE_ENDED) {
                duration
            } else {
                activeController.currentPosition.coerceAtLeast(0L)
            }
            _state.update {
                it.copy(
                    isBuffering = buffering,
                    durationMs = duration,
                    currentIndex = index,
                    positionMs = position,
                )
            }
            if (playbackState == Player.STATE_ENDED &&
                _state.value.repeatMode == RepeatModeState.OFF &&
                _state.value.queue.isNotEmpty()
            ) {
                onNearQueueEnd?.invoke(_state.value.queue, index, true)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val activeController = controller ?: return
            val index = activeController.currentMediaItemIndex.takeIf {
                it in _state.value.queue.indices
            } ?: _state.value.currentIndex
            val url = mediaItem?.localConfiguration?.uri?.toString().orEmpty()
            val duration = activeController.duration.takeIf { it > 0L } ?: 0L
            val activeTrack = _state.value.queue.getOrNull(index)
            _state.update {
                it.copy(
                    currentIndex = index,
                    streamUrl = url.ifBlank { it.streamUrl },
                    routeLabel = resolveRouteLabel(activeTrack),
                    streamBadge = when {
                        activeTrack?.isLocalLosslessMatch == true -> activeTrack.localLosslessBadge.orEmpty()
                        activeTrack?.isYouTubeStream == true -> ""
                        else -> it.streamBadge
                    },
                    actualAudioFormat = "",
                    positionMs = 0L,
                    durationMs = duration,
                    errorMessage = null,
                )
            }
            val q = _state.value.queue
            if (_state.value.repeatMode == RepeatModeState.OFF &&
                q.isNotEmpty() &&
                (q.lastIndex - index) <= AUTOPLAY_PREFETCH_THRESHOLD
            ) {
                onNearQueueEnd?.invoke(q, index, false)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val audioFormat = tracks.groups
                .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                ?.let { group ->
                    (0 until group.length)
                        .firstOrNull { idx -> group.isTrackSelected(idx) }
                        ?.let { idx -> group.getTrackFormat(idx) }
                }
            if (audioFormat != null) {
                val mime = audioFormat.sampleMimeType ?: audioFormat.containerMimeType ?: "audio"
                val codec = audioFormat.codecs?.takeIf { it.isNotBlank() }
                val kbps = audioFormat.bitrate.takeIf { it > 0 }?.div(1000)
                val hz = audioFormat.sampleRate.takeIf { it > 0 }?.let { "${it / 1000.0}kHz".replace(".0kHz", "kHz") }
                val ch = audioFormat.channelCount.takeIf { it > 0 }?.let { "${it}ch" }
                val summary = listOfNotNull(
                    mime,
                    codec?.let { "($it)" },
                    kbps?.let { "${it}kbps" },
                    hz,
                    ch,
                ).joinToString(" · ")
                _state.update { it.copy(actualAudioFormat = summary) }
                AppLogger.info("ExoPlayer active audio format", summary)
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _state.update { it.copy(shuffleEnabled = shuffleModeEnabled) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _state.update { it.copy(repeatMode = repeatMode.toRepeatState()) }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _state.update { it.copy(speed = playbackParameters.speed) }
        }

        override fun onPlayerError(error: PlaybackException) {
            val activeTrack = _state.value.currentTrack
            val rootCauseMsg = generateSequence<Throwable>(error) { it.cause }
                .lastOrNull { !it.message.isNullOrBlank() }
                ?.message
                ?: error.localizedMessage
                ?: "Playback failed"
            if (rootCauseMsg.startsWith("HLS_HANDOFF:")) {
                _state.update { it.copy(isBuffering = true, errorMessage = null) }
                return
            }
            AppLogger.error("Audio playback failed", rootCauseMsg)
            stopPositionPolling()

            if (activeTrack != null && activeTrack.isYouTubeStream) {
                MediaPlaybackBridge.onYtStreamError?.invoke(activeTrack.path)
                val resolverAlreadyExhausted = rootCauseMsg.startsWith("Unable to play track", ignoreCase = true)
                val attempts = retryCountByVideoId[activeTrack.path] ?: 0
                if (!resolverAlreadyExhausted && attempts < MAX_DISCOVER_FAILOVER_RETRIES) {
                    retryCountByVideoId[activeTrack.path] = attempts + 1
                    AppLogger.info(
                        "Retrying Discover stream with next client (${attempts + 1}/$MAX_DISCOVER_FAILOVER_RETRIES)",
                        activeTrack.path
                    )
                    _state.update { it.copy(isBuffering = true, errorMessage = null) }
                    controller?.let { mc ->
                        mc.prepare()
                        mc.play()
                    }
                    return
                }
            }

            _state.update {
                it.copy(
                    errorMessage = rootCauseMsg,
                    isPlaying = false,
                    isBuffering = false,
                )
            }
        }
    }

    init {
        if (MediaPlaybackBridge.isServiceRunning) {
            connectControllerIfNeeded()
        }
    }

    fun attachApi(pocketLinkApi: PocketLinkApi) {
        val previousBaseUrl = MediaPlaybackBridge.activeServerUrl
        api = pocketLinkApi
        MediaPlaybackBridge.mediaClientProvider = { pocketLinkApi.mediaClient }
        if (previousBaseUrl.isNotEmpty() && previousBaseUrl != pocketLinkApi.baseUrl) {
            if (_state.value.currentTrack?.isYouTubeStream != true) {
                stop()
            }
        }
        MediaPlaybackBridge.activeServerUrl = pocketLinkApi.baseUrl
        _state.update {
            it.copy(routeLabel = resolveRouteLabel(it.currentTrack))
        }
    }

    fun updateResolvedStreamMeta(videoId: String, badgeLabel: String, sourceName: String) {
        _state.update { current ->
            if (current.currentTrack?.isYouTubeStream == true && current.currentTrack?.path == videoId) {
                current.copy(
                    streamBadge = badgeLabel,
                    routeLabel = "Discover · $sourceName",
                )
            } else {
                current
            }
        }
    }

    fun retryCurrentTrack() {
        val current = _state.value
        val activeTrack = current.currentTrack ?: return
        if (activeTrack.isYouTubeStream) {
            MediaPlaybackBridge.onYtStreamError?.invoke(activeTrack.path)
        }
        retryCountByVideoId.clear()
        _state.update { it.copy(errorMessage = null, isBuffering = true) }
        withController { mc ->
            mc.prepare()
            mc.play()
        }
    }

    /**
     * Immediately re-resolves and reloads the currently active Discover track at its
     * exact playback timestamp when the user switches audio quality (Auto / High / Medium / Low)
     * or extractor profile, so quality changes take effect live without restarting the track.
     */
    fun reloadCurrentDiscoverTrackAtPosition() {
        val current = _state.value
        val activeTrack = current.currentTrack ?: return
        if (!activeTrack.isYouTubeStream) return
        MediaPlaybackBridge.onYtStreamError?.invoke(activeTrack.path)
        retryCountByVideoId.clear()
        val resumeIndex = current.currentIndex.coerceAtLeast(0)
        val resumePositionMs = (controller?.currentPosition ?: current.positionMs).coerceAtLeast(0L)
        val shouldPlay = controller?.playWhenReady ?: current.isPlaying
        AppLogger.info("Reloading Discover track at new quality", "${activeTrack.displayTitle} @ ${resumePositionMs}ms")
        _state.update {
            it.copy(
                isBuffering = true,
                errorMessage = null,
                streamBadge = "",
                actualAudioFormat = "",
            )
        }
        withController { mc ->
            val currentItem = mc.currentMediaItem
            if (currentItem != null) {
                mc.replaceMediaItem(resumeIndex, currentItem)
                mc.seekTo(resumeIndex, resumePositionMs)
                mc.prepare()
                mc.playWhenReady = shouldPlay
            } else {
                mc.seekTo(resumePositionMs)
                mc.prepare()
                mc.playWhenReady = shouldPlay
            }
        }
    }

    private fun resolveRouteLabel(track: IndexedFile?): String = when {
        track?.isLocalLosslessMatch == true -> "Discover · Local Lossless"
        track?.isYouTubeStream == true -> "Discover · NewPipe"
        else -> api?.routeType?.label ?: "Stream"
    }

    private fun withController(action: (MediaController) -> Unit) {
        val active = controller
        if (active != null && active.isConnected) {
            action(active)
            return
        }
        pendingActions.add(action)
        connectControllerIfNeeded()
    }

    private fun connectControllerIfNeeded() {
        val existingFuture = controllerFuture
        if (existingFuture != null && !existingFuture.isCancelled) {
            return
        }
        val token = SessionToken(appContext, ComponentName(appContext, MediaPlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                try {
                    val mc = future.get()
                    if (controllerFuture !== future) {
                        mc.release()
                        return@addListener
                    }
                    controller = mc
                    mc.addListener(playerListener)
                    syncStateFromController(mc)
                    val actions = pendingActions.toList()
                    pendingActions.clear()
                    actions.forEach { it(mc) }
                } catch (e: Exception) {
                    AppLogger.error("MediaController connection failed", e.message)
                    controllerFuture = null
                    controller = null
                    pendingActions.clear()
                    _state.update {
                        it.copy(
                            isBuffering = false,
                            isPlaying = false,
                            errorMessage = e.localizedMessage ?: "Unable to connect to audio service",
                        )
                    }
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    private fun syncStateFromController(mc: MediaController) {
        val count = mc.mediaItemCount
        val existingQueue = _state.value.queue
        val resolvedQueue = if (existingQueue.size == count && count > 0) {
            existingQueue
        } else if (count > 0) {
            (0 until count).map { idx -> mc.getMediaItemAt(idx).toIndexedFile() }
        } else {
            emptyList()
        }
        val index = mc.currentMediaItemIndex.takeIf { it in resolvedQueue.indices } ?: -1
        val isPlayingNow = mc.isPlaying
        val isBufferingNow = mc.playbackState == Player.STATE_BUFFERING
        val duration = mc.duration.takeIf { it > 0L } ?: _state.value.durationMs
        val pos = mc.currentPosition.coerceAtLeast(0L)
        val activeUrl = mc.currentMediaItem?.localConfiguration?.uri?.toString()
            ?: _state.value.streamUrl
        val currentTrack = resolvedQueue.getOrNull(index)

        _state.update {
            it.copy(
                queue = resolvedQueue,
                currentIndex = index,
                isPlaying = isPlayingNow,
                isBuffering = isBufferingNow,
                positionMs = pos,
                durationMs = duration,
                speed = mc.playbackParameters.speed,
                shuffleEnabled = mc.shuffleModeEnabled,
                repeatMode = mc.repeatMode.toRepeatState(),
                streamUrl = activeUrl,
                routeLabel = resolveRouteLabel(currentTrack),
            )
        }
        if (isPlayingNow) startPositionPolling() else stopPositionPolling()
    }

    fun playQueue(tracks: List<IndexedFile>, startIndex: Int) {
        if (tracks.isEmpty() || startIndex !in tracks.indices) return
        val activeApi = api
        val canResolveAll = tracks.all { !it.streamUri.isNullOrBlank() || activeApi != null }
        if (!canResolveAll) return

        val startTrack = tracks[startIndex]
        val startUrl = resolveTrackUri(startTrack, activeApi)
        val speed = _state.value.speed
        val shuffle = _state.value.shuffleEnabled
        val repeat = _state.value.repeatMode.toPlayerRepeatMode()

        retryCountByVideoId.clear()
        _state.update {
            it.copy(
                queue = tracks,
                currentIndex = startIndex,
                streamUrl = startUrl,
                streamBadge = startTrack.localLosslessBadge.orEmpty(),
                positionMs = 0L,
                durationMs = 0L,
                errorMessage = null,
                isBuffering = true,
                routeLabel = resolveRouteLabel(startTrack),
            )
        }

        val mediaItems = tracks.map { it.toMediaItem(activeApi) }
        withController { mc ->
            mc.repeatMode = repeat
            mc.shuffleModeEnabled = shuffle
            mc.playbackParameters = PlaybackParameters(speed)
            mc.setMediaItems(mediaItems, startIndex, 0L)
            mc.playWhenReady = true
            mc.prepare()
            mc.play()
        }

        if (_state.value.repeatMode == RepeatModeState.OFF &&
            (tracks.lastIndex - startIndex) <= AUTOPLAY_PREFETCH_THRESHOLD
        ) {
            onNearQueueEnd?.invoke(tracks, startIndex, false)
        }
    }

    fun jumpToQueueIndex(index: Int) {
        val current = _state.value
        if (index !in current.queue.indices) return
        val target = current.queue[index]
        retryCountByVideoId.clear()
        _state.update {
            it.copy(
                currentIndex = index,
                streamBadge = target.localLosslessBadge.orEmpty(),
                routeLabel = resolveRouteLabel(target),
                positionMs = 0L,
                durationMs = 0L,
                errorMessage = null,
                isBuffering = true,
            )
        }
        withController { mc ->
            if (index in 0 until mc.mediaItemCount) {
                mc.seekToDefaultPosition(index)
                mc.playWhenReady = true
                mc.play()
            } else {
                playQueue(current.queue, index)
            }
        }
        if (current.repeatMode == RepeatModeState.OFF &&
            (current.queue.lastIndex - index) <= AUTOPLAY_PREFETCH_THRESHOLD
        ) {
            onNearQueueEnd?.invoke(current.queue, index, false)
        }
    }

    fun setLoadingRecommendations(loading: Boolean) {
        _state.update { it.copy(isLoadingRecommendations = loading) }
    }

    fun appendRelatedTracks(anchorVideoId: String, extraTracks: List<IndexedFile>) {
        if (extraTracks.isEmpty()) return
        val currentState = _state.value
        val activeTrack = currentState.currentTrack ?: return
        if (activeTrack.path != anchorVideoId && !activeTrack.isYouTubeStream && !activeTrack.isLocalLosslessMatch) {
            return
        }
        appendTracksInternal(extraTracks, autoPlayIfEnded = false)
    }

    /**
     * Appends Spotify-style Autoplay recommendations to ANY active queue (Discover or Local/Host
     * playlists), deduplicating by path AND normalized title+artist so suggested tracks never
     * repeat existing playlist items. If playback already ended, starts playing the first new song.
     */
    fun appendAutoplayTracks(
        extraTracks: List<IndexedFile>,
        autoPlayIfEnded: Boolean = false,
    ): Int = appendTracksInternal(
        extraTracks = extraTracks.map { it.copy(isAutoplayRecommendation = true) },
        autoPlayIfEnded = autoPlayIfEnded,
    )

    private fun appendTracksInternal(
        extraTracks: List<IndexedFile>,
        autoPlayIfEnded: Boolean,
    ): Int {
        if (extraTracks.isEmpty()) return 0
        val currentState = _state.value
        if (currentState.queue.isEmpty()) return 0

        val existingIds = currentState.queue.mapTo(HashSet()) { "${it.volume}::${it.path}" }
        val existingPaths = currentState.queue.mapTo(HashSet()) { it.path }
        val existingSignatures = currentState.queue.mapTo(HashSet()) { trackDedupeSignature(it) }

        val filtered = extraTracks.filter { candidate ->
            val key = "${candidate.volume}::${candidate.path}"
            val sig = trackDedupeSignature(candidate)
            key !in existingIds &&
                candidate.path !in existingPaths &&
                (sig.isBlank() || sig !in existingSignatures) &&
                existingIds.add(key) &&
                existingPaths.add(candidate.path) &&
                (sig.isBlank() || existingSignatures.add(sig))
        }
        if (filtered.isEmpty()) return 0

        val firstAddedIndex = currentState.queue.size
        _state.update { it.copy(queue = it.queue + filtered) }
        val items = filtered.map { it.toMediaItem(api) }
        withController { mc ->
            val wasEnded = mc.playbackState == Player.STATE_ENDED ||
                (autoPlayIfEnded && !mc.hasNextMediaItem())
            mc.addMediaItems(items)
            if (autoPlayIfEnded && wasEnded && firstAddedIndex in 0 until mc.mediaItemCount) {
                mc.seekToDefaultPosition(firstAddedIndex)
                mc.playWhenReady = true
                mc.prepare()
                mc.play()
            }
        }
        return filtered.size
    }

    private fun trackDedupeSignature(track: IndexedFile): String {
        val t = track.displayTitle.lowercase().replace(Regex("[^a-z0-9]+"), "")
        val a = track.artist.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), "")
        return if (t.isBlank()) "" else "$t|$a"
    }

    fun stopYouTubePlaybackIfActive() {
        if (_state.value.currentTrack?.isYouTubeStream == true) {
            stop()
        }
    }

    private fun resolveTrackUri(track: IndexedFile, activeApi: PocketLinkApi?): String {
        track.streamUri?.takeIf { it.isNotBlank() }?.let { return it }
        val resolvedVolume = track.volume.takeIf { it != "internal" }
        return activeApi!!.streamUrl(track.path, resolvedVolume)
    }

    fun togglePlayPause() {
        val mc = controller ?: run {
            if (_state.value.queue.isNotEmpty()) {
                withController { active -> toggleWithController(active) }
            }
            return
        }
        toggleWithController(mc)
    }

    private fun toggleWithController(mc: MediaController) {
        if (mc.isPlaying || (mc.playWhenReady && mc.playbackState == Player.STATE_BUFFERING)) {
            mc.pause()
        } else {
            _state.update { it.copy(errorMessage = null) }
            if (mc.playbackState == Player.STATE_IDLE) {
                mc.prepare()
            } else if (mc.playbackState == Player.STATE_ENDED) {
                mc.seekTo(0L)
            }
            mc.play()
        }
    }

    fun pause() {
        controller?.pause()
    }

    fun resume() {
        val mc = controller ?: run {
            if (_state.value.queue.isNotEmpty()) {
                withController { active -> resumeWithController(active) }
            }
            return
        }
        resumeWithController(mc)
    }

    private fun resumeWithController(mc: MediaController) {
        _state.update { it.copy(errorMessage = null) }
        if (mc.playbackState == Player.STATE_IDLE) {
            mc.prepare()
        } else if (mc.playbackState == Player.STATE_ENDED) {
            mc.seekTo(0L)
        }
        mc.play()
    }

    fun seekTo(positionMs: Long) {
        val bounded = positionMs.coerceAtLeast(0L)
        controller?.seekTo(bounded)
        _state.update { it.copy(positionMs = bounded) }
    }

    fun setSpeed(speed: Float) {
        _state.update { it.copy(speed = speed) }
        controller?.playbackParameters = PlaybackParameters(speed)
    }

    fun toggleShuffle() {
        val target = !_state.value.shuffleEnabled
        _state.update { it.copy(shuffleEnabled = target) }
        controller?.shuffleModeEnabled = target
    }

    fun cycleRepeatMode() {
        val next = when (_state.value.repeatMode) {
            RepeatModeState.OFF -> RepeatModeState.ALL
            RepeatModeState.ALL -> RepeatModeState.ONE
            RepeatModeState.ONE -> RepeatModeState.OFF
        }
        _state.update { it.copy(repeatMode = next) }
        controller?.repeatMode = next.toPlayerRepeatMode()
    }

    fun next() {
        val current = _state.value
        if (current.queue.isEmpty()) return
        withController { mc ->
            if (mc.hasNextMediaItem()) {
                mc.seekToNextMediaItem()
                mc.play()
            } else if (current.repeatMode == RepeatModeState.ALL && mc.mediaItemCount > 0) {
                mc.seekToDefaultPosition(0)
                mc.play()
            } else if (current.repeatMode == RepeatModeState.OFF) {
                onNearQueueEnd?.invoke(current.queue, current.currentIndex, true)
            }
        }
    }

    fun previous() {
        val current = _state.value
        if (current.queue.isEmpty()) return
        withController { mc ->
            if (mc.currentPosition > 3500L) {
                mc.seekTo(0L)
            } else if (mc.hasPreviousMediaItem()) {
                mc.seekToPreviousMediaItem()
                mc.play()
            } else {
                mc.seekTo(0L)
            }
        }
    }

    fun stop() {
        stopPositionPolling()
        pendingActions.clear()
        controller?.run {
            stop()
            clearMediaItems()
        }
        MediaPlaybackBridge.isPlaying = false
        runCatching { appContext.stopService(MediaPlaybackBridge.isServiceActionStopIntent(appContext)) }
        _state.value = AudioPlaybackState(routeLabel = api?.routeType?.label.orEmpty())
    }

    fun releaseController() {
        stopPositionPolling()
        pendingActions.clear()
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { future ->
            MediaController.releaseFuture(future)
        }
        controllerFuture = null
    }

    fun releasePlayer() {
        stop()
        releaseController()
    }

    private fun startPositionPolling() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (true) {
                val mc = controller
                if (mc != null && mc.isConnected) {
                    val pos = mc.currentPosition.coerceAtLeast(0L)
                    val dur = mc.duration.takeIf { it > 0L } ?: _state.value.durationMs
                    _state.update { it.copy(positionMs = pos, durationMs = dur) }
                }
                delay(500L)
            }
        }
    }

    private fun stopPositionPolling() {
        positionJob?.cancel()
        positionJob = null
    }

    private fun IndexedFile.toMediaItem(activeApi: PocketLinkApi?): MediaItem {
        val url = resolveTrackUri(this, activeApi)
        val folder = parentPath.substringAfterLast('/', "").ifBlank {
            if (volume == "internal") "Internal storage" else volume
        }
        val titleText = displayTitle
        val artistText = artist?.takeIf { it.isNotBlank() } ?: folder
        val extras = Bundle().apply {
            putString(EXTRA_VOLUME, volume)
            putString(EXTRA_PATH, path)
            putString(EXTRA_NAME, name)
            putLong(EXTRA_SIZE, size)
            putString(EXTRA_MODIFIED, modified)
            putString(EXTRA_MIME, mime)
            putString(EXTRA_CATEGORY, category)
            artist?.let { putString(EXTRA_ARTIST, it) }
            artworkUrl?.let { putString(EXTRA_ARTWORK_URL, it) }
            streamUri?.let { putString(EXTRA_STREAM_URI, it) }
            durationText?.let { putString(EXTRA_DURATION_TEXT, it) }
            title?.let { putString(EXTRA_TITLE, it) }
            album?.let { putString(EXTRA_ALBUM, it) }
            putLong(EXTRA_DURATION_MS, durationMs)
            localLosslessBadge?.let { putString(EXTRA_LOSSLESS_BADGE, it) }
            putBoolean(EXTRA_AUTOPLAY_REC, isAutoplayRecommendation)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(titleText)
            .setDisplayTitle(titleText)
            .setArtist(artistText)
            .setSubtitle(artistText)
            .apply {
                artworkUrl?.takeIf { it.isNotBlank() }?.let { setArtworkUri(Uri.parse(it)) }
            }
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId("$volume::$path")
            .setUri(url)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun MediaItem.toIndexedFile(): IndexedFile {
        val extras = mediaMetadata.extras
        val vol = extras?.getString(EXTRA_VOLUME) ?: mediaId.substringBefore("::", "internal")
        val pathVal = extras?.getString(EXTRA_PATH) ?: mediaId.substringAfter("::", "")
        val nameVal = extras?.getString(EXTRA_NAME) ?: mediaMetadata.title?.toString().orEmpty()
        return IndexedFile(
            volume = vol,
            path = pathVal,
            name = nameVal,
            type = "file",
            size = extras?.getLong(EXTRA_SIZE) ?: 0L,
            modified = extras?.getString(EXTRA_MODIFIED).orEmpty(),
            mime = extras?.getString(EXTRA_MIME) ?: "audio/*",
            category = extras?.getString(EXTRA_CATEGORY) ?: "music",
            artist = extras?.getString(EXTRA_ARTIST) ?: mediaMetadata.artist?.toString(),
            artworkUrl = extras?.getString(EXTRA_ARTWORK_URL) ?: mediaMetadata.artworkUri?.toString(),
            streamUri = extras?.getString(EXTRA_STREAM_URI),
            durationText = extras?.getString(EXTRA_DURATION_TEXT),
            localLosslessBadge = extras?.getString(EXTRA_LOSSLESS_BADGE),
            title = extras?.getString(EXTRA_TITLE),
            album = extras?.getString(EXTRA_ALBUM),
            durationMs = extras?.getLong(EXTRA_DURATION_MS) ?: 0L,
            isAutoplayRecommendation = extras?.getBoolean(EXTRA_AUTOPLAY_REC) ?: false,
        )
    }

    private fun RepeatModeState.toPlayerRepeatMode(): Int = when (this) {
        RepeatModeState.OFF -> Player.REPEAT_MODE_OFF
        RepeatModeState.ALL -> Player.REPEAT_MODE_ALL
        RepeatModeState.ONE -> Player.REPEAT_MODE_ONE
    }

    private fun Int.toRepeatState(): RepeatModeState = when (this) {
        Player.REPEAT_MODE_ALL -> RepeatModeState.ALL
        Player.REPEAT_MODE_ONE -> RepeatModeState.ONE
        else -> RepeatModeState.OFF
    }

    companion object {
        private const val AUTOPLAY_PREFETCH_THRESHOLD = 3
        private const val MAX_DISCOVER_FAILOVER_RETRIES = 3
        private const val EXTRA_VOLUME = "pl_volume"
        private const val EXTRA_PATH = "pl_path"
        private const val EXTRA_NAME = "pl_name"
        private const val EXTRA_SIZE = "pl_size"
        private const val EXTRA_MODIFIED = "pl_modified"
        private const val EXTRA_MIME = "pl_mime"
        private const val EXTRA_CATEGORY = "pl_category"
        private const val EXTRA_ARTIST = "pl_artist"
        private const val EXTRA_ARTWORK_URL = "pl_artwork_url"
        private const val EXTRA_STREAM_URI = "pl_stream_uri"
        private const val EXTRA_DURATION_TEXT = "pl_duration_text"
        private const val EXTRA_TITLE = "pl_title"
        private const val EXTRA_ALBUM = "pl_album"
        private const val EXTRA_DURATION_MS = "pl_duration_ms"
        private const val EXTRA_LOSSLESS_BADGE = "pl_lossless_badge"
        private const val EXTRA_AUTOPLAY_REC = "pl_autoplay_rec"
    }
}
