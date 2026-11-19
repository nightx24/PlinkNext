package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.os.Build
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.FileEntry
import com.example.data.model.MediaExtensions
import com.example.data.model.NetworkRouteType
import com.example.media.AudioPlaybackState
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.LocalPocketLinkColors
import com.example.ui.theme.LocalPocketLinkDarkTheme
import com.example.ui.theme.SkyAccent
import com.example.ui.theme.TealAccent
import com.example.ui.theme.VioletAccent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketLinkTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("top_bar_back"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    )
}

@Composable
fun rememberPressScaleModifier(
    interactionSource: MutableInteractionSource,
    targetScale: Float = 0.968f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) targetScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "tactile_press_scale",
    )
    return Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Applies hardware-accelerated GPU blur on Android 12+ (API 31+) when [radius] > 0.dp.
 */
fun Modifier.liquidBackdropBlur(radius: androidx.compose.ui.unit.Dp): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && radius > 0.dp) {
        this.blur(radius = radius, edgeTreatment = BlurredEdgeTreatment.Rectangle)
    } else {
        this
    }

/**
 * Shared frosted/solid glass surface matching LiquidGlassPill from the PocketLink repo.
 * Uses high-opacity frosted optical milk-glass in Light Mode and smoked frost in Dark Mode
 * so underlying scrolling text never clashes with dock or mini-player controls.
 */
@Composable
fun LiquidGlassPill(
    useLiquidGlass: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(32.dp),
    content: @Composable () -> Unit,
) {
    val isDark = LocalPocketLinkDarkTheme.current
    val bgBrush = when {
        isDark && useLiquidGlass -> Brush.verticalGradient(
            listOf(Color(0xEE1D202A), Color(0xF414161E))
        )
        isDark -> Brush.verticalGradient(
            listOf(Color(0xFF1A1B20), Color(0xFF161618))
        )
        useLiquidGlass -> Brush.linearGradient(
            colors = listOf(
                Color(0xF5FFFFFF),
                Color(0xEEF2F5FF),
                Color(0xF3EAF0FF),
            ),
            start = Offset(0f, 0f),
            end = Offset(420f, 320f),
        )
        else -> Brush.verticalGradient(
            listOf(Color(0xFFFFFFFF), Color(0xFFF6F8FE))
        )
    }
    val borderBrush = when {
        isDark && useLiquidGlass -> Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.10f))
        )
        isDark -> Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.09f), Color.White.copy(alpha = 0.07f))
        )
        useLiquidGlass -> Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.98f),
                Color(0xFF8CA0FF).copy(alpha = 0.42f),
                Color(0xFF4760FA).copy(alpha = 0.28f),
            ),
            start = Offset(0f, 0f),
            end = Offset(300f, 260f),
        )
        else -> Brush.verticalGradient(
            listOf(Color(0xFFD2D9EB), Color(0xFFDADFF0))
        )
    }
    val shadowColor = if (isDark) Color.Black else Color(0xFF283C8E)

    Box(
        modifier = modifier
            .shadow(
                elevation = if (isDark) 18.dp else if (useLiquidGlass) 16.dp else 8.dp,
                shape = shape,
                ambientColor = shadowColor.copy(alpha = if (isDark) 0.48f else if (useLiquidGlass) 0.16f else 0.10f),
                spotColor = shadowColor.copy(alpha = if (isDark) 0.56f else if (useLiquidGlass) 0.22f else 0.12f),
            )
            .clip(shape)
            .background(bgBrush)
            .drawWithContent {
                drawContent()
                if (useLiquidGlass) {
                    // Subtle optical specular sheen across the upper glass curvature
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = if (isDark) 0.08f else 0.58f),
                                Color.Transparent,
                            ),
                            startY = 0f,
                            endY = size.height * 0.42f,
                        ),
                        size = Size(size.width, size.height * 0.42f),
                    )
                    if (!isDark) {
                        // Subtle bottom cool refraction rim
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0xFF4760FA).copy(alpha = 0.06f),
                                ),
                                startY = size.height * 0.68f,
                                endY = size.height,
                            ),
                            topLeft = Offset(0f, size.height * 0.68f),
                            size = Size(size.width, size.height * 0.32f),
                        )
                    }
                }
            }
            .border(width = if (!isDark && useLiquidGlass) 1.2.dp else 1.dp, brush = borderBrush, shape = shape),
    ) {
        content()
    }
}

/**
 * Soft atmospheric gradient behind the floating dock / mini player so scrolling content
 * softly dissolves underneath instead of visually overlapping dock controls.
 */
@Composable
fun FloatingDockScrimBackdrop(modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        bg.copy(alpha = 0.55f),
                        bg.copy(alpha = 0.88f),
                        bg.copy(alpha = 0.96f),
                    )
                )
            )
    )
}

/**
 * Signature PocketLink FloatingNavigationDock:
 * - Split glass pills based on user navigationOrder and visibleItems (`home`, `explore`, `files`, `browse`).
 * - Circular Music/Play pill supports:
 *   - Tap: Open Now Playing (or start music)
 *   - Horizontal swipe left/right: Skip Previous / Next track
 *   - Long-press (hold): Expand quick shortcut options (Files, Photos, Documents, Music, Videos, etc.)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FloatingNavigationDock(
    order: List<String>,
    visibleItems: Set<String>,
    selectedTabId: String,
    useLiquidGlass: Boolean,
    isPlaying: Boolean,
    hasMedia: Boolean,
    isVideo: Boolean,
    expandedMenuOpen: Boolean,
    onSelectTab: (String) -> Unit,
    onBrowsePressed: () -> Unit,
    onBrowseLongPress: () -> Unit,
    onSwipeMusicPrevious: () -> Unit,
    onSwipeMusicNext: () -> Unit,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
    onMusicButtonBounds: (Rect) -> Unit = {},
) {
    val activeItems = remember(order, visibleItems) {
        order.filter { it in visibleItems }
    }
    val segments = remember(activeItems) {
        val result = mutableListOf<List<String>>()
        var currentGroup = mutableListOf<String>()
        for (id in activeItems) {
            if (id == "browse") {
                if (currentGroup.isNotEmpty()) {
                    result.add(currentGroup.toList())
                    currentGroup = mutableListOf()
                }
                result.add(listOf("browse"))
            } else {
                currentGroup.add(id)
            }
        }
        if (currentGroup.isNotEmpty()) result.add(currentGroup.toList())
        result
    }

    val containerModifier = if (preview) {
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp)
    } else {
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp)
    }

    Row(
        modifier = containerModifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEach { group ->
            if (group.size == 1 && group.first() == "browse") {
                BrowseMusicDockButton(
                    useLiquidGlass = useLiquidGlass,
                    isPlaying = isPlaying,
                    hasMedia = hasMedia,
                    isVideo = isVideo,
                    expanded = expandedMenuOpen,
                    preview = preview,
                    onTap = onBrowsePressed,
                    onLongPress = onBrowseLongPress,
                    onSwipePrevious = onSwipeMusicPrevious,
                    onSwipeNext = onSwipeMusicNext,
                    onBoundsMeasured = onMusicButtonBounds,
                )
            } else {
                LiquidGlassPill(
                    useLiquidGlass = useLiquidGlass,
                    shape = RoundedCornerShape(32.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        group.forEach { id ->
                            DockTabItem(
                                id = id,
                                selected = selectedTabId == id,
                                onClick = { if (!preview) onSelectTab(id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DockTabItem(
    id: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = when (id) {
        "home" -> "Home"
        "explore" -> "Explore"
        else -> "Files"
    }
    val activeIcon = when (id) {
        "home" -> Icons.Rounded.Home
        "explore" -> Icons.Rounded.Explore
        else -> Icons.Rounded.Folder
    }
    val inactiveIcon = when (id) {
        "home" -> Icons.Outlined.Home
        "explore" -> Icons.Outlined.Explore
        else -> Icons.Outlined.Folder
    }

    val isDark = LocalPocketLinkDarkTheme.current
    val activeColor = MaterialTheme.colorScheme.primary
    val pillBg by animateColorAsState(
        targetValue = if (selected) activeColor.copy(alpha = if (isDark) 0.16f else 0.14f) else Color.Transparent,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "dock_tab_bg",
    )
    val contentTint by animateColorAsState(
        targetValue = when {
            isDark && selected -> Color(0xFF9FB0FF)
            isDark -> Color(0xFFA7AEC2)
            selected -> Color(0xFF3950F5)
            else -> Color(0xFF4D5570)
        },
        animationSpec = tween(200),
        label = "dock_tab_tint",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "dock_tab_scale",
    )

    Box(
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(pillBg)
            .then(
                if (selected) Modifier.border(
                    1.dp,
                    if (isDark) activeColor.copy(alpha = 0.22f) else activeColor.copy(alpha = 0.28f),
                    RoundedCornerShape(26.dp),
                )
                else Modifier
            )
            .semantics { this.selected = selected }
            .clickable(role = Role.Tab, onClick = onClick)
            .testTag("nav_tab_$id"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = if (selected) activeIcon else inactiveIcon,
                contentDescription = label,
                tint = contentTint,
                modifier = Modifier
                    .size(21.dp)
                    .scale(iconScale),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = contentTint,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowseMusicDockButton(
    useLiquidGlass: Boolean,
    isPlaying: Boolean,
    hasMedia: Boolean,
    isVideo: Boolean,
    expanded: Boolean,
    preview: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSwipePrevious: () -> Unit,
    onSwipeNext: () -> Unit,
    onBoundsMeasured: (Rect) -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }
    val pulseTransition = rememberInfiniteTransition(label = "browse_btn_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.72f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "browse_btn_pulse_alpha",
    )
    val dragDirection = when {
        dragOffset.value < -12f -> -1 // swipe left -> next
        dragOffset.value > 12f -> 1   // swipe right -> previous
        else -> 0
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(dragOffset.value.roundToInt(), 0) }
            .size(64.dp)
            .onGloballyPositioned { coords ->
                if (!preview) onBoundsMeasured(coords.boundsInRoot())
            }
            .then(
                if (!preview) {
                    Modifier.pointerInput(Unit) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onHorizontalDrag = { _, dragAmount ->
                                totalDrag += dragAmount
                                scope.launch {
                                    dragOffset.snapTo((dragOffset.value + dragAmount * 0.45f).coerceIn(-36f, 36f))
                                }
                            },
                            onDragEnd = {
                                val triggeredNext = totalDrag < -44f
                                val triggeredPrev = totalDrag > 44f
                                scope.launch {
                                    dragOffset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                }
                                if (triggeredNext) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSwipeNext()
                                } else if (triggeredPrev) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSwipePrevious()
                                }
                            },
                            onDragCancel = {
                                scope.launch { dragOffset.animateTo(0f, spring()) }
                            },
                        )
                    }
                } else Modifier
            ),
    ) {
        val isDark = LocalPocketLinkDarkTheme.current
        val primaryAccentTint = if (isDark) Color(0xFF9FB0FF) else Color(0xFF4760FA)
        val swipeAccentTint = if (isDark) Color(0xFFD8C9FF) else Color(0xFF6D4AFF)
        val closeTint = if (isDark) Color.White else Color(0xFF15192B)
        val activeBorderModifier = when {
            expanded -> Modifier.border(1.5.dp, primaryAccentTint.copy(alpha = 0.65f), CircleShape)
            dragDirection != 0 -> Modifier.border(1.5.dp, LocalPocketLinkColors.current.violet.copy(alpha = 0.78f), CircleShape)
            isPlaying -> Modifier.border(1.5.dp, primaryAccentTint.copy(alpha = pulseAlpha), CircleShape)
            else -> Modifier
        }

        LiquidGlassPill(
            useLiquidGlass = useLiquidGlass,
            shape = CircleShape,
            modifier = Modifier
                .size(64.dp)
                .then(activeBorderModifier),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        enabled = !preview,
                        onClick = onTap,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress()
                        },
                    )
                    .testTag("nav_browse_music_btn"),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = when {
                        expanded -> "close"
                        dragDirection < 0 -> "swipe_next"
                        dragDirection > 0 -> "swipe_prev"
                        isPlaying -> "playing"
                        hasMedia && isVideo -> "video"
                        else -> "music"
                    },
                    transitionSpec = {
                        (scaleIn(initialScale = 0.72f, animationSpec = tween(180)) + fadeIn(tween(150)))
                            .togetherWith(scaleOut(targetScale = 0.72f, animationSpec = tween(130)) + fadeOut(tween(130)))
                    },
                    label = "browse_btn_icon",
                ) { mode ->
                    when (mode) {
                        "close" -> Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close quick menu",
                            tint = closeTint,
                            modifier = Modifier.size(25.dp),
                        )
                        "swipe_next" -> Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = "Next track",
                            tint = swipeAccentTint,
                            modifier = Modifier.size(28.dp),
                        )
                        "swipe_prev" -> Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = "Previous track",
                            tint = swipeAccentTint,
                            modifier = Modifier.size(28.dp),
                        )
                        "playing" -> AnimatedEqualizer(color = primaryAccentTint)
                        "video" -> Icon(
                            imageVector = Icons.Rounded.PlayCircle,
                            contentDescription = "Media player (swipe to skip track, hold for options)",
                            tint = primaryAccentTint,
                            modifier = Modifier.size(26.dp),
                        )
                        else -> Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = "Music player (swipe to skip track, hold for options)",
                            tint = primaryAccentTint,
                            modifier = Modifier.size(25.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AnimatedEqualizer(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "dock_equalizer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2.0).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 760, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "eq_phase",
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        repeat(4) { index ->
            val h = (9f + abs(sin(phase + index * 1.15f)) * 16f).dp
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(h)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}

data class ShortcutDescriptor(
    val id: String,
    val label: String,
    val description: String,
    val icon: ImageVector,
    val tint: Color,
)

object ShortcutCatalog {
    val items = listOf(
        ShortcutDescriptor("explore", "Explore", "Browse all destinations", Icons.Rounded.Explore, IndigoPrimary),
        ShortcutDescriptor("files", "Files", "Browse server folders", Icons.Rounded.Folder, SkyAccent),
        ShortcutDescriptor("music", "Music", "Browse your music library", Icons.Rounded.MusicNote, VioletAccent),
        ShortcutDescriptor("videos", "Videos", "Browse movies and clips", Icons.Rounded.PlayCircle, TealAccent),
        ShortcutDescriptor("photos", "Photos", "Browse your photo collection", Icons.Rounded.PhotoLibrary, AmberAccent),
        ShortcutDescriptor("documents", "Documents", "Browse PDFs and documents", Icons.Rounded.Description, SkyAccent),
        ShortcutDescriptor("favorites", "Favorites", "Open favourite photos", Icons.Rounded.Favorite, AmberAccent),
        ShortcutDescriptor("upload", "Upload", "Choose a file to upload", Icons.Rounded.CloudUpload, EmeraldSuccess),
    )

    fun byId(id: String): ShortcutDescriptor = items.firstOrNull { it.id == id } ?: items.first()
}

/**
 * Unified frosted-glass quick menu triggered by holding the Music dock button
 * (replaces standalone floating FABs / loose floating buttons).
 */
@Composable
fun MusicQuickAccessMenu(
    shortcutIds: List<String>,
    useLiquidGlass: Boolean,
    onSelect: (String) -> Unit,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPocketLinkColors.current
    LiquidGlassPill(
        useLiquidGlass = useLiquidGlass,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
            .widthIn(min = 248.dp, max = 300.dp)
            .testTag("music_quick_access_menu"),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Quick Shortcuts",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .clickable(onClick = onOpenNowPlaying)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "Now Playing",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            shortcutIds.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 46.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(itemTint.copy(alpha = 0.11f))
                                .border(1.dp, itemTint.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                                .clickable { onSelect(item.id) }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("music_menu_shortcut_${item.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                tint = itemTint,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
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

@Composable
fun RouteBadge(routeType: NetworkRouteType, modifier: Modifier = Modifier) {
    val colors = LocalPocketLinkColors.current
    val tint = when (routeType) {
        NetworkRouteType.LOCALHOST -> colors.cyan
        NetworkRouteType.TAILSCALE -> colors.emerald
        NetworkRouteType.HTTPS -> MaterialTheme.colorScheme.primary
        NetworkRouteType.PRIVATE_LAN -> colors.sky
        NetworkRouteType.PUBLIC -> colors.amber
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.14f))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(tint)
        )
        Text(
            text = routeType.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}

@Composable
fun FileIconBadge(
    entry: FileEntry,
    modifier: Modifier = Modifier,
) {
    val (icon, tint) = fileVisualSpec(entry)
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(tint.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

fun fileVisualSpec(entry: FileEntry): Pair<ImageVector, Color> {
    if (entry.isDirectory) return Icons.Rounded.Folder to IndigoPrimary
    val ext = entry.extension
    return when {
        ext in MediaExtensions.photo -> Icons.Rounded.Image to AmberAccent
        ext in MediaExtensions.audio -> Icons.Rounded.MusicNote to VioletAccent
        ext in MediaExtensions.video -> Icons.Rounded.Movie to TealAccent
        ext in MediaExtensions.pdf -> Icons.Rounded.PictureAsPdf to Color(0xFFE05A5A)
        ext in MediaExtensions.archive -> Icons.Rounded.Archive to Color(0xFFD98236)
        ext in MediaExtensions.document -> Icons.Rounded.Description to SkyAccent
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile to Color(0xFF8C95AD)
    }
}

@Composable
fun CapacityProgressBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
        label = "capacity_bar",
    )
    LinearProgressIndicator(
        progress = { animatedFraction },
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(50)),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        strokeCap = StrokeCap.Round,
    )
}

@Composable
fun PersistentMiniPlayer(
    state: AudioPlaybackState,
    visible: Boolean,
    useLiquidGlass: Boolean = true,
    onOpenNowPlaying: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onMiniPlayerBounds: (Rect) -> Unit = {},
) {
    val track = state.currentTrack
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val horizontalDrag = remember { Animatable(0f) }

    AnimatedVisibility(
        visible = visible && track != null,
        enter = slideInVertically(
            initialOffsetY = { it / 2 },
            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        ) + fadeIn(tween(200)),
        exit = slideOutVertically(targetOffsetY = { it / 2 }, animationSpec = tween(180)) + fadeOut(tween(150)),
        modifier = modifier,
    ) {
        if (track != null) {
            val progress = if (state.durationMs > 0L) {
                (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f
            val animatedProgress by animateFloatAsState(
                targetValue = progress,
                animationSpec = tween(durationMillis = 220, easing = LinearEasing),
                label = "mini_player_progress",
            )

            val isDark = LocalPocketLinkDarkTheme.current
            val colors = LocalPocketLinkColors.current
            val pillPrimaryInk = if (isDark) Color.White else Color(0xFF15192B)
            val pillMutedInk = if (isDark) Color.White.copy(alpha = 0.62f) else Color(0xFF565E79)
            val pillControlTint = if (isDark) Color.White.copy(alpha = 0.78f) else Color(0xFF3E455C)
            val pillAccent = if (isDark) Color(0xFFD8C9FF) else colors.violet
            val pillBadgeBg = if (isDark) VioletAccent.copy(alpha = 0.22f) else colors.violet.copy(alpha = 0.14f)

            LiquidGlassPill(
                useLiquidGlass = useLiquidGlass,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp)
                    .onGloballyPositioned { coords ->
                        onMiniPlayerBounds(coords.boundsInRoot())
                    }
                    .offset { IntOffset(horizontalDrag.value.roundToInt(), 0) }
                    .pointerInput(track.path) {
                        var hDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { hDrag = 0f },
                            onHorizontalDrag = { _, delta ->
                                hDrag += delta
                                scope.launch {
                                    horizontalDrag.snapTo((horizontalDrag.value + delta * 0.6f).coerceIn(-64f, 64f))
                                }
                            },
                            onDragEnd = {
                                val next = hDrag < -60f
                                val prev = hDrag > 60f
                                scope.launch {
                                    horizontalDrag.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                }
                                if (next) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onNext()
                                } else if (prev) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onPrevious()
                                }
                            },
                            onDragCancel = { scope.launch { horizontalDrag.animateTo(0f, spring()) } },
                        )
                    }
                    .pointerInput(state.isPlaying) {
                        var vDrag = 0f
                        detectVerticalDragGestures(
                            onDragStart = { vDrag = 0f },
                            onVerticalDrag = { _, delta -> vDrag += delta },
                            onDragEnd = {
                                if (!state.isPlaying && vDrag > 50f) onDismiss()
                            },
                        )
                    }
                    .testTag("mini_player_bar"),
            ) {
                Column(modifier = Modifier.clickable(onClick = onOpenNowPlaying)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(pillBadgeBg),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.isPlaying) {
                                AnimatedEqualizer(
                                    color = pillAccent,
                                    modifier = Modifier.scale(0.75f),
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = pillAccent,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        AnimatedContent(
                            targetState = track.path,
                            transitionSpec = {
                                (fadeIn(tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = tween(220)))
                                    .togetherWith(fadeOut(tween(150)))
                            },
                            label = "mini_player_track_text",
                            modifier = Modifier.weight(1f),
                        ) {
                            Column {
                                Text(
                                    text = track.name.substringBeforeLast('.').replace('_', ' '),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = pillPrimaryInk,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (state.isPlaying) "NOW PLAYING · SWIPE TO SKIP" else "PAUSED · SWIPE TO SKIP",
                                    fontSize = 9.sp,
                                    letterSpacing = 0.9.sp,
                                    color = pillMutedInk,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (state.queue.size > 1) {
                            IconButton(
                                onClick = onPrevious,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SkipPrevious,
                                    contentDescription = "Previous track",
                                    tint = pillControlTint,
                                )
                            }
                        }
                        IconButton(
                            onClick = onTogglePlayPause,
                            modifier = Modifier
                                .size(42.dp)
                                .testTag("mini_player_play_pause"),
                        ) {
                            AnimatedContent(
                                targetState = state.isPlaying,
                                transitionSpec = {
                                    (scaleIn(initialScale = 0.75f, animationSpec = tween(160)) + fadeIn(tween(140)))
                                        .togetherWith(scaleOut(targetScale = 0.75f, animationSpec = tween(120)) + fadeOut(tween(120)))
                                },
                                label = "mini_player_play_icon",
                            ) { playing ->
                                Icon(
                                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (playing) "Pause" else "Play",
                                    tint = pillAccent,
                                )
                            }
                        }
                        IconButton(
                            onClick = onNext,
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("mini_player_next"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = "Next track",
                                tint = pillControlTint,
                            )
                        }
                        if (!state.isPlaying) {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("mini_player_dismiss"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Dismiss mini player",
                                    tint = pillMutedInk,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp),
                        color = colors.violet,
                        trackColor = Color.Transparent,
                    )
                }
            }
        }
    }
}
