package com.example.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.data.model.IndexedFile
import com.example.data.network.PocketLinkApi
import com.example.data.store.AppLogger
import io.github.augztn.pocketlink.ssdbridge.LocalHostController
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
) {
    val currentTrack: IndexedFile?
        get() = queue.getOrNull(currentIndex)
}

@OptIn(UnstableApi::class)
class AudioPlaybackController(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var player: ExoPlayer? = null
    private var api: PocketLinkApi? = null
    private var positionJob: Job? = null

    private val _state = MutableStateFlow(AudioPlaybackState())
    val state: StateFlow<AudioPlaybackState> = _state.asStateFlow()

    init {
        LocalHostController.setMediaCommandHandler { action, seekPos ->
            scope.launch {
                when {
                    action.endsWith(".PLAY") -> resume()
                    action.endsWith(".PAUSE") -> pause()
                    action.endsWith(".NEXT") -> next()
                    action.endsWith(".PREVIOUS") -> previous()
                    action.endsWith(".SEEK") && seekPos != null -> seekTo(seekPos)
                    action.endsWith(".STOP") || action.endsWith(".DISMISS") -> stop()
                }
            }
        }
    }

    fun attachApi(pocketLinkApi: PocketLinkApi) {
        if (api === pocketLinkApi) return
        api = pocketLinkApi
        releasePlayer()
    }

    private fun ensurePlayer(pocketLinkApi: PocketLinkApi): ExoPlayer {
        player?.let { return it }
        val dataSourceFactory = OkHttpDataSource.Factory(pocketLinkApi.mediaClient)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val created = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        created.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
                syncNotification(startForeground = isPlaying)
                if (isPlaying) startPositionPolling() else stopPositionPolling()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val buffering = playbackState == Player.STATE_BUFFERING
                val duration = created.duration.takeIf { it > 0 } ?: _state.value.durationMs
                _state.update { it.copy(isBuffering = buffering, durationMs = duration) }
                if (playbackState == Player.STATE_ENDED) {
                    handleTrackEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                AppLogger.error("Audio playback failed", error.message)
                _state.update { it.copy(errorMessage = error.localizedMessage ?: "Playback failed", isPlaying = false, isBuffering = false) }
            }
        })
        player = created
        return created
    }

    fun playQueue(tracks: List<IndexedFile>, startIndex: Int) {
        val activeApi = api ?: return
        if (tracks.isEmpty() || startIndex !in tracks.indices) return
        _state.update {
            it.copy(
                queue = tracks,
                currentIndex = startIndex,
                errorMessage = null,
                routeLabel = activeApi.routeType.label,
            )
        }
        loadAndPlay(tracks[startIndex], activeApi)
    }

    private fun loadAndPlay(track: IndexedFile, activeApi: PocketLinkApi) {
        val exo = ensurePlayer(activeApi)
        val volume = track.volume.takeIf { it != "internal" }
        val url = activeApi.streamUrl(track.path, volume)
        _state.update {
            it.copy(
                streamUrl = url,
                positionMs = 0L,
                durationMs = 0L,
                errorMessage = null,
                isBuffering = true,
            )
        }
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.playbackParameters = PlaybackParameters(_state.value.speed)
        exo.prepare()
        exo.playWhenReady = true
        syncNotification(startForeground = true)
    }

    fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    fun seekTo(positionMs: Long) {
        val exo = player ?: return
        val bounded = positionMs.coerceAtLeast(0L)
        exo.seekTo(bounded)
        _state.update { it.copy(positionMs = bounded) }
        syncNotification(startForeground = false)
    }

    fun setSpeed(speed: Float) {
        _state.update { it.copy(speed = speed) }
        player?.playbackParameters = PlaybackParameters(speed)
    }

    fun toggleShuffle() {
        _state.update { it.copy(shuffleEnabled = !it.shuffleEnabled) }
    }

    fun cycleRepeatMode() {
        _state.update {
            val next = when (it.repeatMode) {
                RepeatModeState.OFF -> RepeatModeState.ALL
                RepeatModeState.ALL -> RepeatModeState.ONE
                RepeatModeState.ONE -> RepeatModeState.OFF
            }
            it.copy(repeatMode = next)
        }
    }

    fun next() {
        val current = _state.value
        val activeApi = api ?: return
        if (current.queue.isEmpty()) return
        val nextIndex = if (current.shuffleEnabled && current.queue.size > 1) {
            (current.queue.indices - current.currentIndex).random()
        } else if (current.currentIndex + 1 < current.queue.size) {
            current.currentIndex + 1
        } else if (current.repeatMode == RepeatModeState.ALL) {
            0
        } else {
            return
        }
        _state.update { it.copy(currentIndex = nextIndex) }
        loadAndPlay(current.queue[nextIndex], activeApi)
    }

    fun previous() {
        val current = _state.value
        val activeApi = api ?: return
        if (current.queue.isEmpty()) return
        if ((player?.currentPosition ?: 0L) > 3500L) {
            seekTo(0L)
            return
        }
        val prevIndex = if (current.currentIndex > 0) {
            current.currentIndex - 1
        } else if (current.repeatMode == RepeatModeState.ALL) {
            current.queue.lastIndex
        } else {
            0
        }
        _state.update { it.copy(currentIndex = prevIndex) }
        loadAndPlay(current.queue[prevIndex], activeApi)
    }

    private fun handleTrackEnded() {
        val current = _state.value
        val activeApi = api ?: return
        when (current.repeatMode) {
            RepeatModeState.ONE -> {
                seekTo(0L)
                player?.play()
            }
            RepeatModeState.ALL -> next()
            RepeatModeState.OFF -> {
                if (current.currentIndex + 1 < current.queue.size) {
                    next()
                } else {
                    _state.update { it.copy(isPlaying = false, positionMs = it.durationMs) }
                    syncNotification(startForeground = false)
                }
            }
        }
    }

    fun stop() {
        stopPositionPolling()
        player?.stop()
        LocalHostController.stopMediaNotification(appContext)
        _state.value = AudioPlaybackState()
    }

    fun releasePlayer() {
        stopPositionPolling()
        player?.release()
        player = null
        LocalHostController.stopMediaNotification(appContext)
        _state.value = AudioPlaybackState()
    }

    private fun startPositionPolling() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (true) {
                val exo = player
                if (exo != null) {
                    val pos = exo.currentPosition.coerceAtLeast(0L)
                    val dur = exo.duration.takeIf { it > 0L } ?: _state.value.durationMs
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

    private fun syncNotification(startForeground: Boolean) {
        val s = _state.value
        val track = s.currentTrack ?: return
        val folder = track.parentPath.substringAfterLast('/', "").ifBlank {
            if (track.volume == "internal") "Internal storage" else track.volume
        }
        LocalHostController.updateMediaNotification(
            context = appContext,
            title = track.name.substringBeforeLast('.'),
            artist = folder,
            playing = s.isPlaying,
            positionMs = s.positionMs,
            durationMs = s.durationMs,
            startForeground = startForeground,
        )
    }
}
