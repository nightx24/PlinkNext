package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MovieCreation
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PermMedia
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppSettingsState
import com.example.data.model.AppThemeMode
import com.example.data.model.NetworkRouteType
import com.example.ui.PocketLinkViewModel
import com.example.ui.components.PocketLinkTopBar
import com.example.ui.components.RouteBadge
import com.example.ui.components.rememberPressScaleModifier
import com.example.ui.theme.LocalPocketLinkColors
import com.example.ui.theme.LocalPocketLinkDarkTheme
import kotlinx.coroutines.launch

private enum class SettingsPage(val title: String) {
    HUB("Settings"),
    APPEARANCE("Appearance & Theme"),
    NAV_CUSTOMIZATION("Navigation Bar & Dock"),
    DASHBOARD_SHORTCUTS("Dashboard & Shortcuts"),
    FLOATING_SHORTCUTS("Floating Shortcuts"),
    MEDIA_STORAGE("Media & Storage"),
    NOW_PLAYING("Audio & Now Playing"),
    VIDEO_PLAYER("Video Player"),
    CONNECTION_DIAGNOSTICS("Connection & Host"),
}

@Composable
fun SettingsScreen(
    viewModel: PocketLinkViewModel,
    settings: AppSettingsState,
    activeHostLabel: String? = null,
    routeType: NetworkRouteType? = null,
    onOpenHostSetup: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAbout: () -> Unit,
    onDisconnect: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val pageStack = remember { mutableStateListOf(SettingsPage.HUB) }
    var lastTransitionForward by remember { mutableStateOf(true) }
    var showReorderHomeDialog by remember { mutableStateOf(false) }

    val currentPage = pageStack.lastOrNull() ?: SettingsPage.HUB

    fun pushPage(target: SettingsPage) {
        if (currentPage != target) {
            lastTransitionForward = true
            pageStack.add(target)
        }
    }

    fun navigateUp() {
        if (pageStack.size > 1) {
            lastTransitionForward = false
            pageStack.removeAt(pageStack.lastIndex)
        } else {
            onBack?.invoke()
        }
    }

    BackHandler(enabled = pageStack.size > 1) {
        navigateUp()
    }

    Scaffold(
        topBar = {
            PocketLinkTopBar(
                title = currentPage.title,
                subtitle = if (currentPage == SettingsPage.HUB) "Customize look, dock, playback & network" else null,
                onBack = if (pageStack.size > 1 || onBack != null) ({ navigateUp() }) else null,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            AnimatedContent(
                targetState = currentPage,
                transitionSpec = {
                    if (lastTransitionForward) {
                        (slideInHorizontally(animationSpec = tween(260)) { it / 5 } +
                            fadeIn(tween(220)) +
                            scaleIn(initialScale = 0.97f, animationSpec = tween(240))) togetherWith
                            (slideOutHorizontally(animationSpec = tween(220)) { -it / 6 } + fadeOut(tween(180)))
                    } else {
                        (slideInHorizontally(animationSpec = tween(240)) { -it / 5 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(animationSpec = tween(220)) { it / 5 } +
                                fadeOut(tween(180)) +
                                scaleOut(targetScale = 0.97f, animationSpec = tween(200)))
                    }
                },
                label = "settings_category_transition",
                modifier = Modifier
                    .widthIn(max = 620.dp)
                    .fillMaxSize(),
            ) { page ->
                when (page) {
                    SettingsPage.HUB -> SettingsHubContent(
                        viewModel = viewModel,
                        settings = settings,
                        activeHostLabel = activeHostLabel,
                        routeType = routeType,
                        onSelectCategory = ::pushPage,
                        onOpenAbout = onOpenAbout,
                        onDisconnect = onDisconnect,
                    )

                    SettingsPage.APPEARANCE -> AppearanceSettingsContent(
                        viewModel = viewModel,
                        settings = settings,
                        onOpenNavCustomization = { pushPage(SettingsPage.NAV_CUSTOMIZATION) },
                        onOpenFloatingShortcuts = { pushPage(SettingsPage.FLOATING_SHORTCUTS) },
                        onOpenReorderHome = { showReorderHomeDialog = true },
                    )

                    SettingsPage.NAV_CUSTOMIZATION -> NavigationCustomizationContent(
                        viewModel = viewModel,
                        settings = settings,
                        onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                    )

                    SettingsPage.DASHBOARD_SHORTCUTS -> DashboardShortcutsSettingsContent(
                        viewModel = viewModel,
                        settings = settings,
                        onOpenFloatingShortcuts = { pushPage(SettingsPage.FLOATING_SHORTCUTS) },
                        onOpenReorderHome = { showReorderHomeDialog = true },
                    )

                    SettingsPage.FLOATING_SHORTCUTS -> FloatingShortcutsCustomizationContent(
                        viewModel = viewModel,
                        settings = settings,
                        onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                    )

                    SettingsPage.MEDIA_STORAGE -> MediaStorageSettingsContent(
                        viewModel = viewModel,
                        settings = settings,
                        onOpenNowPlaying = { pushPage(SettingsPage.NOW_PLAYING) },
                        onOpenVideoPlayer = { pushPage(SettingsPage.VIDEO_PLAYER) },
                        onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                    )

                    SettingsPage.NOW_PLAYING -> NowPlayingSettingsContent(
                        viewModel = viewModel,
                        settings = settings,
                        onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                    )

                    SettingsPage.VIDEO_PLAYER -> VideoPlayerSettingsContent(
                        viewModel = viewModel,
                        settings = settings,
                        onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                    )

                    SettingsPage.CONNECTION_DIAGNOSTICS -> ConnectionDiagnosticsSettingsContent(
                        activeHostLabel = activeHostLabel,
                        routeType = routeType,
                        onOpenHostSetup = onOpenHostSetup,
                        onOpenDiagnostics = onOpenDiagnostics,
                        onDisconnect = onDisconnect,
                    )
                }
            }
        }
    }

    if (showReorderHomeDialog) {
        HomeSectionOrderDialog(
            currentOrder = settings.homeSectionOrder,
            onDismiss = { showReorderHomeDialog = false },
            onSave = { next ->
                scope.launch { viewModel.settingsRepository.setHomeSectionOrder(next) }
                showReorderHomeDialog = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsHubContent(
    viewModel: PocketLinkViewModel,
    settings: AppSettingsState,
    activeHostLabel: String?,
    routeType: NetworkRouteType?,
    onSelectCategory: (SettingsPage) -> Unit,
    onOpenAbout: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = LocalPocketLinkColors.current
    val isDark = LocalPocketLinkDarkTheme.current
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }

    val themeSummary = when {
        settings.amoled -> "AMOLED"
        else -> settings.themeMode.label
    } + if (settings.useLiquidGlass) " · Glass" else ""

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Quick Personalization & Connection Overview Card
        item {
            StaggeredSettingsSection(visible = revealed, delayMs = 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.linearGradient(
                                if (isDark) {
                                    listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                                        MaterialTheme.colorScheme.surfaceContainerLow,
                                    )
                                } else {
                                    listOf(
                                        Color(0xFFEDF1FF),
                                        Color(0xFFFFFFFF),
                                    )
                                }
                            )
                        )
                        .border(1.dp, colors.cardBorder, RoundedCornerShape(24.dp))
                        .padding(16.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (isDark) Icons.Rounded.DarkMode else Icons.Rounded.LightMode,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(21.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = activeHostLabel ?: "PocketLink Workspace",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "$themeSummary · ${settings.visibleNavigationItems.size} dock tabs",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (routeType != null) {
                                RouteBadge(routeType)
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = !settings.amoled && settings.themeMode == AppThemeMode.LIGHT,
                                onClick = { scope.launch { viewModel.settingsRepository.setThemeMode(AppThemeMode.LIGHT, amoled = false) } },
                                leadingIcon = { Icon(Icons.Rounded.LightMode, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = { Text("Light") },
                            )
                            FilterChip(
                                selected = !settings.amoled && settings.themeMode == AppThemeMode.DARK,
                                onClick = { scope.launch { viewModel.settingsRepository.setThemeMode(AppThemeMode.DARK, amoled = false) } },
                                leadingIcon = { Icon(Icons.Rounded.DarkMode, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = { Text("Dark") },
                            )
                            FilterChip(
                                selected = settings.amoled,
                                onClick = { viewModel.setAmoled(!settings.amoled) },
                                label = { Text("AMOLED") },
                            )
                            FilterChip(
                                selected = settings.useLiquidGlass,
                                onClick = { viewModel.setUseLiquidGlass(!settings.useLiquidGlass) },
                                leadingIcon = { Icon(Icons.Rounded.BlurOn, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                label = { Text("Glass") },
                            )
                        }
                    }
                }
            }
        }

        // PERSONALIZATION SECTION
        item {
            StaggeredSettingsSection(visible = revealed, delayMs = 45) {
                SettingsGroupHeader("Personalization")
            }
        }
        item {
            StaggeredSettingsSection(visible = revealed, delayMs = 70) {
                SettingsCategoryCard(
                    icon = Icons.Rounded.Palette,
                    tint = MaterialTheme.colorScheme.primary,
                    title = "Appearance & Theme",
                    subtitle = "Light, Dark & AMOLED modes, Liquid Glass dock finish",
                    badgeText = themeSummary,
                    onClick = { onSelectCategory(SettingsPage.APPEARANCE) },
                    testTag = "settings_cat_appearance",
                )
            }
        }
        item {
            StaggeredSettingsSection(visible = revealed, delayMs = 100) {
                SettingsCategoryCard(
                    icon = Icons.Rounded.Tune,
                    tint = colors.sky,
                    title = "Navigation Bar & Dock",
                    subtitle = "Reorder floating pills, Music/Play swipe & visibility",
                    badgeText = "${settings.visibleNavigationItems.size} tabs",
                    onClick = { onSelectCategory(SettingsPage.NAV_CUSTOMIZATION) },
                    testTag = "settings_cat_navigation",
                )
            }
        }
        item {
            StaggeredSettingsSection(visible = revealed, delayMs = 130) {
                SettingsCategoryCard(
                    icon = Icons.Rounded.DashboardCustomize,
                    tint = colors.teal,
                    title = "Dashboard & Shortcuts",
                    subtitle = "Home sections order, quick actions & Music pill shortcuts",
                    badgeText = "${settings.floatingShortcuts.size} pinned",
                    onClick = { onSelectCategory(SettingsPage.DASHBOARD_SHORTCUTS) },
                    testTag = "settings_cat_dashboard",
                )
            }
        }

        // MEDIA & STORAGE SECTION
        item {
            StaggeredSettingsSection(visible = revealed, delayMs = 160) {
                SettingsGroupHeader("Media & Storage")
            }
        }
        item {
            StaggeredSettingsSection(visible = revealed, delayMs = 190) {
                SettingsCategoryCard(
                    icon = Icons.Rounded.GraphicEq,
                    tint = colors.violet,
                    title = "Audio & Now Playing",
                    subtitle = "Mini-player, swipe gestures & stream stats",
                    badgeText = if (settings.showMiniPlayer) "Mini On" else "Mini Off",
                    onClick = { onSelectCategory(SettingsPage.NOW_PLAYING) },
                    testTag = "settings_cat_now_playing",
                )
            }
        }
        item {
            StaggeredSettingsSection(visible = revealed, delayMs = 220) {
                SettingsCategoryCard(
                    icon = Icons.Rounded.MovieCreation,
                    tint = colors.cyan,
                    title = "Video Player",
                    subtitle = "Playback speed, fit mode, seek interval & auto-fullscreen",
                    badgeText = "${settings.videoSeekSeconds}s · ${settings.videoDefaultSpeed}×",
                    onClick = { onSelectCategory(SettingsPage.VIDEO_PLAYER) },
                    testTag = "settings_cat_video",
                )
            }
        }
        item {
            StaggeredSettingsSection(visible = revealed, delayMs = 250) {
                SettingsCategoryCard(
                    icon = Icons.Rounded.PermMedia,
                    tint = colors.amber,
                    title = "Media Library & Storage",
                    subtitle = "Install sample Music, Videos & Photos, or clear cache",
                    badgeText = "Cache & Test",
                    onClick = { onSelectCategory(SettingsPage.MEDIA_STORAGE) },
                    testTag = "settings_cat_media",
                )
            }
        }

        // NETWORK & SYSTEM SECTION
        item {
            StaggeredSettingsSection(visible = revealed, delayMs = 280) {
                SettingsGroupHeader("Network & System")
            }
        }
        item {
            StaggeredSettingsSection(visible = revealed, delayMs = 310) {
                SettingsCategoryCard(
                    icon = Icons.Rounded.Wifi,
                    tint = colors.emerald,
                    title = "Connection, Tailscale & Host",
                    subtitle = "Saved servers, VPN hosting guide & troubleshooting logs",
                    badgeText = routeType?.label ?: "Servers",
                    onClick = { onSelectCategory(SettingsPage.CONNECTION_DIAGNOSTICS) },
                    testTag = "settings_cat_connection",
                )
            }
        }
        item {
            StaggeredSettingsSection(visible = revealed, delayMs = 340) {
                SettingsCategoryCard(
                    icon = Icons.Rounded.Info,
                    tint = colors.amber,
                    title = "About PocketLink",
                    subtitle = "Version info, architecture & open-source licenses",
                    onClick = onOpenAbout,
                    testTag = "settings_cat_about",
                )
            }
        }
        item {
            StaggeredSettingsSection(visible = revealed, delayMs = 370) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onDisconnect,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("disconnect_server_btn"),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Disconnect & switch server", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun StaggeredSettingsSection(
    visible: Boolean,
    delayMs: Int,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(240, delayMillis = delayMs)) +
            slideInVertically(
                initialOffsetY = { 24 },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
    ) {
        content()
    }
}

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.1.sp,
        modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingsCategoryCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    badgeText: String? = null,
    onClick: () -> Unit,
    testTag: String,
) {
    val colors = LocalPocketLinkColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val scaleModifier = rememberPressScaleModifier(interactionSource, targetScale = 0.978f)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(scaleModifier)
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, colors.cardBorder, RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!badgeText.isNullOrBlank()) {
                Surface(
                    shape = CircleShape,
                    color = tint.copy(alpha = 0.12f),
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = tint,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
