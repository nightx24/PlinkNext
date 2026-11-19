package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppSettingsState
import com.example.data.model.formatDurationMs
import com.example.media.AudioPlaybackController
import com.example.media.AudioPlaybackState
import com.example.media.RepeatModeState
import com.example.ui.components.AnimatedEqualizer
import com.example.ui.components.ShortcutCatalog
import com.example.ui.theme.LocalPocketLinkColors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private data class ArtworkAtmosphere(
    val primary: Color,
    val secondary: Color,
    val deepBase: Color,
)

private val AppleMusicPalettes = listOf(
    ArtworkAtmosphere(
        primary = Color(0xFFFA2D48),
        secondary = Color(0xFF7B2CBF),
        deepBase = Color(0xFF140B14),
    ),
    ArtworkAtmosphere(
        primary = Color(0xFF2563EB),
        secondary = Color(0xFF7C3AED),
        deepBase = Color(0xFF0B1020),
    ),
    ArtworkAtmosphere(
        primary = Color(0xFF0EA5E9),
        secondary = Color(0xFF10B981),
        deepBase = Color(0xFF09161D),
    ),
    ArtworkAtmosphere(
        primary = Color(0xFFF59E0B),
        secondary = Color(0xFFDC2626),
        deepBase = Color(0xFF1B100A),
    ),
    ArtworkAtmosphere(
        primary = Color(0xFFEC4899),
        secondary = Color(0xFF4F46E5),
        deepBase = Color(0xFF160B1E),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playbackState: AudioPlaybackState,
    audioController: AudioPlaybackController,
    showStatsForNerds: Boolean,
    enableGestures: Boolean = true,
    shortcuts: List<String> = AppSettingsState.DEFAULT_FLOATING_SHORTCUTS,
    onSelectShortcut: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    val track = playbackState.currentTrack
    var showQueueSheet by remember { mutableStateOf(false) }
    var showShortcutsSheet by remember { mutableStateOf(false) }
    var showSpeedStrip by remember { mutableStateOf(false) }
    var scrubbingPosition by remember { mutableStateOf<Float?>(null) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val dismissOffsetY = remember { Animatable(0f) }
    val artworkOffsetX = remember { Animatable(0f) }
    val colors = LocalPocketLinkColors.current

    BackHandler(enabled = showQueueSheet || showShortcutsSheet || showSpeedStrip) {
        showQueueSheet = false
        showShortcutsSheet = false
        showSpeedStrip = false
    }

    val folderName = remember(track?.parentPath, track?.volume) {
        val raw = track?.parentPath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        raw ?: if (track?.volume == "internal" || track?.volume == null) "PocketLink Library" else track.volume
    }

    val targetAtmosphere = remember(track?.path) {
        val seed = abs((track?.name ?: track?.path ?: "default").hashCode())
        AppleMusicPalettes[seed % AppleMusicPalettes.size]
    }
    val glowPrimary by animateColorAsState(
        targetValue = targetAtmosphere.primary,
        animationSpec = tween(700),
        label = "am_glow_primary",
    )
    val glowSecondary by animateColorAsState(
        targetValue = targetAtmosphere.secondary,
        animationSpec = tween(700),
        label = "am_glow_secondary",
    )
    val baseColor by animateColorAsState(
        targetValue = targetAtmosphere.deepBase,
        animationSpec = tween(700),
        label = "am_base_color",
    )

    val inkPrimary = Color.White
    val inkSecondary = Color.White.copy(alpha = 0.68f)
    val inkMuted = Color.White.copy(alpha = 0.50f)
    val frostedPillBg = Color.White.copy(alpha = 0.14f)

    val dragProgress = (dismissOffsetY.value / 220f).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val s = 1f - dragProgress * 0.07f
                scaleX = s
                scaleY = s
                translationY = dismissOffsetY.value * 0.4f
                shape = RoundedCornerShape((dragProgress * 28f).dp)
                clip = dragProgress > 0.01f
            }
            .background(baseColor),
    ) {
        // Apple Music dynamic blurred-color atmospheric mesh backdrop
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        glowPrimary.copy(alpha = 0.42f),
                        glowSecondary.copy(alpha = 0.24f),
                        baseColor.copy(alpha = 0.96f),
                    ),
                ),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glowPrimary.copy(alpha = 0.48f), Color.Transparent),
                    center = Offset(size.width * 0.22f, size.height * 0.22f),
                    radius = size.maxDimension * 0.62f,
                ),
                center = Offset(size.width * 0.22f, size.height * 0.22f),
                radius = size.maxDimension * 0.62f,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glowSecondary.copy(alpha = 0.42f), Color.Transparent),
                    center = Offset(size.width * 0.85f, size.height * 0.48f),
                    radius = size.maxDimension * 0.58f,
                ),
                center = Offset(size.width * 0.85f, size.height * 0.48f),
                radius = size.maxDimension * 0.58f,
            )
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.10f),
                        Color.Black.copy(alpha = 0.28f),
                        Color.Black.copy(alpha = 0.56f),
                    ),
                ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .then(
                    if (enableGestures) {
                        Modifier.pointerInput(Unit) {
                            var totalY = 0f
                            detectVerticalDragGestures(
                                onDragStart = { totalY = 0f },
                                onVerticalDrag = { _, delta ->
                                    if (totalY + delta >= 0f) {
                                        totalY += delta
                                        scope.launch { dismissOffsetY.snapTo((totalY * 0.55f).coerceIn(0f, 220f)) }
                                    }
                                },
                                onDragEnd = {
                                    if (totalY > 120f) {
                                        onBack()
                                    } else {
                                        scope.launch { dismissOffsetY.animateTo(0f, spring()) }
                                    }
                                },
                                onDragCancel = {
                                    scope.launch { dismissOffsetY.animateTo(0f, spring()) }
                                },
                            )
                        }
                    } else Modifier
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Apple Music top grabber pill
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .width(38.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.36f)),
            )

            // Minimal translucent top header bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onBack)
                        .testTag("now_playing_minimize_btn"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(frostedPillBg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Minimize player",
                            tint = inkPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                ) {
                    Text(
                        text = folderName.uppercase(),
                        fontSize = 11.sp,
                        letterSpacing = 1.3.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable { showShortcutsSheet = true }
                        .testTag("now_playing_shortcuts_btn"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(frostedPillBg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.GridView,
                            contentDescription = "Quick shortcuts",
                            tint = inkPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (track == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No track selected",
                        style = MaterialTheme.typography.titleMedium,
                        color = inkSecondary,
                    )
                }
                return@Column
            }

            val duration = playbackState.durationMs.coerceAtLeast(1L).toFloat()
            val sliderValue = scrubbingPosition ?: playbackState.positionMs.toFloat().coerceIn(0f, duration)
            val isScrubbing = scrubbingPosition != null
            val artworkScale by animateFloatAsState(
                targetValue = if (playbackState.isPlaying) 1f else 0.87f,
                animationSpec = spring(
                    dampingRatio = 0.72f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "am_artwork_scale",
            )
            val artworkShadowDp by animateDpAsState(
                targetValue = if (playbackState.isPlaying) 28.dp else 8.dp,
                animationSpec = tween(320),
                label = "am_artwork_shadow",
            )

            Column(
                modifier = Modifier
                    .widthIn(max = 500.dp)
                    .fillMaxSize()
                    .padding(horizontal = 26.dp)
                    .padding(bottom = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Hero Square Artwork with Apple Music play/pause scale physics
                Box(
                    modifier = Modifier
                        .weight(1f, fill = true)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = track.path,
                        transitionSpec = {
                            (scaleIn(initialScale = 0.92f) + fadeIn(tween(240))) togetherWith
                                (scaleOut(targetScale = 0.92f) + fadeOut(tween(180)))
                        },
                        label = "am_artwork_transition",
                    ) { _ ->
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(artworkOffsetX.value.roundToInt(), 0) }
                                .fillMaxWidth(0.92f)
                                .aspectRatio(1f)
                                .graphicsLayer {
                                    scaleX = artworkScale
                                    scaleY = artworkScale
                                }
                                .shadow(
                                    elevation = artworkShadowDp,
                                    shape = RoundedCornerShape(16.dp),
                                    ambientColor = Color.Black.copy(alpha = 0.75f),
                                    spotColor = glowPrimary.copy(alpha = 0.55f),
                                )
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(
                                            glowPrimary,
                                            glowSecondary,
                                            baseColor.copy(alpha = 0.92f),
                                        ),
                                    )
                                )
                                .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
                                .then(
                                    if (enableGestures) {
                                        Modifier.pointerInput(track.path) {
                                            var totalX = 0f
                                            detectHorizontalDragGestures(
                                                onDragStart = { totalX = 0f },
                                                onHorizontalDrag = { _, delta ->
                                                    totalX += delta
                                                    scope.launch {
                                                        artworkOffsetX.snapTo((totalX * 0.45f).coerceIn(-90f, 90f))
                                                    }
                                                },
                                                onDragEnd = {
                                                    val goNext = totalX < -80f
                                                    val goPrev = totalX > 80f
                                                    scope.launch {
                                                        artworkOffsetX.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                                    }
                                                    if (goNext) {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        audioController.next()
                                                    } else if (goPrev) {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        audioController.previous()
                                                    }
                                                },
                                                onDragCancel = { scope.launch { artworkOffsetX.animateTo(0f, spring()) } },
                                            )
                                        }
                                    } else Modifier
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            // Subtle Apple-style soft radial sheen over album square
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                                        center = Offset(size.width * 0.25f, size.height * 0.20f),
                                        radius = size.width * 0.75f,
                                    ),
                                    center = Offset(size.width * 0.25f, size.height * 0.20f),
                                    radius = size.width * 0.75f,
                                )
                            }
                            if (playbackState.isPlaying) {
                                AnimatedEqualizer(
                                    color = Color.White.copy(alpha = 0.94f),
                                    modifier = Modifier.size(64.dp),
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.92f),
                                    modifier = Modifier.size(92.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Apple Music Left-Aligned Title / Folder Subtitle + Frosted Right Pills
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.name.substringBeforeLast('.').replace('_', ' '),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.3).sp,
                                ),
                                color = inkPrimary,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee(),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "$folderName · ${playbackState.routeLabel.ifBlank { "Private Stream" }}",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = inkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            playbackState.errorMessage?.let { err ->
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFFF8A8A),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        // Compact frosted playback-speed pill
                        Surface(
                            onClick = { showSpeedStrip = !showSpeedStrip },
                            shape = CircleShape,
                            color = if (showSpeedStrip || playbackState.speed != 1.0f) {
                                Color.White.copy(alpha = 0.24f)
                            } else {
                                frostedPillBg
                            },
                            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.20f)),
                        ) {
                            Text(
                                text = "${playbackState.speed}×",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = inkPrimary,
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                            )
                        }
                    }

                    // Animated expandable frosted speed strip
                    AnimatedVisibility(
                        visible = showSpeedStrip,
                        enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(150)) + fadeOut(tween(150)),
                    ) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)) { spd ->
                                val selected = playbackState.speed == spd
                                Surface(
                                    onClick = {
                                        audioController.setSpeed(spd)
                                        showSpeedStrip = false
                                    },
                                    shape = RoundedCornerShape(50),
                                    color = if (selected) Color.White else frostedPillBg,
                                ) {
                                    Text(
                                        text = "${spd}×",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) Color.Black else Color.White,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                    )
                                }
                            }
                        }
                    }

                    // Signature Apple Music Translucent Capsule Scrubber Bar
                    val trackFormat = track.name.substringAfterLast('.', "AUDIO").uppercase()
                    AppleMusicScrubber(
                        positionMs = sliderValue.toLong(),
                        durationMs = playbackState.durationMs,
                        isScrubbing = isScrubbing,
                        badgeLabel = trackFormat,
                        onScrub = { newPos -> scrubbingPosition = newPos.toFloat() },
                        onScrubEnd = {
                            scrubbingPosition?.let { audioController.seekTo(it.toLong()) }
                            scrubbingPosition = null
                        },
                    )

                    // Pure Crisp White Apple Music Transport Controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(62.dp)
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = Color.White, bounded = true),
                                    onClick = { audioController.previous() },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipPrevious,
                                contentDescription = "Previous",
                                tint = inkPrimary,
                                modifier = Modifier.size(44.dp),
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(82.dp)
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = Color.White, bounded = true),
                                    onClick = { audioController.togglePlayPause() },
                                )
                                .testTag("now_playing_play_pause_btn"),
                            contentAlignment = Alignment.Center,
                        ) {
                            AnimatedContent(
                                targetState = playbackState.isPlaying,
                                transitionSpec = {
                                    (scaleIn(initialScale = 0.78f, animationSpec = tween(160)) + fadeIn(tween(140)))
                                        .togetherWith(scaleOut(targetScale = 0.78f, animationSpec = tween(120)) + fadeOut(tween(120)))
                                },
                                label = "am_play_pause_icon",
                            ) { playing ->
                                Icon(
                                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (playing) "Pause" else "Play",
                                    tint = inkPrimary,
                                    modifier = Modifier.size(56.dp),
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(62.dp)
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = Color.White, bounded = true),
                                    onClick = { audioController.next() },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = "Next",
                                tint = inkPrimary,
                                modifier = Modifier.size(44.dp),
                            )
                        }
                    }

                    if (showStatsForNerds) {
                        Surface(
                            color = Color.White.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.16f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    "STREAM DIAGNOSTICS",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.1.sp,
                                    color = glowPrimary.copy(alpha = 0.95f),
                                )
                                Text(
                                    "Route: ${playbackState.routeLabel} · Track ${playbackState.currentIndex + 1}/${playbackState.queue.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = inkPrimary,
                                )
                                Text(
                                    "Original byte stream · $trackFormat",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = inkSecondary,
                                )
                            }
                        }
                    }

                    // Bottom Apple Music utility row (Shuffle · Speed · Repeat · Up Next Queue)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppleUtilityIconButton(
                            active = playbackState.shuffleEnabled,
                            onClick = { audioController.toggleShuffle() },
                        ) {
                            Icon(
                                Icons.Rounded.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (playbackState.shuffleEnabled) inkPrimary else inkMuted,
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        AppleUtilityIconButton(
                            active = playbackState.repeatMode != RepeatModeState.OFF,
                            onClick = { audioController.cycleRepeatMode() },
                        ) {
                            Icon(
                                imageVector = if (playbackState.repeatMode == RepeatModeState.ONE) {
                                    Icons.Rounded.RepeatOne
                                } else {
                                    Icons.Rounded.Repeat
                                },
                                contentDescription = "Repeat",
                                tint = if (playbackState.repeatMode != RepeatModeState.OFF) inkPrimary else inkMuted,
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        AppleUtilityIconButton(
                            active = showQueueSheet,
                            onClick = { showQueueSheet = true },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = "Up next queue",
                                tint = if (showQueueSheet) inkPrimary else inkSecondary,
                                modifier = Modifier.size(23.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showQueueSheet) {
        ModalBottomSheet(
            onDismissRequest = { showQueueSheet = false },
            containerColor = baseColor.copy(alpha = 0.97f),
            contentColor = Color.White,
        ) {
            Text(
                "Playing Next (${playbackState.queue.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(playbackState.queue) { idx, item ->
                    val isCurrent = idx == playbackState.currentIndex
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                audioController.playQueue(playbackState.queue, idx)
                                showQueueSheet = false
                            },
                        color = if (isCurrent) glowPrimary.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                if (isCurrent) Icons.Rounded.GraphicEq else Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = if (isCurrent) glowPrimary else Color.White.copy(alpha = 0.65f),
                            )
                            Text(
                                item.name.substringBeforeLast('.').replace('_', ' '),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showShortcutsSheet) {
        ModalBottomSheet(onDismissRequest = { showShortcutsSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Quick Destinations",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    "Jump directly to any destination while music continues playing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                shortcuts.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        pair.forEach { id ->
                            val item = ShortcutCatalog.byId(id)
                            val itemTint = when (item.id) {
                                "explore" -> MaterialTheme.colorScheme.primary
                                "files", "documents" -> colors.sky
                                "music" -> colors.violet
                                "videos" -> colors.teal
                                "photos", "favorites" -> colors.amber
                                "upload" -> colors.emerald
                                else -> item.tint
                            }
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        showShortcutsSheet = false
                                        onSelectShortcut(item.id)
                                    }
                                    .testTag("now_playing_shortcut_${item.id}"),
                                color = itemTint.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, itemTint.copy(alpha = 0.25f)),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(item.icon, contentDescription = null, tint = itemTint)
                                    Text(
                                        item.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                        if (pair.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppleMusicScrubber(
    positionMs: Long,
    durationMs: Long,
    isScrubbing: Boolean,
    badgeLabel: String,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    val rawFraction = (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val barHeight by animateDpAsState(
        targetValue = if (isScrubbing) 10.dp else 6.dp,
        animationSpec = tween(140, easing = FastOutSlowInEasing),
        label = "am_scrubber_height",
    )
    var barWidthPx by remember { mutableStateOf(1f) }
    val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .onSizeChanged { barWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        val frac = (offset.x / barWidthPx).coerceIn(0f, 1f)
                        onScrub((frac * safeDuration).toLong())
                        onScrubEnd()
                    }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val frac = (change.position.x / barWidthPx).coerceIn(0f, 1f)
                            onScrub((frac * safeDuration).toLong())
                        },
                        onDragEnd = { onScrubEnd() },
                        onDragCancel = { onScrubEnd() },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.22f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(rawFraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = if (isScrubbing) 1f else 0.86f)),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDurationMs(positionMs),
                style = TextStyle(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                    color = Color.White.copy(alpha = if (isScrubbing) 0.95f else 0.64f),
                ),
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color.White.copy(alpha = 0.14f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.78f),
                        modifier = Modifier.size(11.dp),
                    )
                    Text(
                        text = badgeLabel,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        color = Color.White.copy(alpha = 0.80f),
                    )
                }
            }
            Text(
                text = if (durationMs > 0L) "-${formatDurationMs(remainingMs)}" else "0:00",
                style = TextStyle(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                    color = Color.White.copy(alpha = if (isScrubbing) 0.95f else 0.64f),
                ),
            )
        }
    }
}

@Composable
private fun AppleUtilityIconButton(
    active: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) Color.White.copy(alpha = 0.20f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
