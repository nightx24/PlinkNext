package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MovieCreation
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Pinch
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Reorder
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AppSettingsState
import com.example.data.model.AppThemeMode
import com.example.data.model.NetworkRouteType
import com.example.data.model.YtAudioQuality
import com.example.ui.PocketLinkViewModel
import com.example.ui.components.FloatingNavigationDock
import com.example.ui.components.RouteBadge
import com.example.ui.components.ShortcutCatalog
import com.example.ui.components.rememberPressScaleModifier
import com.example.ui.theme.LocalPocketLinkColors
import com.example.ui.theme.LocalPocketLinkDarkTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppearanceSettingsContent(
    viewModel: PocketLinkViewModel,
    settings: AppSettingsState,
    onOpenNavCustomization: () -> Unit,
    onOpenFloatingShortcuts: () -> Unit,
    onOpenReorderHome: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = LocalPocketLinkColors.current

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsSectionCard(title = "Theme & Glass") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AppThemeMode.entries.forEach { mode ->
                            val selected = !settings.amoled && settings.themeMode == mode
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    scope.launch { viewModel.settingsRepository.setThemeMode(mode, amoled = false) }
                                },
                                label = { Text(mode.label) },
                            )
                        }
                        FilterChip(
                            selected = settings.amoled,
                            onClick = { viewModel.setAmoled(!settings.amoled) },
                            label = { Text("AMOLED") },
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    ToggleRow(
                        icon = Icons.Rounded.BlurOn,
                        iconTint = colors.sky,
                        title = "Liquid Glass UI",
                        subtitle = "Frosted glass finish on floating dock, mini-player & shortcut pills",
                        checked = settings.useLiquidGlass,
                        onCheckedChange = { viewModel.setUseLiquidGlass(it) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    ToggleRow(
                        icon = Icons.Rounded.BlurOn,
                        iconTint = colors.teal,
                        title = "Header Blur",
                        subtitle = "Transparent top header bar with customizable frosted blur on or off in the header section only",
                        checked = settings.headerBlurEnabled,
                        switchTestTag = "header_blur_switch",
                        onCheckedChange = { viewModel.setHeaderBlurEnabled(it) },
                    )
                }
            }
        }

        item {
            SettingsSectionCard(title = "Navigation & Music Shortcuts") {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    SettingsActionRow(
                        icon = Icons.Rounded.Tune,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = "Customize Navigation Bar",
                        subtitle = "Reorder or show/hide dock destinations",
                        onClick = onOpenNavCustomization,
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    SettingsActionRow(
                        icon = Icons.Rounded.AutoAwesome,
                        iconTint = colors.violet,
                        title = "Music & Now Playing Shortcuts",
                        subtitle = "${settings.floatingShortcuts.size} pinned destinations · Music pill hold & Now Playing",
                        onClick = onOpenFloatingShortcuts,
                    )
                }
            }
        }

        item {
            SettingsSectionCard(title = "Home Dashboard Sections") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToggleRow(
                        icon = Icons.Rounded.DashboardCustomize,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = "Quick actions",
                        checked = settings.showQuickActions,
                        onCheckedChange = { scope.launch { viewModel.settingsRepository.updateHomeVisibility(quickActions = it) } },
                    )
                    ToggleRow(
                        icon = Icons.Rounded.GridView,
                        iconTint = colors.violet,
                        title = "Continue strip",
                        checked = settings.showLibrary,
                        onCheckedChange = { scope.launch { viewModel.settingsRepository.updateHomeVisibility(library = it) } },
                    )
                    ToggleRow(
                        icon = Icons.Rounded.History,
                        iconTint = colors.teal,
                        title = "Recent files",
                        checked = settings.showRecentFiles,
                        onCheckedChange = { scope.launch { viewModel.settingsRepository.updateHomeVisibility(recentFiles = it) } },
                    )
                    ToggleRow(
                        icon = Icons.Rounded.Search,
                        iconTint = colors.sky,
                        title = "Global search",
                        subtitle = "Show the search entry on Home",
                        checked = settings.showGlobalSearch,
                        onCheckedChange = { scope.launch { viewModel.settingsRepository.updateHomeVisibility(globalSearch = it) } },
                    )
                    ToggleRow(
                        icon = Icons.Rounded.SwapVert,
                        iconTint = colors.emerald,
                        title = "Active transfers",
                        subtitle = "Show live upload & download progress on Home",
                        checked = settings.showActiveTransfers,
                        onCheckedChange = { scope.launch { viewModel.settingsRepository.updateHomeVisibility(activeTransfers = it) } },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    SettingsActionRow(
                        icon = Icons.Rounded.Reorder,
                        iconTint = colors.amber,
                        title = "Reorder Home sections",
                        subtitle = "Choose the order of dashboard cards",
                        onClick = onOpenReorderHome,
                    )
                }
            }
        }
    }
}

@Composable
internal fun DashboardShortcutsSettingsContent(
    viewModel: PocketLinkViewModel,
    settings: AppSettingsState,
    onOpenFloatingShortcuts: () -> Unit,
    onOpenReorderHome: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = LocalPocketLinkColors.current
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsSectionCard(title = "Music & Now Playing Shortcuts") {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    SettingsActionRow(
                        icon = Icons.Rounded.AutoAwesome,
                        iconTint = colors.violet,
                        title = "Customize pinned shortcuts",
                        subtitle = "${settings.floatingShortcuts.size} destinations · Music pill hold & Now Playing",
                        onClick = onOpenFloatingShortcuts,
                    )
                }
            }
        }
        item {
            SettingsSectionCard(title = "Home Dashboard Sections") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToggleRow(
                        icon = Icons.Rounded.DashboardCustomize,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = "Quick actions",
                        checked = settings.showQuickActions,
                        onCheckedChange = { scope.launch { viewModel.settingsRepository.updateHomeVisibility(quickActions = it) } },
                    )
                    ToggleRow(
                        icon = Icons.Rounded.GridView,
                        iconTint = colors.violet,
                        title = "Continue strip",
                        checked = settings.showLibrary,
                        onCheckedChange = { scope.launch { viewModel.settingsRepository.updateHomeVisibility(library = it) } },
                    )
                    ToggleRow(
                        icon = Icons.Rounded.History,
                        iconTint = colors.teal,
                        title = "Recent files",
                        checked = settings.showRecentFiles,
                        onCheckedChange = { scope.launch { viewModel.settingsRepository.updateHomeVisibility(recentFiles = it) } },
                    )
                    ToggleRow(
                        icon = Icons.Rounded.Search,
                        iconTint = colors.sky,
                        title = "Global search",
                        checked = settings.showGlobalSearch,
                        onCheckedChange = { scope.launch { viewModel.settingsRepository.updateHomeVisibility(globalSearch = it) } },
                    )
                    ToggleRow(
                        icon = Icons.Rounded.SwapVert,
                        iconTint = colors.emerald,
                        title = "Active transfers",
                        checked = settings.showActiveTransfers,
                        onCheckedChange = { scope.launch { viewModel.settingsRepository.updateHomeVisibility(activeTransfers = it) } },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    SettingsActionRow(
                        icon = Icons.Rounded.Reorder,
                        iconTint = colors.amber,
                        title = "Reorder Home sections",
                        subtitle = "Set the vertical order of Home cards",
                        onClick = onOpenReorderHome,
                    )
                }
            }
        }
    }
}

@Composable
internal fun NavigationCustomizationContent(
    viewModel: PocketLinkViewModel,
    settings: AppSettingsState,
    onMessage: (String) -> Unit,
) {
    val order = settings.navigationOrder
    val visible = settings.visibleNavigationItems
    var previewSelectedTab by remember(visible) {
        mutableStateOf(
            listOf("home", "explore", "files").firstOrNull { it in visible } ?: "home"
        )
    }

    fun move(index: Int, delta: Int) {
        val target = index + delta
        if (target !in order.indices) return
        val next = order.toMutableList()
        val item = next.removeAt(index)
        next.add(target, item)
        viewModel.setNavigationLayout(next, visible)
    }

    fun toggleVisibility(id: String, show: Boolean) {
        if (id == "home") return
        val next = if (show) visible + id else visible - id
        if (next.size < 2) return
        viewModel.setNavigationLayout(order, next)
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Interactive dock preview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                Text(
                    "Preview updates live as you reorder tabs or switch between Light, Dark, and Liquid Glass.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .border(1.dp, LocalPocketLinkColors.current.cardBorder, RoundedCornerShape(24.dp))
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                ) {
                    FloatingNavigationDock(
                        order = order,
                        visibleItems = visible,
                        selectedTabId = previewSelectedTab,
                        useLiquidGlass = settings.useLiquidGlass,
                        isPlaying = false,
                        hasMedia = true,
                        isVideo = false,
                        expandedMenuOpen = false,
                        onSelectTab = { previewSelectedTab = it },
                        onBrowsePressed = {},
                        onBrowseLongPress = {},
                        onSwipeMusicPrevious = {},
                        onSwipeMusicNext = {},
                        preview = false,
                    )
                }
            }
        }

        item {
            SettingsSectionCard(title = "Navigation Destinations") {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    order.forEachIndexed { idx, id ->
                        val (label, desc, icon) = when (id) {
                            "home" -> Triple("Home", "Always visible · Dashboard & telemetry", Icons.Rounded.Home)
                            "explore" -> Triple("Explore", "Media libraries, search & drives", Icons.Rounded.Explore)
                            "files" -> Triple("Files", "Browse & manage storage", Icons.Rounded.Folder)
                            else -> Triple("Music / Play Pill", "Swipe for tracks · Hold for options", Icons.Rounded.MusicNote)
                        }
                        if (idx > 0) {
                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (id != "home") {
                                Switch(
                                    checked = id in visible,
                                    enabled = id !in visible || visible.size > 2,
                                    onCheckedChange = { toggleVisibility(id, it) },
                                )
                            }
                            IconButton(onClick = { move(idx, -1) }, enabled = idx > 0) {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move up", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { move(idx, 1) }, enabled = idx < order.lastIndex) {
                                Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move down", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = {
                    viewModel.resetNavigationLayout()
                    onMessage("Navigation bar reset to default")
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Reset to Default")
            }
        }
    }
}

@Composable
internal fun FloatingShortcutsCustomizationContent(
    viewModel: PocketLinkViewModel,
    settings: AppSettingsState,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pinned = settings.floatingShortcuts

    fun move(index: Int, delta: Int) {
        val target = index + delta
        if (target !in pinned.indices) return
        val next = pinned.toMutableList()
        val item = next.removeAt(index)
        next.add(target, item)
        scope.launch { viewModel.settingsRepository.setFloatingShortcutConfig(shortcuts = next) }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsSectionCard(title = "Music Button Access") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleRow(
                        icon = Icons.Rounded.Bolt,
                        title = "Hold Music pill for shortcuts",
                        subtitle = "Long-press the Music dock button to open the frosted quick menu (also inside Now Playing)",
                        checked = settings.floatingShortcutLongPressEnabled,
                        onCheckedChange = { scope.launch { viewModel.settingsRepository.setFloatingShortcutConfig(longPressEnabled = it) } },
                    )
                }
            }
        }

        item {
            SettingsSectionCard(title = "Pinned Destinations Order") {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    pinned.forEachIndexed { idx, id ->
                        val item = ShortcutCatalog.byId(id)
                        if (idx > 0) {
                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(item.icon, contentDescription = null, tint = item.tint)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    if (idx == 0) "Primary · ${item.description}" else item.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { move(idx, -1) }, enabled = idx > 0) {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move up", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { move(idx, 1) }, enabled = idx < pinned.lastIndex) {
                                Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move down", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }

        item {
            SettingsSectionCard(title = "Available Destinations") {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    ShortcutCatalog.items.forEachIndexed { idx, item ->
                        val checked = item.id in pinned
                        if (idx > 0) {
                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val next = if (checked) {
                                        if (pinned.size > 1) pinned - item.id else pinned
                                    } else {
                                        pinned + item.id
                                    }
                                    scope.launch { viewModel.settingsRepository.setFloatingShortcutConfig(shortcuts = next) }
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(item.icon, contentDescription = null, tint = item.tint)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(item.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    val next = if (isChecked) pinned + item.id else if (pinned.size > 1) pinned - item.id else pinned
                                    scope.launch { viewModel.settingsRepository.setFloatingShortcutConfig(shortcuts = next) }
                                },
                            )
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = {
                    scope.launch { viewModel.settingsRepository.resetFloatingShortcutConfig() }
                    onMessage("Shortcuts reset to defaults")
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Reset Shortcuts")
            }
        }
    }
}

@Composable
internal fun MediaStorageSettingsContent(
    viewModel: PocketLinkViewModel,
    settings: AppSettingsState,
    onOpenNowPlaying: () -> Unit,
    onOpenVideoPlayer: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val colors = LocalPocketLinkColors.current
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsSectionCard(title = "Playback & Players") {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    SettingsActionRow(
                        icon = Icons.Rounded.GraphicEq,
                        iconTint = colors.violet,
                        title = "Now Playing & mini-player",
                        subtitle = "Mini-player ${if (settings.showMiniPlayer) "enabled" else "hidden"} · swipe gestures",
                        onClick = onOpenNowPlaying,
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    SettingsActionRow(
                        icon = Icons.Rounded.MovieCreation,
                        iconTint = colors.teal,
                        title = "Video player",
                        subtitle = "${settings.videoSeekSeconds}s seek · ${settings.videoDefaultSpeed}× speed · ${settings.videoFitMode}",
                        onClick = onOpenVideoPlayer,
                    )
                }
            }
        }

        item {
            SettingsSectionCard(title = "Testing & Storage Maintenance") {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    SettingsActionRow(
                        icon = Icons.Rounded.LibraryMusic,
                        iconTint = colors.amber,
                        title = "Install sample Music, Videos & Photos",
                        subtitle = "Copy bundled test tracks, videos, photos & docs to this device",
                        onClick = {
                            viewModel.seedSampleMedia(force = true) { result ->
                                result.onSuccess { count ->
                                    onMessage("Added $count sample files (Music, Videos, Pictures & Documents)")
                                }.onFailure { err ->
                                    onMessage(err.message ?: "Could not add sample media")
                                }
                            }
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    SettingsActionRow(
                        icon = Icons.Rounded.CleaningServices,
                        iconTint = colors.cyan,
                        title = "Clear image & temp cache",
                        subtitle = "Free space by removing downloaded thumbnails & temp files",
                        onClick = {
                            viewModel.clearCache { ok ->
                                onMessage(if (ok) "Temporary files and image cache cleared." else "Could not clear cache.")
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun NowPlayingSettingsContent(
    viewModel: PocketLinkViewModel,
    settings: AppSettingsState,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = LocalPocketLinkColors.current
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSectionCard(title = "Audio & Now Playing") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToggleRow(
                        icon = Icons.Rounded.MusicNote,
                        iconTint = colors.violet,
                        title = "Mini-player",
                        subtitle = "Show the compact player above the floating dock while music is playing",
                        checked = settings.showMiniPlayer,
                        onCheckedChange = { scope.launch { viewModel.settingsRepository.updateNowPlayingOptions(miniPlayer = it) } },
                    )
                    ToggleRow(
                        icon = Icons.Rounded.TouchApp,
                        iconTint = colors.sky,
                        title = "Swipe gestures",
                        subtitle = "Swipe down to close; swipe left or right to change tracks",
                        checked = settings.enableNowPlayingGestures,
                        onCheckedChange = { scope.launch { viewModel.settingsRepository.updateNowPlayingOptions(gestures = it) } },
                    )
                    ToggleRow(
                        icon = Icons.Rounded.GraphicEq,
                        iconTint = colors.teal,
                        title = "Show stats for nerds",
                        subtitle = "Show route, queue position and stream details in Now Playing",
                        checked = settings.showStatsForNerds,
                        onCheckedChange = { scope.launch { viewModel.settingsRepository.updateNowPlayingOptions(statsForNerds = it) } },
                    )
                }
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    scope.launch { viewModel.settingsRepository.resetNowPlayingOptions() }
                    onMessage("Now Playing settings reset to defaults")
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Reset to defaults")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoPlayerSettingsContent(
    viewModel: PocketLinkViewModel,
    settings: AppSettingsState,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = LocalPocketLinkColors.current
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(22.dp)),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.MovieCreation, contentDescription = null, tint = colors.teal, modifier = Modifier.size(18.dp))
                        Text("Player HUD Preview", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(112.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF0C0E14))
                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                            .padding(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Sample Video · ${settings.videoFitMode.uppercase()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${settings.videoDefaultSpeed}×",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.teal,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            text = if (settings.videoTapToTogglePlayback) "Tap = Play/Pause · -${settings.videoSeekSeconds}s / +${settings.videoSeekSeconds}s"
                            else "Tap = Toggle HUD · -${settings.videoSeekSeconds}s / +${settings.videoSeekSeconds}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.78f),
                            modifier = Modifier.align(Alignment.Center),
                        )
                        Row(
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = if (settings.videoControlsTimeoutSeconds == 0) "HUD: Always visible" else "HUD auto-hide: ${settings.videoControlsTimeoutSeconds}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.68f),
                            )
                            Text(
                                text = listOfNotNull(
                                    if (settings.videoGesturesEnabled) "Gestures" else null,
                                    if (settings.videoPinchZoomEnabled) "Pinch" else null,
                                    if (settings.videoAutoFullscreen) "Landscape" else null,
                                ).joinToString(" · ").ifEmpty { "Basic controls" },
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF9FB0FF),
                            )
                        }
                    }
                }
            }
        }

        item {
            SettingsSectionCard(title = "Playback & Sizing") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Speed, contentDescription = null, tint = colors.teal, modifier = Modifier.size(18.dp))
                        Text("Default playback speed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                            FilterChip(
                                selected = settings.videoDefaultSpeed == spd,
                                onClick = { scope.launch { viewModel.settingsRepository.updateVideoOptions(defaultSpeed = spd) } },
                                label = { Text("${spd}×") },
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.AspectRatio, contentDescription = null, tint = colors.sky, modifier = Modifier.size(18.dp))
                        Text("Video fit mode", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("contain" to "Fit", "cover" to "Zoom", "fill" to "Stretch").forEach { (mode, label) ->
                            FilterChip(
                                selected = settings.videoFitMode == mode,
                                onClick = { scope.launch { viewModel.settingsRepository.updateVideoOptions(fitMode = mode) } },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        }

        item {
            SettingsSectionCard(title = "Seek, Controls & Gestures") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Seek & double-tap skip interval", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 10, 15, 30).forEach { sec ->
                            FilterChip(
                                selected = settings.videoSeekSeconds == sec,
                                onClick = { scope.launch { viewModel.settingsRepository.updateVideoOptions(seekSeconds = sec) } },
                                label = { Text("${sec}s") },
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Timer, contentDescription = null, tint = colors.amber, modifier = Modifier.size(18.dp))
                        Text("Controls auto-hide delay", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(2, 4, 6, 10, 0).forEach { sec ->
                            FilterChip(
                                selected = settings.videoControlsTimeoutSeconds == sec,
                                onClick = { scope.launch { viewModel.settingsRepository.updateVideoOptions(controlsTimeoutSeconds = sec) } },
                                label = { Text(if (sec == 0) "Never" else "${sec}s") },
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                    ToggleRow(
                        icon = Icons.Rounded.Fullscreen,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = "Start videos in fullscreen",
                        subtitle = "Automatically switch to landscape fullscreen when opening a video",
                        checked = settings.videoAutoFullscreen,
                        onCheckedChange = { scope.launch { viewModel.settingsRepository.updateVideoOptions(autoFullscreen = it) } },
                    )
                    ToggleRow(
                        icon = Icons.Rounded.TouchApp,
                        iconTint = colors.violet,
                        title = "Tap video to toggle play/pause",
                        subtitle = "Instead of only showing/hiding the controls overlay",
                        checked = settings.videoTapToTogglePlayback,
                        onCheckedChange = { scope.launch { viewModel.settingsRepository.updateVideoOptions(tapToTogglePlayback = it) } },
                    )
                    ToggleRow(
                        icon = Icons.Rounded.TouchApp,
                        iconTint = colors.teal,
                        title = "Swipe & double-tap gestures",
                        subtitle = "Swipe left/right for brightness & volume, scrub timeline, or double-tap to seek",
                        checked = settings.videoGesturesEnabled,
                        onCheckedChange = { scope.launch { viewModel.settingsRepository.updateVideoOptions(gesturesEnabled = it) } },
                    )
                    ToggleRow(
                        icon = Icons.Rounded.Pinch,
                        iconTint = colors.sky,
                        title = "Pinch to zoom",
                        subtitle = "Zoom video canvas up to 3.0× with two fingers",
                        checked = settings.videoPinchZoomEnabled,
                        onCheckedChange = { scope.launch { viewModel.settingsRepository.updateVideoOptions(pinchZoomEnabled = it) } },
                    )
                }
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    scope.launch { viewModel.settingsRepository.resetVideoOptions() }
                    onMessage("Video player settings reset to defaults")
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Reset to defaults")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun YtStreamingSettingsContent(
    viewModel: PocketLinkViewModel,
    settings: AppSettingsState,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = LocalPocketLinkColors.current
    val enabled = settings.ytStreamingEnabled

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Surface(
                color = (if (enabled) colors.violet else MaterialTheme.colorScheme.surfaceContainerHighest).copy(alpha = if (enabled) 0.14f else 0.7f),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (enabled) colors.violet.copy(alpha = 0.36f) else colors.cardBorder,
                        RoundedCornerShape(22.dp),
                    ),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToggleRow(
                        icon = Icons.Rounded.LibraryMusic,
                        iconTint = colors.violet,
                        title = "Enable Discover Streaming",
                        subtitle = if (enabled) {
                            "Active · Powered by NewPipe Extractor with charts, search & synced lyrics"
                        } else {
                            "Turned off · Discover hubs & active Discover audio streams are disabled"
                        },
                        checked = enabled,
                        switchTestTag = "yt_streaming_master_switch",
                        onCheckedChange = { next ->
                            scope.launch {
                                viewModel.settingsRepository.updateYtStreamingOptions(enabled = next)
                                onMessage(if (next) "Discover streaming enabled" else "Discover streaming disabled")
                            }
                        },
                    )
                }
            }
        }

        item {
            SettingsSectionCard(title = "Audio Stream Quality (Opus First)") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        YtAudioQuality.entries.forEach { quality ->
                            FilterChip(
                                selected = settings.ytAudioQuality == quality,
                                enabled = enabled,
                                onClick = {
                                    scope.launch {
                                        viewModel.settingsRepository.updateYtStreamingOptions(audioQuality = quality)
                                    }
                                },
                                label = { Text(quality.shortLabel) },
                                modifier = Modifier.testTag("yt_quality_${quality.prefKey}"),
                            )
                        }
                    }
                    Text(
                        text = "${settings.ytAudioQuality.label} — ${settings.ytAudioQuality.detail}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (settings.ytAudioQuality == YtAudioQuality.AUTO) {
                        val effective = viewModel.ytMusicClient.effectiveAudioQuality()
                        Text(
                            text = "Active network tier: ${effective.shortLabel} (Opus prioritized when available)",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.teal,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        item {
            SettingsSectionCard(title = "Playback & Experience") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToggleRow(
                        icon = Icons.Rounded.Lyrics,
                        iconTint = colors.sky,
                        title = "Synced lyrics (LRCLIB)",
                        subtitle = "Fetch time-synced .lrc lyrics and tap lines to seek in Now Playing",
                        checked = settings.ytLyricsEnabled,
                        enabled = enabled,
                        onCheckedChange = {
                            scope.launch { viewModel.settingsRepository.updateYtStreamingOptions(lyricsEnabled = it) }
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    ToggleRow(
                        icon = Icons.Rounded.QueueMusic,
                        iconTint = colors.violet,
                        title = "Auto-queue related radio",
                        subtitle = "Automatically queue similar Discover tracks when starting a song",
                        checked = settings.ytAutoQueueRelated,
                        enabled = enabled,
                        onCheckedChange = {
                            scope.launch { viewModel.settingsRepository.updateYtStreamingOptions(autoQueueRelated = it) }
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    ToggleRow(
                        icon = Icons.Rounded.SkipNext,
                        iconTint = colors.teal,
                        title = "Skip silence",
                        subtitle = "Automatically skip quiet gaps during audio playback",
                        checked = settings.ytSkipSilence,
                        enabled = enabled,
                        onCheckedChange = {
                            scope.launch { viewModel.settingsRepository.updateYtStreamingOptions(skipSilence = it) }
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    ToggleRow(
                        icon = Icons.Rounded.Image,
                        iconTint = colors.amber,
                        title = "Show album artwork",
                        subtitle = "Display remote album covers in Discover lists, mini-player and Now Playing",
                        checked = settings.ytShowArtwork,
                        enabled = enabled,
                        onCheckedChange = {
                            scope.launch { viewModel.settingsRepository.updateYtStreamingOptions(showArtwork = it) }
                        },
                    )
                }
            }
        }

        item {
            SettingsSectionCard(title = "Resolver & Content Region") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Tune, contentDescription = null, tint = colors.teal, modifier = Modifier.size(18.dp))
                        Text("Audio extractor engine", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            "AUTO" to "Auto (NewPipe + Fallback)",
                            "NEWPIPE" to "NewPipe Extractor",
                            "ANDROID_VR" to "Android VR",
                            "IOS" to "iOS Client",
                        ).forEach { (id, label) ->
                            FilterChip(
                                selected = settings.ytResolverProfile.equals(id, ignoreCase = true),
                                enabled = enabled,
                                onClick = {
                                    scope.launch { viewModel.settingsRepository.updateYtStreamingOptions(resolverProfile = id) }
                                },
                                label = { Text(label) },
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Public, contentDescription = null, tint = colors.sky, modifier = Modifier.size(18.dp))
                        Text("Charts & search region", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf("US", "IN", "GB", "JP", "KR", "DE", "BR", "CA").forEach { code ->
                            FilterChip(
                                selected = settings.ytCountryCode.equals(code, ignoreCase = true),
                                enabled = enabled,
                                onClick = {
                                    scope.launch { viewModel.settingsRepository.updateYtStreamingOptions(countryCode = code) }
                                },
                                label = { Text(code) },
                            )
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = {
                    scope.launch { viewModel.settingsRepository.resetYtStreamingOptions() }
                    onMessage("Discover settings reset to defaults")
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("yt_streaming_reset_btn"),
            ) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Reset to defaults")
            }
        }
    }
}

@Composable
internal fun ConnectionDiagnosticsSettingsContent(
    viewModel: PocketLinkViewModel,
    activeHostLabel: String?,
    routeType: NetworkRouteType?,
    onOpenHostSetup: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onDisconnect: () -> Unit,
    onMessage: (String) -> Unit = {},
) {
    val colors = LocalPocketLinkColors.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val tunnelStatus by viewModel.tunnelStatus.collectAsStateWithLifecycle()
    val tunnelConfig by viewModel.tunnelConfig.collectAsStateWithLifecycle()
    val isTunnelActive = tunnelStatus.isActive
    val activeEndpointUrl = tunnelStatus.publicUrl ?: tunnelConfig.activePublicUrl

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!activeHostLabel.isNullOrBlank() || routeType != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, colors.cardBorder, RoundedCornerShape(20.dp))
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(colors.emerald.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.VpnLock, contentDescription = null, tint = colors.emerald)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Active session", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(activeHostLabel ?: "Connected Host", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        if (routeType != null) {
                            RouteBadge(routeType)
                        }
                    }
                }
            }
        }

        item {
            SettingsSectionCard(title = "Cloudflare Tunnel (1-Click Auto)") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToggleRow(
                        icon = Icons.Rounded.Public,
                        iconTint = Color(0xFFF38020),
                        title = "Cloudflare Auto Tunnel",
                        subtitle = if (isTunnelActive) {
                            val raySuffix = tunnelStatus.cloudflareRay?.let { " · Ray $it" }.orEmpty()
                            "${tunnelStatus.state.label}$raySuffix"
                        } else {
                            "Single-click zero-config tunnel — auto-handles auth, token & edge registration"
                        },
                        checked = isTunnelActive,
                        switchTestTag = "settings_cloudflare_auto_switch",
                        onCheckedChange = { enabled: Boolean ->
                            if (enabled) {
                                viewModel.startCloudflareTunnel()
                                onMessage("Cloudflare Tunnel auto-provisioned & starting")
                            } else {
                                viewModel.stopCloudflareTunnel()
                                onMessage("Cloudflare Tunnel stopped")
                            }
                        },
                    )
                    if (activeEndpointUrl.isNotBlank()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        SettingsActionRow(
                            icon = Icons.Rounded.Bolt,
                            iconTint = colors.emerald,
                            title = activeEndpointUrl,
                            subtitle = "Tap to copy auto-configured Cloudflare HTTPS URL",
                            onClick = {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(activeEndpointUrl))
                                onMessage("Copied Cloudflare URL: $activeEndpointUrl")
                            },
                        )
                    }
                }
            }
        }

        item {
            SettingsSectionCard(title = "Server, Tailscale & Host") {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    SettingsActionRow(
                        icon = Icons.Rounded.Dns,
                        iconTint = MaterialTheme.colorScheme.primary,
                        title = "Saved servers",
                        subtitle = "Manage and switch PocketLink connections",
                        onClick = onDisconnect,
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    SettingsActionRow(
                        icon = Icons.Rounded.PhoneAndroid,
                        iconTint = colors.emerald,
                        title = "Host PocketLink & Tailscale guide",
                        subtitle = "Configure password, Tailscale VPN IP, storage & USB drives",
                        onClick = onOpenHostSetup,
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    SettingsActionRow(
                        icon = Icons.Rounded.BugReport,
                        iconTint = colors.amber,
                        title = "Diagnostics & logs",
                        subtitle = "Inspect connection history and export troubleshooting log",
                        onClick = onOpenDiagnostics,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    val border = LocalPocketLinkColors.current.cardBorder
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, border, RoundedCornerShape(20.dp)),
        ) {
            content()
        }
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    checked: Boolean,
    enabled: Boolean = true,
    switchTestTag: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(19.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            modifier = if (switchTestTag != null) Modifier.testTag(switchTestTag) else Modifier,
        )
    }
}

@Composable
internal fun SettingsActionRow(
    icon: ImageVector,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scaleMod = rememberPressScaleModifier(interactionSource, targetScale = 0.985f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(scaleMod)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(iconTint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun HomeSectionOrderDialog(
    currentOrder: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val labels = mapOf(
        "search" to "Global search",
        "quick" to "Quick actions",
        "transfers" to "Active transfers",
        "continue" to "Continue strip",
        "recent" to "Recent files",
    )
    val draft = remember { mutableStateListOf<String>().apply { addAll(currentOrder) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reorder Home sections") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                draft.forEachIndexed { idx, id ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(labels[id] ?: id, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        IconButton(
                            onClick = {
                                if (idx > 0) {
                                    val moved = draft.removeAt(idx)
                                    draft.add(idx - 1, moved)
                                }
                            },
                            enabled = idx > 0,
                        ) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move up", modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = {
                                if (idx < draft.lastIndex) {
                                    val moved = draft.removeAt(idx)
                                    draft.add(idx + 1, moved)
                                }
                            },
                            enabled = idx < draft.lastIndex,
                        ) {
                            Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move down", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(draft.toList()) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    draft.clear()
                    draft.addAll(AppSettingsState.DEFAULT_HOME_SECTION_ORDER)
                }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
