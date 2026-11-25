package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AddToQueue
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppSettingsState
import com.example.data.model.AppThemeMode
import com.example.data.model.HomeActivityItem
import com.example.data.model.HostSystemTelemetry
import com.example.data.model.MediaIndexStatus
import com.example.data.model.RecentFile
import com.example.data.model.ServerProfile
import com.example.data.model.StorageVolume
import com.example.data.model.TransferDirection
import com.example.data.model.TransferItem
import com.example.data.model.TransferStatus
import com.example.data.model.formatBytes
import com.example.data.network.PocketLinkApi
import com.example.media.TransferManager
import com.example.ui.components.CapacityProgressBar
import com.example.ui.components.FileIconBadge
import com.example.ui.components.LiquidGlassPill
import com.example.ui.components.RouteBadge
import com.example.ui.components.rememberPressScaleModifier
import com.example.ui.theme.LocalPocketLinkColors
import com.example.ui.theme.LocalPocketLinkDarkTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeDashboardScreen(
    api: PocketLinkApi,
    profile: ServerProfile?,
    settings: AppSettingsState,
    transfers: List<TransferItem>,
    transferManager: TransferManager,
    onSwitchServer: () -> Unit,
    onOpenHostSetup: () -> Unit,
    onOpenFiles: (volume: String?, path: String) -> Unit,
    onOpenPhotos: (favoritesOnly: Boolean) -> Unit,
    onOpenMusic: () -> Unit,
    onOpenYtMusic: () -> Unit = {},
    onOpenVideos: () -> Unit,
    onOpenDocuments: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenTransfers: () -> Unit,
    onOpenSettings: () -> Unit,
    onUploadFiles: () -> Unit,
    onOpenRecentFile: (RecentFile) -> Unit,
    onOpenActivityItem: (HomeActivityItem) -> Unit,
    onSaveQuickActions: (List<String>) -> Unit,
    onQuickThemeChange: ((AppThemeMode, Boolean) -> Unit)? = null,
    onOpenDrawer: () -> Unit = {},
    onHostSummaryChanged: (Boolean?, Long?, HostSystemTelemetry?, List<StorageVolume>) -> Unit = { _, _, _, _ -> },
) {
    val scope = rememberCoroutineScope()

    var online by remember { mutableStateOf<Boolean?>(null) }
    var latencyMs by remember { mutableStateOf<Long?>(null) }
    var lastCheckedMs by remember { mutableStateOf<Long?>(null) }
    var telemetry by remember { mutableStateOf<HostSystemTelemetry?>(null) }
    var volumes by remember { mutableStateOf<List<StorageVolume>>(emptyList()) }
    var recentFiles by remember { mutableStateOf<List<RecentFile>>(emptyList()) }
    var indexStatus by remember { mutableStateOf<MediaIndexStatus?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    var showServerSwitcher by remember { mutableStateOf(false) }
    var showQuickActionsEditor by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RecentFile?>(null) }
    var deleteTarget by remember { mutableStateOf<RecentFile?>(null) }

    suspend fun loadAll() = coroutineScope {
        refreshing = true
        val healthDef = async { runCatching { api.checkHealth() } }
        val telemetryDef = async { runCatching { api.systemTelemetry() } }
        val volumesDef = async { runCatching { api.listStorageVolumes() } }
        val indexDef = async { runCatching { api.mediaIndexStatus() } }
        val recentDef = async { runCatching { api.listRecentFiles(limit = 8) } }

        healthDef.await()
            .onSuccess { ms ->
                online = true
                latencyMs = ms
            }
            .onFailure {
                online = false
                latencyMs = null
            }
        lastCheckedMs = System.currentTimeMillis()
        telemetryDef.await().onSuccess { telemetry = it }
        volumesDef.await().onSuccess { volumes = it }
        indexDef.await().onSuccess { indexStatus = it }
        recentDef.await().onSuccess { recentFiles = it }
        onHostSummaryChanged(online, latencyMs, telemetry, volumes)
        refreshing = false
    }

    LaunchedEffect(api.baseUrl) {
        loadAll()
        while (true) {
            delay(5000L)
            coroutineScope {
                val telDef = async { runCatching { api.systemTelemetry() } }
                val idxDef = if (indexStatus?.isScanning == true) {
                    async { runCatching { api.mediaIndexStatus() } }
                } else null
                telDef.await().onSuccess {
                    telemetry = it
                    onHostSummaryChanged(online, latencyMs, telemetry, volumes)
                }
                idxDef?.await()?.onSuccess { indexStatus = it }
            }
        }
    }

    val activeTransfers = remember(transfers) {
        transfers.filter { it.status == TransferStatus.RUNNING || it.status == TransferStatus.QUEUED }
    }
    val mountedVolumes = remember(volumes) { volumes.filter { it.available } }
    val recentActivity = remember(settings.homeActivity, api.baseUrl) {
        settings.homeActivity.filter { it.serverUrl == api.baseUrl }.take(8)
    }

    val spinTransition = rememberInfiniteTransition(label = "refresh_spin")
    val spinAngle by spinTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "spin_angle",
    )

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            com.example.ui.components.PocketLinkTopBar(
                title = "PocketLink",
                subtitle = "Your private space",
                navigationIcon = {
                    IconButton(
                        onClick = onOpenDrawer,
                        modifier = Modifier.testTag("open_drawer_menu_btn"),
                    ) {
                        Icon(Icons.Rounded.Menu, contentDescription = "Open storage menu")
                    }
                },
                actions = {
                    LiquidGlassPill(
                        useLiquidGlass = settings.useLiquidGlass,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        IconButton(
                            onClick = onOpenTransfers,
                            modifier = Modifier.size(40.dp).testTag("dashboard_transfers_btn"),
                        ) {
                            Icon(
                                Icons.Rounded.SwapHoriz,
                                contentDescription = "Transfers",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = { if (!refreshing) scope.launch { loadAll() } },
                        modifier = Modifier.testTag("dashboard_refresh_btn"),
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "Refresh dashboard",
                            modifier = if (refreshing) Modifier.rotate(spinAngle) else Modifier,
                        )
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("dashboard_settings_btn"),
                    ) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                    }
                },
            )
        }
    ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 128.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "server_telemetry") {
                    ServerTelemetryCard(
                        api = api,
                        profile = profile,
                        online = online,
                        latencyMs = latencyMs,
                        lastCheckedMs = lastCheckedMs,
                        volumes = mountedVolumes,
                        activeTransfers = activeTransfers.size,
                        refreshing = refreshing,
                        useLiquidGlass = settings.useLiquidGlass,
                        onOpenSwitcher = { showServerSwitcher = true },
                        onQuickUpload = onUploadFiles,
                        onOpenTransfers = onOpenTransfers,
                        onOpenDrives = { onOpenFiles(mountedVolumes.firstOrNull()?.id ?: "internal", "") },
                        onReconnect = { scope.launch { loadAll() } },
                        onSwitchServer = onSwitchServer,
                    )
                }

                item(key = "host_health") {
                    HostDeviceMetricsCard(
                        telemetry = telemetry,
                        volumes = volumes,
                        online = online == true,
                        onOpenVolume = { vol -> onOpenFiles(vol.id, "") },
                    )
                }

                item(key = "media_scan") {
                    HostMediaScanCard(
                        status = indexStatus,
                        online = online == true,
                        onScan = {
                            scope.launch {
                                runCatching { api.startMediaIndexScan() }
                                    .onSuccess { indexStatus = it }
                            }
                        },
                    )
                }

                settings.homeSectionOrder.forEach { section ->
                    when (section) {
                        "search" -> if (settings.showGlobalSearch) {
                            item(key = "sec_search") {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(18.dp))
                                        .clickable(onClick = onOpenSearch)
                                        .testTag("dashboard_search_bar"),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    shape = RoundedCornerShape(18.dp),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Text(
                                            text = "Search files, folders and media",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Icon(
                                            Icons.AutoMirrored.Rounded.ArrowForward,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }

                        "quick" -> if (settings.showQuickActions) {
                            item(key = "sec_quick") {
                                QuickActionsSection(
                                    actions = settings.quickActions,
                                    onEdit = { showQuickActionsEditor = true },
                                    onAction = { key ->
                                        when (key) {
                                            "upload" -> onUploadFiles()
                                            "yt_music" -> onOpenYtMusic()
                                            "files" -> onOpenFiles(null, "")
                                            "drives" -> onOpenFiles(mountedVolumes.firstOrNull()?.id ?: "internal", "")
                                            "favorites" -> onOpenPhotos(true)
                                            "photos" -> onOpenPhotos(false)
                                            "music" -> onOpenMusic()
                                            "videos" -> onOpenVideos()
                                            "documents" -> onOpenDocuments()
                                            "transfers" -> onOpenTransfers()
                                        }
                                    },
                                )
                            }
                        }

                        "transfers" -> if (settings.showActiveTransfers && activeTransfers.isNotEmpty()) {
                            item(key = "sec_transfers") {
                                ActiveTransfersPreviewCard(
                                    transfers = activeTransfers.take(2),
                                    onOpenTransfers = onOpenTransfers,
                                )
                            }
                        }

                        "continue" -> if (settings.showLibrary) {
                            item(key = "sec_continue") {
                                ContinueStripSection(
                                    items = recentActivity,
                                    onOpen = onOpenActivityItem,
                                )
                            }
                        }

                        "recent" -> if (settings.showRecentFiles) {
                            item(key = "sec_recent_header") {
                                Text(
                                    text = "Recent files",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                            }
                            if (recentFiles.isEmpty()) {
                                item(key = "sec_recent_empty") {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        shape = RoundedCornerShape(20.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(18.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(
                                                text = if (refreshing) "Checking your folders for recent files…"
                                                else "No recent files found in the scanned folders.",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            } else {
                                item(key = "sec_recent_card") {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        shape = RoundedCornerShape(20.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column {
                                            recentFiles.take(8).forEachIndexed { idx, recent ->
                                                if (idx > 0) {
                                                    HorizontalDivider(
                                                        modifier = Modifier.padding(start = 68.dp),
                                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                                    )
                                                }
                                                RecentFileRow(
                                                    recent = recent,
                                                    onClick = { onOpenRecentFile(recent) },
                                                    onDownload = {
                                                        transferManager.enqueueDownload(
                                                            api = api,
                                                            remotePath = recent.path,
                                                            volume = recent.volume,
                                                            fileName = recent.file.name,
                                                            expectedSize = recent.file.size,
                                                        )
                                                    },
                                                    onRename = { renameTarget = recent },
                                                    onDelete = { deleteTarget = recent },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    if (showServerSwitcher) {
        ModalBottomSheet(onDismissRequest = { showServerSwitcher = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Server switcher & Native host",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            showServerSwitcher = false
                            onSwitchServer()
                        },
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.SwapHoriz, contentDescription = null)
                        Column(Modifier.weight(1f)) {
                            Text("Switch server", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("Choose or manage saved servers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            showServerSwitcher = false
                            onOpenHostSetup()
                        },
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.Dns, contentDescription = null)
                        Column(Modifier.weight(1f)) {
                            Text("Host PocketLink on this phone", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("Manage storage permissions, USB drives & sample media", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showQuickActionsEditor) {
        QuickActionsEditDialog(
            current = settings.quickActions,
            onDismiss = { showQuickActionsEditor = false },
            onSave = { next ->
                onSaveQuickActions(next)
                showQuickActionsEditor = false
            },
        )
    }

    renameTarget?.let { target ->
        var newName by remember(target) { mutableStateOf(target.file.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename file") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newName.trim()
                        renameTarget = null
                        if (trimmed.isNotEmpty() && trimmed != target.file.name) {
                            scope.launch {
                                runCatching { api.renameItem(target.path, trimmed, target.volume) }
                                loadAll()
                            }
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete file?") },
            text = { Text("Delete \"${target.file.name}\"? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            runCatching { api.deleteItem(target.path, target.volume) }
                            loadAll()
                        }
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ServerTelemetryCard(
    api: PocketLinkApi,
    profile: ServerProfile?,
    online: Boolean?,
    latencyMs: Long?,
    lastCheckedMs: Long?,
    volumes: List<StorageVolume>,
    activeTransfers: Int,
    refreshing: Boolean,
    useLiquidGlass: Boolean,
    onOpenSwitcher: () -> Unit,
    onQuickUpload: () -> Unit,
    onOpenTransfers: () -> Unit,
    onOpenDrives: () -> Unit,
    onReconnect: () -> Unit,
    onSwitchServer: () -> Unit,
) {
    val colors = LocalPocketLinkColors.current
    val dotColor = when (online) {
        true -> colors.emerald
        false -> MaterialTheme.colorScheme.error
        null -> colors.amber
    }
    val hostLabel = profile?.displayName ?: api.baseUrl.removePrefix("http://").removePrefix("https://")
    val timeText = remember(lastCheckedMs) {
        lastCheckedMs?.let { SimpleDateFormat("HH:mm", Locale.US).format(Date(it)) }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(
                1.dp,
                if (online == false) MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
                else colors.cardBorder,
                RoundedCornerShape(22.dp),
            )
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenSwitcher)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
                Text(
                    text = hostLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RouteBadge(api.routeType)
                Icon(Icons.Rounded.ExpandMore, contentDescription = "Switch server", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            refreshing -> "Checking server…"
                            online == true -> "Online${latencyMs?.let { " • ${it} ms" }.orEmpty()}"
                            else -> "Offline"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = dotColor,
                    )
                    if (timeText != null) {
                        Text(
                            text = "Last checked $timeText",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                LiquidGlassPill(
                    useLiquidGlass = useLiquidGlass,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    IconButton(
                        onClick = onQuickUpload,
                        enabled = online == true,
                        modifier = Modifier.size(40.dp).testTag("dashboard_quick_upload_btn"),
                    ) {
                        Icon(
                            Icons.Rounded.ArrowUpward,
                            contentDescription = "Quick upload",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = online == false && !refreshing,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Server is unreachable. Check your Tailscale or local host service.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onReconnect) { Text("Reconnect") }
                    TextButton(onClick = onSwitchServer) { Text("Switch") }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("STORAGE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.7.sp)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${volumes.size + 1} volume${if (volumes.isEmpty()) "" else "s"}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenTransfers),
                ) {
                    Text("TRANSFERS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.7.sp)
                    Spacer(Modifier.height(3.dp))
                    Text("$activeTransfers active", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("LATENCY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.7.sp)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (refreshing) "Checking" else latencyMs?.let { "$it ms" } ?: "—",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenDrives),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "MOUNTED PARTITIONS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 0.65.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${volumes.size + 1} mounted", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Box(modifier = Modifier.weight(1f).height(5.dp).background(colors.sky))
                    volumes.forEach { _ ->
                        Box(modifier = Modifier.weight(1f).height(5.dp).background(colors.teal))
                    }
                }
                Text(
                    text = buildString {
                        append("Internal storage")
                        if (volumes.isNotEmpty()) {
                            append(" · ")
                            append(volumes.joinToString(" · ") { it.name })
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HostDeviceMetricsCard(
    telemetry: HostSystemTelemetry?,
    volumes: List<StorageVolume>,
    online: Boolean,
    onOpenVolume: (StorageVolume) -> Unit,
) {
    val colors = LocalPocketLinkColors.current
    val updatedAt = remember(telemetry?.sampledAtMs) {
        telemetry?.sampledAtMs?.let { SimpleDateFormat("HH:mm", Locale.US).format(Date(it)) }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, colors.cardBorder, RoundedCornerShape(22.dp))
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Text(
                    "HOST DEVICE HEALTH",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.65.sp,
                    modifier = Modifier.weight(1f),
                )
                if (updatedAt != null) {
                    Text(
                        "${if (online) "Updated" else "Last known"} $updatedAt",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (telemetry == null) {
                Text(
                    text = if (online) "Reading live CPU, memory, storage & battery…" else "Host health unavailable while offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricGridTile(
                        icon = Icons.Rounded.Speed,
                        label = "CPU",
                        value = telemetry.cpuUsagePercent?.let { "${it.roundToInt()}%" } ?: "—",
                        progress = telemetry.cpuUsagePercent?.div(100.0)?.toFloat(),
                        detail = if (telemetry.cpuUsagePercent != null) "Live device usage" else "Sampling next tick",
                        color = colors.sky,
                        modifier = Modifier.weight(1f),
                    )
                    MetricGridTile(
                        icon = Icons.Rounded.Memory,
                        label = "MEMORY",
                        value = telemetry.memoryUsagePercent?.let { "${it.roundToInt()}%" } ?: "—",
                        progress = telemetry.memoryUsagePercent?.div(100.0)?.toFloat(),
                        detail = if (telemetry.memoryUsedBytes != null && telemetry.memoryTotalBytes != null) {
                            "${formatBytes(telemetry.memoryUsedBytes)} / ${formatBytes(telemetry.memoryTotalBytes)}"
                        } else "Unavailable",
                        color = colors.violet,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricGridTile(
                        icon = Icons.Rounded.Storage,
                        label = "STORAGE",
                        value = telemetry.storageUsagePercent?.let { "${it.roundToInt()}%" } ?: "—",
                        progress = telemetry.storageUsagePercent?.div(100.0)?.toFloat(),
                        detail = if (telemetry.storageAvailableBytes != null) {
                            "${formatBytes(telemetry.storageAvailableBytes)} free"
                        } else "Unavailable",
                        color = colors.cyan,
                        modifier = Modifier.weight(1f),
                    )
                    MetricGridTile(
                        icon = if (telemetry.isCharging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd,
                        label = "BATTERY",
                        value = telemetry.batteryPercent?.let { "$it%" } ?: "—",
                        progress = telemetry.batteryPercent?.div(100f),
                        detail = when (telemetry.batteryStatus) {
                            "charging" -> "Charging"
                            "not_charging" -> "Not charging"
                            else -> "Status unavailable"
                        },
                        color = colors.emerald,
                        modifier = Modifier.weight(1f),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DeviceDetailRow(
                        icon = Icons.Rounded.PhoneAndroid,
                        title = telemetry.deviceDisplayName,
                        trailing = telemetry.androidVersion?.let { "Android $it" },
                    )
                    if (telemetry.cpuModel != null || telemetry.cpuCores != null) {
                        DeviceDetailRow(
                            icon = Icons.Rounded.Memory,
                            title = telemetry.cpuModel ?: "Processor",
                            trailing = telemetry.cpuCores?.let { "$it cores" },
                        )
                    }
                }
            }

            if (volumes.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Text(
                    "EXTERNAL STORAGE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.65.sp,
                )
                volumes.forEach { vol ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = vol.available) { onOpenVolume(vol) }
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Usb, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Text(vol.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = when {
                                    !vol.available -> "Disconnected"
                                    vol.writable -> "Writable"
                                    else -> "Read only"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    !vol.available -> MaterialTheme.colorScheme.onSurfaceVariant
                                    vol.writable -> colors.emerald
                                    else -> colors.amber
                                },
                            )
                        }
                        if (vol.available && vol.usedBytes != null && vol.totalBytes != null && vol.availableBytes != null && vol.usagePercent != null) {
                            Row {
                                Text(
                                    "${formatBytes(vol.usedBytes)} of ${formatBytes(vol.totalBytes)} used",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${formatBytes(vol.availableBytes)} free",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            CapacityProgressBar(fraction = (vol.usagePercent / 100.0).toFloat(), color = colors.sky)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricGridTile(
    icon: ImageVector,
    label: String,
    value: String,
    progress: Float?,
    detail: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPocketLinkColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, letterSpacing = 0.55.sp)
        }
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        if (progress != null) {
            CapacityProgressBar(fraction = progress, color = color)
        }
        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DeviceDetailRow(icon: ImageVector, title: String, trailing: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!trailing.isNullOrBlank()) {
            Text(trailing, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HostMediaScanCard(
    status: MediaIndexStatus?,
    online: Boolean,
    onScan: () -> Unit,
) {
    val colors = LocalPocketLinkColors.current
    val scanning = status?.isScanning == true
    val stateLabel = when {
        scanning -> "Scanning"
        status == null -> "Checking"
        status.state == "failed" -> "Failed"
        status.state == "partial" -> "Partial"
        status.state == "complete" -> "Up to date"
        else -> "Ready"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, colors.cardBorder, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(Icons.Rounded.ManageSearch, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text("Host media library", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Text(
                    stateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (scanning) colors.amber else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (scanning) "Scanning ${status?.currentSource ?: "storage"}…"
                else "Refresh the host device media and file index remotely.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status != null) {
                Text(
                    "${status.mediaCount} media · ${status.fileCount} files · ${status.scanned} checked · ${status.skipped} skipped",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                )
            }
            if (scanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), strokeCap = StrokeCap.Round)
            }
            OutlinedButton(
                onClick = onScan,
                enabled = online && !scanning,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (scanning) "Scanning on host…" else "Scan host now")
            }
        }
    }
}

@Composable
private fun QuickActionsSection(
    actions: List<String>,
    onEdit: () -> Unit,
    onAction: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Quick actions",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onEdit) { Text("Edit") }
        }
        val rows = actions.chunked(4)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowItems.forEach { key ->
                        val colors = LocalPocketLinkColors.current
                        val (label, icon, tint) = when (key) {
                            "upload" -> Triple("Upload", Icons.Rounded.CloudUpload, MaterialTheme.colorScheme.primary)
                            "yt_music" -> Triple("Discover", Icons.Rounded.LibraryMusic, colors.violet)
                            "files" -> Triple("Files", Icons.Rounded.FolderOpen, colors.sky)
                            "drives" -> Triple("Drives", Icons.Rounded.Usb, colors.cyan)
                            "favorites" -> Triple("Favorites", Icons.Rounded.Favorite, colors.amber)
                            "photos" -> Triple("Photos", Icons.Rounded.PhotoLibrary, colors.amber)
                            "music" -> Triple("Music", Icons.Rounded.Headphones, colors.violet)
                            "videos" -> Triple("Videos", Icons.Rounded.Movie, colors.teal)
                            "documents" -> Triple("Docs", Icons.Rounded.Description, colors.sky)
                            else -> Triple("Transfers", Icons.Rounded.SwapHoriz, colors.emerald)
                        }
                        val interactionSource = remember { MutableInteractionSource() }
                        val scaleMod = rememberPressScaleModifier(interactionSource, targetScale = 0.94f)
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .then(scaleMod)
                                .clip(RoundedCornerShape(18.dp))
                                .border(1.dp, colors.cardBorder, RoundedCornerShape(18.dp))
                                .clickable(interactionSource = interactionSource, indication = null) { onAction(key) },
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(tint.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                                }
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    repeat(4 - rowItems.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveTransfersPreviewCard(
    transfers: List<TransferItem>,
    onOpenTransfers: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpenTransfers),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Active transfers", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Text("View all", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            transfers.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            if (item.direction == TransferDirection.UPLOAD) Icons.Rounded.CloudUpload else Icons.Rounded.ArrowDownward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(item.fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${(item.progress * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                    CapacityProgressBar(fraction = item.progress, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun ContinueStripSection(
    items: List<HomeActivityItem>,
    onOpen: (HomeActivityItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Continue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        if (items.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Your recent tracks, folders and files will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { "${it.kind}:${it.path}:${it.updatedAtMs}" }) { recent ->
                    val icon = when (recent.kind) {
                        "track" -> Icons.Rounded.MusicNote
                        "folder" -> Icons.Rounded.Folder
                        else -> Icons.AutoMirrored.Rounded.InsertDriveFile
                    }
                    Surface(
                        modifier = Modifier
                            .width(246.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { onOpen(recent) },
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(recent.kind.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(recent.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(
                                if (recent.kind == "track") Icons.Rounded.PlayArrow else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentFileRow(
    recent: RecentFile,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FileIconBadge(recent.file)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = recent.file.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${formatBytes(recent.file.size)} · ${recent.volumeName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "File actions")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("Download") }, onClick = { menuExpanded = false; onDownload() })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menuExpanded = false; onRename() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menuExpanded = false; onDelete() })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickActionsEditDialog(
    current: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val draft = remember { mutableStateListOf<String>().apply { addAll(current) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Customize quick actions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Select the actions you want on Home.", style = MaterialTheme.typography.bodySmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AppSettingsState.ALL_QUICK_ACTIONS.forEach { action ->
                        val selected = action in draft
                        val chipLabel = when (action) {
                            "yt_music" -> "Discover"
                            else -> action.replaceFirstChar { it.uppercase() }
                        }
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (selected && draft.size > 1) draft.remove(action)
                                else if (!selected) draft.add(action)
                            },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                            label = { Text(chipLabel) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(draft.toList()) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun HomeNavigationDrawerSheet(
    drawerState: DrawerState,
    api: PocketLinkApi,
    profile: ServerProfile?,
    settings: AppSettingsState,
    online: Boolean?,
    latencyMs: Long?,
    telemetry: HostSystemTelemetry?,
    volumes: List<StorageVolume>,
    transfers: List<TransferItem>,
    onSwitchServer: () -> Unit,
    onOpenHostSetup: () -> Unit,
    onOpenFiles: (volume: String?, path: String) -> Unit,
    onOpenPhotos: (favoritesOnly: Boolean) -> Unit,
    onOpenMusic: () -> Unit,
    onOpenYtMusic: () -> Unit = {},
    onOpenVideos: () -> Unit,
    onOpenDocuments: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenTransfers: () -> Unit,
    onOpenSettings: () -> Unit,
    onQuickThemeChange: ((AppThemeMode, Boolean) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val colors = LocalPocketLinkColors.current
    val isDark = LocalPocketLinkDarkTheme.current
    val activeTransfers = remember(transfers) {
        transfers.filter { it.status == TransferStatus.RUNNING || it.status == TransferStatus.QUEUED }
    }
    val mountedVolumes = remember(volumes) { volumes.filter { it.available } }

    ModalDrawerSheet(
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.widthIn(max = 324.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Rich Host & Status Header Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.linearGradient(
                            if (isDark) {
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                                    MaterialTheme.colorScheme.surfaceContainerLow,
                                )
                            } else {
                                listOf(
                                    Color(0xFFE7ECFF),
                                    Color(0xFFF6F8FF),
                                )
                            }
                        )
                    )
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(22.dp))
                    .padding(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Cloud,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "PocketLink",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                            )
                            Text(
                                text = profile?.displayName ?: "Private Cloud Host",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val dotColor = when (online) {
                            true -> colors.emerald
                            false -> MaterialTheme.colorScheme.error
                            null -> colors.amber
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor),
                            )
                            Text(
                                text = when {
                                    online == true && latencyMs != null -> "Online · ${latencyMs} ms"
                                    online == true -> "Online"
                                    online == false -> "Offline"
                                    else -> "Checking…"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = dotColor,
                            )
                        }
                        RouteBadge(api.routeType)
                    }
                }
            }

            // Quick Libraries 2-Column Shortcuts inside the 3-Bar Menu
            DrawerSectionLabel("Quick Libraries")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DrawerQuickTile(
                        icon = Icons.Rounded.Headphones,
                        label = "Music",
                        tint = colors.violet,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenMusic()
                        },
                        modifier = Modifier.weight(1f).testTag("drawer_quick_music"),
                    )
                    DrawerQuickTile(
                        icon = Icons.Rounded.Movie,
                        label = "Videos",
                        tint = colors.teal,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenVideos()
                        },
                        modifier = Modifier.weight(1f).testTag("drawer_quick_videos"),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DrawerQuickTile(
                        icon = Icons.Rounded.PhotoLibrary,
                        label = "Photos",
                        tint = colors.amber,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenPhotos(false)
                        },
                        modifier = Modifier.weight(1f).testTag("drawer_quick_photos"),
                    )
                    DrawerQuickTile(
                        icon = Icons.Rounded.Description,
                        label = "Docs",
                        tint = colors.sky,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenDocuments()
                        },
                        modifier = Modifier.weight(1f).testTag("drawer_quick_docs"),
                    )
                }
                if (settings.ytStreamingEnabled) {
                    DrawerQuickTile(
                        icon = Icons.Rounded.LibraryMusic,
                        label = "Discover",
                        tint = colors.violet,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenYtMusic()
                        },
                        modifier = Modifier.fillMaxWidth().testTag("drawer_quick_yt_music"),
                    )
                }
            }

            // Storage & Drives Section
            DrawerSectionLabel("Storage & Host")
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(18.dp)),
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    DrawerMenuRow(
                        icon = Icons.Rounded.PhoneAndroid,
                        tint = colors.sky,
                        title = "Internal storage",
                        subtitle = telemetry?.storageAvailableBytes?.let { "${formatBytes(it)} free" } ?: "Browse root folders",
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenFiles(null, "")
                        },
                        testTag = "drawer_item_internal",
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 54.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    DrawerMenuRow(
                        icon = Icons.Rounded.Usb,
                        tint = colors.cyan,
                        title = "USB & external drives",
                        subtitle = "${mountedVolumes.size} of ${volumes.size} available",
                        badgeText = if (mountedVolumes.isNotEmpty()) "${mountedVolumes.size}" else null,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenFiles(mountedVolumes.firstOrNull()?.id ?: "internal", "")
                        },
                        testTag = "drawer_item_usb",
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 54.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    DrawerMenuRow(
                        icon = Icons.Rounded.AddToQueue,
                        tint = colors.emerald,
                        title = "Host & Tailscale setup",
                        subtitle = "Share drives or link another device",
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenHostSetup()
                        },
                        testTag = "drawer_item_host",
                    )
                }
            }

            // Tools & System Section
            DrawerSectionLabel("Tools & Navigation")
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(18.dp)),
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    DrawerMenuRow(
                        icon = Icons.Rounded.Search,
                        tint = MaterialTheme.colorScheme.primary,
                        title = "Search everything",
                        subtitle = "Find files, music, videos & docs",
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenSearch()
                        },
                        testTag = "drawer_item_search",
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 54.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    DrawerMenuRow(
                        icon = Icons.Rounded.SwapHoriz,
                        tint = colors.teal,
                        title = "Transfers",
                        subtitle = if (activeTransfers.isEmpty()) "Upload & download queue" else "${activeTransfers.size} transfer(s) running",
                        badgeText = if (activeTransfers.isNotEmpty()) "${activeTransfers.size}" else null,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenTransfers()
                        },
                        testTag = "drawer_item_transfers",
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 54.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    DrawerMenuRow(
                        icon = Icons.Rounded.Dns,
                        tint = colors.amber,
                        title = "Switch server",
                        subtitle = "Manage saved hosts & LAN / Tailscale",
                        onClick = {
                            scope.launch { drawerState.close() }
                            onSwitchServer()
                        },
                        testTag = "drawer_item_switch_server",
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 54.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    DrawerMenuRow(
                        icon = Icons.Rounded.Settings,
                        tint = MaterialTheme.colorScheme.primary,
                        title = "Settings",
                        subtitle = "Themes, dock, players & storage",
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenSettings()
                        },
                        testTag = "drawer_item_settings",
                    )
                }
            }

            // Instant Theme Switcher Footer
            if (onQuickThemeChange != null) {
                DrawerSectionLabel("Theme")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !settings.amoled && settings.themeMode == AppThemeMode.LIGHT,
                        onClick = { onQuickThemeChange(AppThemeMode.LIGHT, false) },
                        leadingIcon = { Icon(Icons.Rounded.LightMode, contentDescription = null, modifier = Modifier.size(15.dp)) },
                        label = { Text("Light") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = !settings.amoled && settings.themeMode == AppThemeMode.DARK,
                        onClick = { onQuickThemeChange(AppThemeMode.DARK, false) },
                        leadingIcon = { Icon(Icons.Rounded.DarkMode, contentDescription = null, modifier = Modifier.size(15.dp)) },
                        label = { Text("Dark") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = settings.amoled,
                        onClick = { onQuickThemeChange(AppThemeMode.DARK, !settings.amoled) },
                        label = { Text("AMOLED") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.ExtraBold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 1.05.sp,
        modifier = Modifier.padding(start = 6.dp, top = 2.dp),
    )
}

@Composable
private fun DrawerQuickTile(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalPocketLinkColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val scaleMod = rememberPressScaleModifier(interactionSource, targetScale = 0.95f)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .then(scaleMod)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, colors.cardBorder, RoundedCornerShape(16.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DrawerMenuRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String? = null,
    badgeText: String? = null,
    onClick: () -> Unit,
    testTag: String,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scaleMod = rememberPressScaleModifier(interactionSource, targetScale = 0.985f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(scaleMod)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!badgeText.isNullOrBlank()) {
            Surface(
                shape = CircleShape,
                color = tint.copy(alpha = 0.14f),
            ) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = tint,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

