@file:kotlin.OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.augztn.pocketlink.ssdbridge.PocketLinkMediaEngine
import com.example.data.model.AppSettingsState
import com.example.data.model.IndexedFile
import com.example.data.model.formatDurationMs
import com.example.data.network.PocketLinkApi
import com.example.ui.components.GlassPlayerSurface
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.TealAccent
import kotlinx.coroutines.delay
import kotlin.math.abs

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    api: PocketLinkApi,
    video: IndexedFile,
    settings: AppSettingsState,
    onUpdateVideoSettings: (
        controlsTimeoutSeconds: Int?,
        seekSeconds: Int?,
        defaultSpeed: Float?,
        fitMode: String?,
        gesturesEnabled: Boolean?,
        pinchZoomEnabled: Boolean?,
        tapToTogglePlayback: Boolean?,
        autoFullscreen: Boolean?,
    ) -> Unit = { _, _, _, _, _, _, _, _ -> },
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val haptic = LocalHapticFeedback.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    val volume = video.volume.takeIf { it != "internal" }
    val streamUrl = remember(api.baseUrl, video.path, volume) { api.streamUrl(video.path, volume) }

    val exoPlayer = remember(streamUrl) {
        val dsFactory = OkHttpDataSource.Factory(api.mediaClient)
        PocketLinkMediaEngine.buildExoPlayer(
            context = context,
            dataSourceFactory = dsFactory,
            contentType = C.AUDIO_CONTENT_TYPE_MOVIE,
            handleWakeMode = false,
        ).apply {
            setMediaItem(MediaItem.fromUri(streamUrl))
            playbackParameters = PlaybackParameters(settings.videoDefaultSpeed)
            playWhenReady = true
            prepare()
        }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPreviewMs by remember { mutableLongStateOf(0L) }
    var showControls by remember { mutableStateOf(true) }
    var controlsLocked by remember { mutableStateOf(false) }
    var showUnlockPill by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    var customizeSheetOpen by remember { mutableStateOf(false) }
    var isLandscapeFullscreen by remember { mutableStateOf(settings.videoAutoFullscreen) }
    var currentSpeed by remember(settings.videoDefaultSpeed) { mutableFloatStateOf(settings.videoDefaultSpeed) }
    var resizeMode by remember(settings.videoFitMode) {
        mutableIntStateOf(fitModeToResize(settings.videoFitMode))
    }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var hudMessage by remember { mutableStateOf<String?>(null) }
    var hudNonce by remember { mutableIntStateOf(0) }

    fun showHud(msg: String) {
        hudMessage = msg
        hudNonce++
    }

    // Apply & restore fullscreen orientation & system bars
    DisposableEffect(activity, isLandscapeFullscreen) {
        val win = activity?.window
        if (win != null && isLandscapeFullscreen) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowCompat.getInsetsController(win, win.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        } else if (win != null) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowCompat.getInsetsController(win, win.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }

    DisposableEffect(activity) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { win ->
                val attrs = win.attributes
                attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                win.attributes = attrs
                WindowCompat.getInsetsController(win, win.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                durationMs = exoPlayer.duration.coerceAtLeast(0L)
            }

            override fun onPlayerError(error: PlaybackException) {
                errorMessage = error.localizedMessage ?: "Video playback error"
                isBuffering = false
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    androidx.activity.compose.BackHandler(enabled = customizeSheetOpen || speedMenuOpen) {
        customizeSheetOpen = false
        speedMenuOpen = false
    }

    LaunchedEffect(exoPlayer, isPlaying, isScrubbing) {
        while (true) {
            if (!isScrubbing) {
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            }
            durationMs = exoPlayer.duration.coerceAtLeast(0L)
            delay(350L)
        }
    }

    LaunchedEffect(showControls, isPlaying, settings.videoControlsTimeoutSeconds, customizeSheetOpen, speedMenuOpen) {
        if (showControls && isPlaying && settings.videoControlsTimeoutSeconds > 0 && !customizeSheetOpen && !speedMenuOpen) {
            delay(settings.videoControlsTimeoutSeconds * 1000L)
            showControls = false
        }
    }

    LaunchedEffect(hudNonce) {
        if (hudMessage != null) {
            delay(1300L)
            hudMessage = null
        }
    }

    LaunchedEffect(showUnlockPill) {
        if (showUnlockPill) {
            delay(2200L)
            showUnlockPill = false
        }
    }

    LaunchedEffect(currentSpeed) {
        exoPlayer.playbackParameters = PlaybackParameters(currentSpeed)
    }

    val animatedZoom by animateFloatAsState(
        targetValue = zoomScale,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "video_zoom_scale",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
            .pointerInput(settings.videoPinchZoomEnabled, controlsLocked) {
                if (settings.videoPinchZoomEnabled && !controlsLocked) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (abs(zoom - 1f) > 0.015f) {
                            zoomScale = (zoomScale * zoom).coerceIn(1f, 3.0f)
                            showHud("Zoom ${String.format("%.1f", zoomScale)}×")
                        }
                    }
                }
            }
            .pointerInput(
                settings.videoGesturesEnabled,
                settings.videoSeekSeconds,
                settings.videoTapToTogglePlayback,
                controlsLocked,
                customizeSheetOpen,
            ) {
                detectTapGestures(
                    onTap = {
                        if (controlsLocked) {
                            showUnlockPill = true
                        } else if (customizeSheetOpen) {
                            customizeSheetOpen = false
                        } else if (settings.videoTapToTogglePlayback) {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            showHud(if (exoPlayer.isPlaying) "Playing" else "Paused")
                            showControls = true
                        } else {
                            showControls = !showControls
                        }
                    },
                    onDoubleTap = { offset ->
                        if (controlsLocked || !settings.videoGesturesEnabled) return@detectTapGestures
                        val width = containerSize.width.toFloat().coerceAtLeast(1f)
                        val seekMs = settings.videoSeekSeconds * 1000L
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (offset.x < width * 0.45f) {
                            val target = (exoPlayer.currentPosition - seekMs).coerceAtLeast(0L)
                            exoPlayer.seekTo(target)
                            positionMs = target
                            showHud("↺ -${settings.videoSeekSeconds}s")
                        } else if (offset.x > width * 0.55f) {
                            val maxDur = exoPlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                            val target = (exoPlayer.currentPosition + seekMs).coerceAtMost(maxDur)
                            exoPlayer.seekTo(target)
                            positionMs = target
                            showHud("+${settings.videoSeekSeconds}s ↻")
                        } else {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            showHud(if (exoPlayer.isPlaying) "Playing" else "Paused")
                        }
                    },
                )
            }
            .pointerInput(settings.videoGesturesEnabled, controlsLocked, durationMs) {
                if (!settings.videoGesturesEnabled || controlsLocked) return@pointerInput
                var horizontalMode = false
                var verticalMode = false
                var startPosMs = 0L
                var dragTargetMs = 0L
                var startX = 0f
                var cumulativeDx = 0f
                var cumulativeDy = 0f

                detectDragGestures(
                    onDragStart = { startOffset ->
                        horizontalMode = false
                        verticalMode = false
                        startPosMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                        dragTargetMs = startPosMs
                        startX = startOffset.x
                        cumulativeDx = 0f
                        cumulativeDy = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        cumulativeDx += dragAmount.x
                        cumulativeDy += dragAmount.y
                        if (!horizontalMode && !verticalMode) {
                            if (abs(cumulativeDx) > abs(cumulativeDy) * 1.25f && abs(cumulativeDx) > 12f) {
                                horizontalMode = true
                            } else if (abs(cumulativeDy) > abs(cumulativeDx) * 1.25f && abs(cumulativeDy) > 12f) {
                                verticalMode = true
                            }
                        }
                        if (horizontalMode && durationMs > 0L) {
                            val deltaMs = (cumulativeDx / containerSize.width.coerceAtLeast(1) * 90_000L).toLong()
                            dragTargetMs = (startPosMs + deltaMs).coerceIn(0L, durationMs)
                            isScrubbing = true
                            scrubPreviewMs = dragTargetMs
                            val deltaSec = ((dragTargetMs - startPosMs) / 1000L)
                            val sign = if (deltaSec >= 0) "+" else ""
                            showHud("${formatDurationMs(dragTargetMs)} (${sign}${deltaSec}s)")
                        } else if (verticalMode) {
                            val isLeftHalf = startX < containerSize.width * 0.5f
                            val stepDelta = -dragAmount.y / containerSize.height.coerceAtLeast(1).toFloat() * 1.25f
                            if (isLeftHalf && activity?.window != null) {
                                val win = activity.window
                                val attrs = win.attributes
                                val cur = if (attrs.screenBrightness in 0f..1f) attrs.screenBrightness else 0.5f
                                val next = (cur + stepDelta).coerceIn(0.05f, 1f)
                                attrs.screenBrightness = next
                                win.attributes = attrs
                                showHud("Brightness ${(next * 100).toInt()}%")
                            } else if (!isLeftHalf && audioManager != null) {
                                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                val nextFloat = (curVol.toFloat() / maxVol + stepDelta).coerceIn(0f, 1f)
                                val targetVol = (nextFloat * maxVol).toInt().coerceIn(0, maxVol)
                                if (targetVol != curVol) {
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                                }
                                showHud("Volume ${(nextFloat * 100).toInt()}%")
                            }
                        }
                    },
                    onDragEnd = {
                        if (horizontalMode) {
                            exoPlayer.seekTo(dragTargetMs)
                            positionMs = dragTargetMs
                            isScrubbing = false
                        }
                    },
                    onDragCancel = {
                        isScrubbing = false
                    },
                )
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = exoPlayer
                    this.resizeMode = resizeMode
                }
            },
            update = { view ->
                view.resizeMode = resizeMode
            },
            onRelease = { view ->
                view.player = null
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = animatedZoom
                    scaleY = animatedZoom
                },
        )

        if (isBuffering && errorMessage == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = IndigoPrimary,
            )
        }

        errorMessage?.let { err ->
            GlassPlayerSurface(
                shape = RoundedCornerShape(22.dp),
                containerColor = Color(0xFF14161D).copy(alpha = 0.62f),
                accentGlow = IndigoPrimary,
                borderColor = Color.White.copy(alpha = 0.24f),
                blurRadius = 22.dp,
                shadowElevation = 12.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = err,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {
                            errorMessage = null
                            exoPlayer.prepare()
                            exoPlayer.playWhenReady = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = IndigoPrimary),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retry stream")
                    }
                }
            }
        }

        // Gesture / Zoom Live HUD Pill
        AnimatedVisibility(
            visible = hudMessage != null,
            enter = fadeIn() + scaleIn(initialScale = 0.88f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .systemBarsPadding()
                .padding(top = 74.dp),
        ) {
            GlassPlayerSurface(
                shape = RoundedCornerShape(50),
                containerColor = Color(0xFF11131A).copy(alpha = 0.56f),
                accentGlow = TealAccent,
                borderColor = Color.White.copy(alpha = 0.28f),
                blurRadius = 20.dp,
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val icon = when {
                        hudMessage.orEmpty().startsWith("Brightness") -> Icons.Rounded.BrightnessHigh
                        hudMessage.orEmpty().startsWith("Volume") -> Icons.Rounded.VolumeUp
                        hudMessage.orEmpty().startsWith("Zoom") -> Icons.Rounded.ZoomOutMap
                        else -> Icons.Rounded.FastForward
                    }
                    Icon(icon, contentDescription = null, tint = TealAccent, modifier = Modifier.size(17.dp))
                    Text(
                        text = hudMessage.orEmpty(),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Screen Lock Unlock Chip
        AnimatedVisibility(
            visible = controlsLocked && showUnlockPill,
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .systemBarsPadding()
                .padding(bottom = 32.dp),
        ) {
            GlassPlayerSurface(
                shape = RoundedCornerShape(50),
                containerColor = Color(0xFF14161E).copy(alpha = 0.62f),
                accentGlow = TealAccent,
                borderColor = Color.White.copy(alpha = 0.30f),
                blurRadius = 20.dp,
                shadowElevation = 10.dp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        controlsLocked = false
                        showUnlockPill = false
                        showControls = true
                    },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.LockOpen, contentDescription = null, tint = TealAccent, modifier = Modifier.size(18.dp))
                    Text("Controls locked · Tap to unlock", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        AnimatedVisibility(
            visible = showControls && !controlsLocked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xBA090A0F),
                                Color.Transparent,
                                Color.Transparent,
                                Color(0xCC090A0F),
                            )
                        )
                    )
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                // Top Glass Header Bar
                GlassPlayerSurface(
                    shape = RoundedCornerShape(22.dp),
                    containerColor = Color(0xFF121520).copy(alpha = 0.50f),
                    accentGlow = IndigoPrimary,
                    borderColor = Color.White.copy(alpha = 0.24f),
                    blurRadius = 24.dp,
                    shadowElevation = 10.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("top_bar_back")) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = video.name.substringBeforeLast('.'),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${resizeModeLabel(resizeMode)} · ${currentSpeed}× · Seek ${settings.videoSeekSeconds}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.72f),
                                maxLines = 1,
                            )
                        }
                        if (zoomScale > 1.05f) {
                            TextButton(onClick = { zoomScale = 1f }) {
                                Text("Reset ${String.format("%.1f", zoomScale)}×", color = TealAccent)
                            }
                        }
                        IconButton(onClick = {
                            resizeMode = when (resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                            showHud("Aspect: ${resizeModeLabel(resizeMode)}")
                        }) {
                            Icon(Icons.Rounded.AspectRatio, contentDescription = "Fit mode", tint = Color.White)
                        }
                        Box {
                            IconButton(onClick = { speedMenuOpen = true }) {
                                Icon(Icons.Rounded.Speed, contentDescription = "Playback speed", tint = Color.White)
                            }
                            DropdownMenu(expanded = speedMenuOpen, onDismissRequest = { speedMenuOpen = false }) {
                                listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                                    DropdownMenuItem(
                                        text = { Text("${spd}×") },
                                        onClick = {
                                            currentSpeed = spd
                                            speedMenuOpen = false
                                            showHud("Speed ${spd}×")
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { isLandscapeFullscreen = !isLandscapeFullscreen }) {
                            Icon(
                                imageVector = if (isLandscapeFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                contentDescription = "Toggle fullscreen orientation",
                                tint = if (isLandscapeFullscreen) CyanAccent else Color.White,
                            )
                        }
                        IconButton(
                            onClick = { customizeSheetOpen = true },
                            modifier = Modifier.testTag("video_player_customize_btn"),
                        ) {
                            Icon(Icons.Rounded.Tune, contentDescription = "Customize video player", tint = TealAccent)
                        }
                    }
                }

                // Center Transport Buttons
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GlassPlayerSurface(
                            shape = CircleShape,
                            containerColor = Color(0xFF141722).copy(alpha = 0.48f),
                            accentGlow = Color.White,
                            borderColor = Color.White.copy(alpha = 0.26f),
                            blurRadius = 20.dp,
                            shadowElevation = 8.dp,
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .clickable {
                                    val target = (exoPlayer.currentPosition - settings.videoSeekSeconds * 1000L).coerceAtLeast(0L)
                                    exoPlayer.seekTo(target)
                                    positionMs = target
                                    showHud("-${settings.videoSeekSeconds}s")
                                },
                        ) {
                            Icon(Icons.Rounded.FastRewind, contentDescription = "Rewind", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("-${settings.videoSeekSeconds}s", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
                    }

                    GlassPlayerSurface(
                        shape = CircleShape,
                        containerColor = IndigoPrimary.copy(alpha = 0.62f),
                        accentGlow = IndigoPrimary,
                        borderColor = Color.White.copy(alpha = 0.42f),
                        blurRadius = 22.dp,
                        shadowElevation = 14.dp,
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .clickable {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                    ) {
                        AnimatedContent(
                            targetState = isPlaying,
                            transitionSpec = {
                                (scaleIn(initialScale = 0.72f) + fadeIn()).togetherWith(scaleOut(targetScale = 0.72f) + fadeOut())
                            },
                            label = "video_play_pause_icon",
                        ) { playing ->
                            Icon(
                                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(38.dp),
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GlassPlayerSurface(
                            shape = CircleShape,
                            containerColor = Color(0xFF141722).copy(alpha = 0.48f),
                            accentGlow = Color.White,
                            borderColor = Color.White.copy(alpha = 0.26f),
                            blurRadius = 20.dp,
                            shadowElevation = 8.dp,
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .clickable {
                                    val maxDur = exoPlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                                    val target = (exoPlayer.currentPosition + settings.videoSeekSeconds * 1000L).coerceAtMost(maxDur)
                                    exoPlayer.seekTo(target)
                                    positionMs = target
                                    showHud("+${settings.videoSeekSeconds}s")
                                },
                        ) {
                            Icon(Icons.Rounded.FastForward, contentDescription = "Forward", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("+${settings.videoSeekSeconds}s", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
                    }
                }

                // Bottom Scrubbing Glass Panel
                GlassPlayerSurface(
                    shape = RoundedCornerShape(24.dp),
                    containerColor = Color(0xFF121520).copy(alpha = 0.52f),
                    accentGlow = IndigoPrimary,
                    borderColor = Color.White.copy(alpha = 0.24f),
                    blurRadius = 24.dp,
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        val shownPosition = if (isScrubbing) scrubPreviewMs else positionMs
                        val maxSlider = durationMs.coerceAtLeast(1L).toFloat()
                        Slider(
                            value = shownPosition.toFloat().coerceIn(0f, maxSlider),
                            onValueChange = {
                                isScrubbing = true
                                scrubPreviewMs = it.toLong()
                            },
                            onValueChangeFinished = {
                                exoPlayer.seekTo(scrubPreviewMs)
                                positionMs = scrubPreviewMs
                                isScrubbing = false
                            },
                            valueRange = 0f..maxSlider,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = IndigoPrimary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.28f),
                            ),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = {
                                    controlsLocked = true
                                    showUnlockPill = true
                                    showControls = false
                                },
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(Icons.Rounded.Lock, contentDescription = "Lock controls", tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
                            }
                            Text(
                                text = "${formatDurationMs(shownPosition)} / ${formatDurationMs(durationMs)}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${currentSpeed}× · ${resizeModeLabel(resizeMode)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TealAccent,
                                modifier = Modifier.clickable { speedMenuOpen = true },
                            )
                        }
                    }
                }
            }
        }

        // In-Player Live Customization Drawer
        AnimatedVisibility(
            visible = customizeSheetOpen,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.52f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { customizeSheetOpen = false }
                    .systemBarsPadding()
                    .padding(16.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                GlassPlayerSurface(
                    shape = RoundedCornerShape(26.dp),
                    containerColor = Color(0xFF141722).copy(alpha = 0.72f),
                    accentGlow = TealAccent,
                    borderColor = Color.White.copy(alpha = 0.24f),
                    blurRadius = 26.dp,
                    shadowElevation = 16.dp,
                    modifier = Modifier
                        .widthIn(max = 540.dp)
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { },
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Tune, contentDescription = null, tint = TealAccent)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Customize Video Player", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                                Text("Changes apply immediately and save to Settings", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.65f))
                            }
                            IconButton(onClick = { customizeSheetOpen = false }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                            }
                        }

                        Text("Playback speed", style = MaterialTheme.typography.labelLarge, color = Color.White)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                                FilterChip(
                                    selected = currentSpeed == spd,
                                    onClick = {
                                        currentSpeed = spd
                                        onUpdateVideoSettings(null, null, spd, null, null, null, null, null)
                                    },
                                    label = { Text("${spd}×") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = IndigoPrimary,
                                        selectedLabelColor = Color.White,
                                        labelColor = Color.White.copy(alpha = 0.85f),
                                    ),
                                )
                            }
                        }

                        Text("Video fit mode", style = MaterialTheme.typography.labelLarge, color = Color.White)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("contain" to "Fit", "cover" to "Zoom", "fill" to "Stretch").forEach { (mode, label) ->
                                FilterChip(
                                    selected = settings.videoFitMode == mode,
                                    onClick = {
                                        resizeMode = fitModeToResize(mode)
                                        onUpdateVideoSettings(null, null, null, mode, null, null, null, null)
                                    },
                                    label = { Text(label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = IndigoPrimary,
                                        selectedLabelColor = Color.White,
                                        labelColor = Color.White.copy(alpha = 0.85f),
                                    ),
                                )
                            }
                        }

                        Text("Seek step & HUD timeout", style = MaterialTheme.typography.labelLarge, color = Color.White)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(5, 10, 15, 30).forEach { sec ->
                                FilterChip(
                                    selected = settings.videoSeekSeconds == sec,
                                    onClick = { onUpdateVideoSettings(null, sec, null, null, null, null, null, null) },
                                    label = { Text("${sec}s seek") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = TealAccent,
                                        selectedLabelColor = Color(0xFF062024),
                                        labelColor = Color.White.copy(alpha = 0.85f),
                                    ),
                                )
                            }
                            listOf(2, 4, 6, 0).forEach { timeout ->
                                FilterChip(
                                    selected = settings.videoControlsTimeoutSeconds == timeout,
                                    onClick = { onUpdateVideoSettings(timeout, null, null, null, null, null, null, null) },
                                    label = { Text(if (timeout == 0) "Always show" else "${timeout}s hide") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = IndigoPrimary,
                                        selectedLabelColor = Color.White,
                                        labelColor = Color.White.copy(alpha = 0.85f),
                                    ),
                                )
                            }
                        }

                        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

                        PlayerQuickSwitchRow(
                            title = "Swipe brightness, volume & double-tap seek",
                            checked = settings.videoGesturesEnabled,
                            onCheckedChange = { onUpdateVideoSettings(null, null, null, null, it, null, null, null) },
                        )
                        PlayerQuickSwitchRow(
                            title = "Pinch to zoom video canvas",
                            checked = settings.videoPinchZoomEnabled,
                            onCheckedChange = { onUpdateVideoSettings(null, null, null, null, null, it, null, null) },
                        )
                        PlayerQuickSwitchRow(
                            title = "Tap screen to play / pause directly",
                            checked = settings.videoTapToTogglePlayback,
                            onCheckedChange = { onUpdateVideoSettings(null, null, null, null, null, null, it, null) },
                        )
                        PlayerQuickSwitchRow(
                            title = "Auto-landscape fullscreen",
                            checked = settings.videoAutoFullscreen,
                            onCheckedChange = {
                                isLandscapeFullscreen = it
                                onUpdateVideoSettings(null, null, null, null, null, null, null, it)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerQuickSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun fitModeToResize(fitMode: String): Int = when (fitMode) {
    "cover" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

private fun resizeModeLabel(resizeMode: Int): String = when (resizeMode) {
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
    else -> "Fit"
}
