package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.HomeActivityItem
import com.example.data.model.HostSystemTelemetry
import com.example.data.model.IndexedFile
import com.example.data.model.MediaCategory
import com.example.data.model.MediaExtensions
import com.example.data.model.StorageVolume
import com.example.ui.components.FloatingDockScrimBackdrop
import com.example.ui.components.FloatingNavigationDock
import com.example.ui.components.LocalBackdropGlassState
import com.example.ui.components.MusicQuickAccessMenu
import com.example.ui.components.PersistentMiniPlayer
import com.example.ui.components.backdropGlassSource
import com.example.ui.components.liquidBackdropBlur
import com.example.ui.components.rememberBackdropGlassState
import com.example.ui.screens.AboutScreen
import com.example.ui.screens.ConnectionScreen
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.DocumentsBrowserScreen
import com.example.ui.screens.ExploreScreen
import com.example.ui.screens.FileManagerScreen
import com.example.ui.screens.HomeDashboardScreen
import com.example.ui.screens.HomeNavigationDrawerSheet
import com.example.ui.screens.LocalStorageSetupScreen
import com.example.ui.screens.MediaBrowserScreen
import com.example.ui.screens.NowPlayingScreen
import com.example.ui.screens.PhotoGalleryScreen
import com.example.ui.screens.PhotoViewerScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TransferManagerScreen
import com.example.ui.screens.VideoPlayerScreen
import com.example.ui.screens.YtMusicScreen
import com.example.ui.theme.PocketLinkTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class MainTab(val id: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Rounded.Home),
    EXPLORE("explore", "Explore", Icons.Rounded.Explore),
    FILES("files", "Files", Icons.Rounded.Folder),
}

sealed interface OverlayRoute {
    data object HostSetup : OverlayRoute
    data class Settings(val openYtStreaming: Boolean = false) : OverlayRoute
    data object Transfers : OverlayRoute
    data object Diagnostics : OverlayRoute
    data object About : OverlayRoute
    data object Search : OverlayRoute
    data object Documents : OverlayRoute
    data object YtMusic : OverlayRoute
    data class Photos(val favoritesOnly: Boolean = false) : OverlayRoute
    data class PhotoViewer(val photos: List<IndexedFile>, val index: Int) : OverlayRoute
    data class MediaLibrary(val category: MediaCategory) : OverlayRoute
    data object NowPlaying : OverlayRoute
    data class VideoPlayer(val video: IndexedFile) : OverlayRoute
}

@Composable
fun PocketLinkApp(viewModel: PocketLinkViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val hostStatus by viewModel.hostStatus.collectAsStateWithLifecycle()
    val tunnelStatus by viewModel.tunnelStatus.collectAsStateWithLifecycle()
    val tunnelConfig by viewModel.tunnelConfig.collectAsStateWithLifecycle()
    val audioState by viewModel.audioController.state.collectAsStateWithLifecycle()
    val transfers by viewModel.transferManager.transfers.collectAsStateWithLifecycle()
    val imageLoader by viewModel.imageLoader.collectAsStateWithLifecycle()
    val ytMusicState by viewModel.ytMusic.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyricsState.collectAsStateWithLifecycle()

    val overlayStack = remember { mutableStateListOf<OverlayRoute>() }
    var selectedTab by remember { mutableStateOf(MainTab.HOME) }
    var filesInitialVolume by remember { mutableStateOf<String?>(null) }
    var filesInitialPath by remember { mutableStateOf("") }
    var miniPlayerDismissed by remember { mutableStateOf(false) }
    var dockQuickMenuExpanded by remember { mutableStateOf(false) }
    var miniPlayerBounds by remember { mutableStateOf<Rect?>(null) }
    var musicButtonBounds by remember { mutableStateOf<Rect?>(null) }
    var rootSizePx by remember { mutableStateOf(IntSize.Zero) }

    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val dashboardUploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val api = connection.activeApi ?: return@rememberLauncherForActivityResult
        if (uris.isNotEmpty()) {
            uris.forEach { uri -> viewModel.transferManager.enqueueUpload(api, uri, "", null) }
            scope.launch { snackbarHostState.showSnackbar("Uploading ${uris.size} file(s) to internal root…") }
        }
    }

    fun openShortcut(id: String) {
        dockQuickMenuExpanded = false
        when (id) {
            "explore" -> {
                overlayStack.clear()
                selectedTab = MainTab.EXPLORE
            }
            "yt_music" -> {
                if (settings.ytStreamingEnabled) {
                    overlayStack.add(OverlayRoute.YtMusic)
                } else {
                    overlayStack.add(OverlayRoute.Settings(openYtStreaming = true))
                }
            }
            "files" -> {
                overlayStack.clear()
                filesInitialVolume = null
                filesInitialPath = ""
                selectedTab = MainTab.FILES
            }
            "music" -> overlayStack.add(OverlayRoute.MediaLibrary(MediaCategory.MUSIC))
            "videos" -> overlayStack.add(OverlayRoute.MediaLibrary(MediaCategory.VIDEO))
            "photos" -> overlayStack.add(OverlayRoute.Photos(false))
            "documents" -> overlayStack.add(OverlayRoute.Documents)
            "favorites" -> overlayStack.add(OverlayRoute.Photos(true))
            "upload" -> dashboardUploadLauncher.launch(arrayOf("*/*"))
        }
    }

    fun openActivity(item: HomeActivityItem) {
        when (item.kind) {
            "track" -> {
                val track = IndexedFile(
                    volume = item.volume ?: "internal",
                    path = item.path,
                    name = item.title,
                    type = "file",
                    size = 0L,
                    modified = "",
                    mime = "",
                )
                miniPlayerDismissed = false
                viewModel.playHostOrLocalPlaylist(listOf(track), 0)
                overlayStack.add(OverlayRoute.NowPlaying)
            }
            "folder" -> {
                filesInitialVolume = item.volume
                filesInitialPath = item.path
                selectedTab = MainTab.FILES
            }
            else -> {
                filesInitialVolume = item.volume
                filesInitialPath = item.parentPath
                selectedTab = MainTab.FILES
            }
        }
    }

    PocketLinkTheme(themeMode = settings.themeMode, amoled = settings.amoled) {
        val currentOverlay = overlayStack.lastOrNull()
        val isNowPlayingActive = currentOverlay == OverlayRoute.NowPlaying
        val topNonPlayerOverlay = overlayStack.lastOrNull { it != OverlayRoute.NowPlaying }
        val activeApi = connection.activeApi

        val nowPlayingProgress = remember { Animatable(0f) }
        var nowPlayingMounted by remember { mutableStateOf(false) }
        var nowPlayingClosing by remember { mutableStateOf(false) }

        LaunchedEffect(isNowPlayingActive) {
            if (isNowPlayingActive) {
                nowPlayingClosing = false
                nowPlayingMounted = true
                nowPlayingProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = 0.88f,
                        stiffness = 410f,
                    ),
                )
            } else if (!nowPlayingClosing) {
                nowPlayingMounted = false
                nowPlayingProgress.snapTo(0f)
            }
        }

        fun dismissNowPlaying(onCollapsed: (() -> Unit)? = null) {
            if (!nowPlayingMounted || nowPlayingClosing) {
                onCollapsed?.invoke()
                return
            }
            nowPlayingClosing = true
            scope.launch {
                nowPlayingProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = 330,
                        easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f),
                    ),
                )
                if (overlayStack.lastOrNull() == OverlayRoute.NowPlaying) {
                    overlayStack.removeAt(overlayStack.lastIndex)
                } else {
                    overlayStack.removeAll { it == OverlayRoute.NowPlaying }
                }
                nowPlayingMounted = false
                nowPlayingClosing = false
                onCollapsed?.invoke()
            }
        }

        val depthProgress = if (nowPlayingMounted) nowPlayingProgress.value.coerceIn(0f, 1f) else 0f

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { rootSizePx = it },
        ) {
            // Base layer: Connect screen OR Main tabbed shell (stays mounted under overlays so scroll state persists)
            AnimatedContent(
                targetState = activeApi != null,
                transitionSpec = {
                    if (targetState) {
                        (slideInHorizontally(tween(300)) { it / 6 } + fadeIn(tween(260))) togetherWith
                            (slideOutHorizontally(tween(240)) { -it / 6 } + fadeOut(tween(200)))
                    } else {
                        (slideInHorizontally(tween(280)) { -it / 6 } + fadeIn(tween(240))) togetherWith
                            (slideOutHorizontally(tween(240)) { it / 6 } + fadeOut(tween(200)))
                    }
                },
                label = "root_connection_transition",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (depthProgress > 0f) {
                            val s = lerp(1f, 0.935f, depthProgress)
                            scaleX = s
                            scaleY = s
                            shape = RoundedCornerShape((24f * depthProgress).dp)
                            clip = true
                        }
                    },
            ) { isConnected ->
                if (!isConnected || activeApi == null) {
                    val disconnectBackdrop = rememberBackdropGlassState(settings.useLiquidGlass)
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .backdropGlassSource(disconnectBackdrop),
                        ) {
                            ConnectionScreen(
                                connectionState = connection,
                                hostStatus = hostStatus,
                                tunnelStatus = tunnelStatus,
                                tunnelConfig = tunnelConfig,
                                ytStreamingEnabled = settings.ytStreamingEnabled,
                                onConnect = { name, host, port, user, pass, id ->
                                    viewModel.connect(name, host, port, user, pass, id)
                                },
                                onQuickStartLocal = { viewModel.quickStartLocalDevice() },
                                onToggleCloudflareTunnel = { turnOn ->
                                    if (turnOn) {
                                        viewModel.startCloudflareTunnel()
                                            .onFailure { overlayStack.add(OverlayRoute.HostSetup) }
                                    } else {
                                        viewModel.stopCloudflareTunnel()
                                    }
                                },
                                onDeleteProfile = { viewModel.deleteProfile(it) },
                                onOpenHostSetup = { overlayStack.add(OverlayRoute.HostSetup) },
                                onOpenYtMusic = { overlayStack.add(OverlayRoute.YtMusic) },
                                onOpenYtSettings = { overlayStack.add(OverlayRoute.Settings(openYtStreaming = true)) },
                                onOpenSettings = { overlayStack.add(OverlayRoute.Settings()) },
                                onOpenDiagnostics = { overlayStack.add(OverlayRoute.Diagnostics) },
                                onOpenAbout = { overlayStack.add(OverlayRoute.About) },
                                onRefreshHostStatus = { viewModel.refreshHostStatus() },
                                onClearError = { viewModel.clearConnectionError() },
                            )
                        }
                        CompositionLocalProvider(LocalBackdropGlassState provides disconnectBackdrop) {
                            PersistentMiniPlayer(
                                state = audioState,
                                visible = settings.showMiniPlayer && (!miniPlayerDismissed || audioState.isPlaying),
                                useLiquidGlass = settings.useLiquidGlass,
                                imageLoader = imageLoader,
                                showArtwork = settings.ytShowArtwork,
                                onOpenNowPlaying = { overlayStack.add(OverlayRoute.NowPlaying) },
                                onTogglePlayPause = { viewModel.audioController.togglePlayPause() },
                                onPrevious = { viewModel.audioController.previous() },
                                onNext = { viewModel.audioController.next() },
                                onDismiss = { miniPlayerDismissed = true },
                                onMiniPlayerBounds = { miniPlayerBounds = it },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(bottom = 10.dp),
                            )
                        }
                    }
                } else {
                    MainConnectedShell(
                        viewModel = viewModel,
                        activeApi = activeApi,
                        connection = connection,
                        settings = settings,
                        audioState = audioState,
                        transfers = transfers,
                        imageLoader = imageLoader,
                        snackbarHostState = snackbarHostState,
                        selectedTab = selectedTab,
                        onSelectTab = { selectedTab = it },
                        filesInitialVolume = filesInitialVolume,
                        filesInitialPath = filesInitialPath,
                        onUpdateFilesLocation = { vol, path ->
                            filesInitialVolume = vol
                            filesInitialPath = path
                        },
                        miniPlayerDismissed = miniPlayerDismissed,
                        onSetMiniPlayerDismissed = { miniPlayerDismissed = it },
                        dockQuickMenuExpanded = dockQuickMenuExpanded,
                        onSetDockQuickMenuExpanded = { dockQuickMenuExpanded = it },
                        overlayStackEmpty = overlayStack.isEmpty(),
                        onOpenOverlay = { overlayStack.add(it) },
                        onOpenShortcut = ::openShortcut,
                        onOpenActivity = ::openActivity,
                        onUploadFiles = { dashboardUploadLauncher.launch(arrayOf("*/*")) },
                        onQuickThemeChange = { mode, amoled ->
                            scope.launch { viewModel.settingsRepository.setThemeMode(mode, amoled = amoled) }
                        },
                        onMusicButtonBounds = { musicButtonBounds = it },
                        onMiniPlayerBounds = { miniPlayerBounds = it },
                    )
                }
            }

            var overlayBackProgress by remember { mutableFloatStateOf(0f) }

            PredictiveBackHandler(enabled = nowPlayingMounted || topNonPlayerOverlay != null) { progressFlow ->
                if (nowPlayingMounted && !nowPlayingClosing) {
                    try {
                        progressFlow.collect { backEvent ->
                            nowPlayingProgress.snapTo((1f - backEvent.progress * 0.42f).coerceIn(0.45f, 1f))
                        }
                        dismissNowPlaying()
                    } catch (e: CancellationException) {
                        nowPlayingProgress.animateTo(
                            targetValue = 1f,
                            animationSpec = spring(dampingRatio = 0.88f, stiffness = 410f),
                        )
                        throw e
                    }
                } else if (topNonPlayerOverlay != null) {
                    try {
                        progressFlow.collect { backEvent ->
                            overlayBackProgress = backEvent.progress.coerceIn(0f, 1f)
                        }
                        overlayBackProgress = 0f
                        val idx = overlayStack.indexOfLast { it != OverlayRoute.NowPlaying }
                        if (idx >= 0) overlayStack.removeAt(idx)
                    } catch (e: CancellationException) {
                        overlayBackProgress = 0f
                        throw e
                    }
                }
            }

            // Standard Overlay Stack (everything except NowPlaying morph overlay)
            AnimatedVisibility(
                visible = topNonPlayerOverlay != null,
                enter = slideInVertically(
                    animationSpec = spring(dampingRatio = 0.90f, stiffness = 460f),
                    initialOffsetY = { it / 12 },
                ) + fadeIn(tween(200)) + scaleIn(
                    initialScale = 0.95f,
                    animationSpec = spring(dampingRatio = 0.90f, stiffness = 460f),
                ),
                exit = slideOutVertically(
                    animationSpec = tween(210, easing = FastOutLinearInEasing),
                    targetOffsetY = { it / 14 },
                ) + fadeOut(tween(170)) + scaleOut(
                    targetScale = 0.95f,
                    animationSpec = tween(200),
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val combinedDepth = depthProgress.coerceAtLeast(overlayBackProgress * 0.6f)
                        if (combinedDepth > 0f) {
                            val s = lerp(1f, 0.92f, combinedDepth)
                            scaleX = s
                            scaleY = s
                            translationY = overlayBackProgress * 32f
                            shape = RoundedCornerShape((24f * combinedDepth).dp)
                            clip = true
                        }
                    },
            ) {
                if (topNonPlayerOverlay != null) {
                    AnimatedContent(
                        targetState = topNonPlayerOverlay,
                        transitionSpec = {
                            (fadeIn(tween(200)) + scaleIn(initialScale = 0.98f, animationSpec = tween(200))) togetherWith
                                fadeOut(tween(160))
                        },
                        label = "overlay_route_transition",
                        modifier = Modifier.fillMaxSize(),
                    ) { route ->
                        when (route) {
                            OverlayRoute.HostSetup -> LocalStorageSetupScreen(
                                viewModel = viewModel,
                                hostStatus = hostStatus,
                                onBack = { overlayStack.removeAt(overlayStack.lastIndex) },
                                onConnectLocal = {
                                    overlayStack.clear()
                                    if (activeApi == null) {
                                        viewModel.quickStartLocalDevice()
                                    }
                                },
                            )
                            is OverlayRoute.Settings -> SettingsScreen(
                                viewModel = viewModel,
                                settings = settings,
                                activeHostLabel = connection.activeProfile?.displayName
                                    ?: activeApi?.baseUrl?.removePrefix("http://")?.removePrefix("https://"),
                                routeType = activeApi?.routeType,
                                initialOpenYtStreaming = route.openYtStreaming,
                                onOpenHostSetup = { overlayStack.add(OverlayRoute.HostSetup) },
                                onOpenDiagnostics = { overlayStack.add(OverlayRoute.Diagnostics) },
                                onOpenAbout = { overlayStack.add(OverlayRoute.About) },
                                onDisconnect = {
                                    overlayStack.clear()
                                    selectedTab = MainTab.HOME
                                    viewModel.disconnect()
                                },
                                onBack = { overlayStack.removeAt(overlayStack.lastIndex) },
                            )
                            OverlayRoute.YtMusic -> YtMusicScreen(
                                ytState = ytMusicState,
                                settings = settings,
                                playbackState = audioState,
                                imageLoader = imageLoader,
                                onLoadHome = { viewModel.loadYtHomeFeed(it) },
                                onQueryChange = { viewModel.updateYtSearchQuery(it) },
                                onSubmitSearch = { viewModel.searchYtMusic(it) },
                                onPlayTrack = { track, list ->
                                    miniPlayerDismissed = false
                                    viewModel.playYtTrack(track, list)
                                },
                                onQueueTrack = { track ->
                                    val active = audioState.currentTrack
                                    if (active == null) {
                                        miniPlayerDismissed = false
                                        viewModel.playYtTrack(track, listOf(track))
                                    } else {
                                        val resolved = viewModel.resolveDiscoverTrack(track)
                                        viewModel.audioController.appendRelatedTracks(active.path, listOf(resolved))
                                        val suffix = if (resolved.isLocalLosslessMatch) " (Local Lossless)" else ""
                                        scope.launch { snackbarHostState.showSnackbar("Added \"${track.title}\"$suffix to queue") }
                                    }
                                },
                                onEnableStreaming = {
                                    scope.launch {
                                        viewModel.settingsRepository.updateYtStreamingOptions(enabled = true)
                                    }
                                },
                                onToggleLocalLossless = { enabled ->
                                    viewModel.setDiscoverLocalLosslessEnabled(enabled)
                                },
                                onRefreshLocalLossless = {
                                    viewModel.refreshLocalLosslessLibrary(force = true)
                                },
                                onSelectQuality = { tier ->
                                    viewModel.setYtAudioQuality(tier)
                                },
                                onToggleAutoplay = { enabled ->
                                    viewModel.setAutoplayEnabled(enabled)
                                },
                                onLoadMore = {
                                    viewModel.loadMoreDiscoverTracks()
                                },
                                onOpenDiagnostics = { overlayStack.add(OverlayRoute.Diagnostics) },
                                onOpenYtSettings = { overlayStack.add(OverlayRoute.Settings(openYtStreaming = true)) },
                                onOpenNowPlaying = { overlayStack.add(OverlayRoute.NowPlaying) },
                                onBack = { overlayStack.removeAt(overlayStack.lastIndex) },
                            )
                            OverlayRoute.Transfers -> TransferManagerScreen(
                                transfers = transfers,
                                transferManager = viewModel.transferManager,
                                onBack = { overlayStack.removeAt(overlayStack.lastIndex) },
                            )
                            OverlayRoute.Diagnostics -> DiagnosticsScreen(
                                viewModel = viewModel,
                                onBack = { overlayStack.removeAt(overlayStack.lastIndex) },
                            )
                            OverlayRoute.About -> AboutScreen(
                                onBack = { overlayStack.removeAt(overlayStack.lastIndex) }
                            )
                            OverlayRoute.Search -> if (activeApi != null) {
                                SearchScreen(
                                    api = activeApi,
                                    onOpenResult = { item ->
                                        val vol = item.volume.takeIf { it != "internal" }
                                        when {
                                            item.isDirectory -> {
                                                viewModel.recordActivity("folder", item.name, item.path, vol)
                                                filesInitialVolume = vol
                                                filesInitialPath = item.path
                                                selectedTab = MainTab.FILES
                                                overlayStack.clear()
                                            }
                                            MediaExtensions.isAudio(item.name) -> {
                                                viewModel.recordActivity("track", item.name, item.path, vol)
                                                miniPlayerDismissed = false
                                                viewModel.playHostOrLocalPlaylist(listOf(item), 0)
                                                overlayStack.add(OverlayRoute.NowPlaying)
                                            }
                                            MediaExtensions.isVideo(item.name) -> {
                                                viewModel.recordActivity("file", item.name, item.path, vol)
                                                overlayStack.add(OverlayRoute.VideoPlayer(item))
                                            }
                                            MediaExtensions.isPhoto(item.name) -> {
                                                viewModel.recordActivity("file", item.name, item.path, vol)
                                                overlayStack.add(OverlayRoute.PhotoViewer(listOf(item), 0))
                                            }
                                            else -> {
                                                viewModel.recordActivity("file", item.name, item.path, vol)
                                                viewModel.transferManager.enqueueDownload(activeApi, item.path, vol, item.name, item.size) { file ->
                                                    viewModel.transferManager.openDownloadedFile(context, file.absolutePath)
                                                }
                                                scope.launch { snackbarHostState.showSnackbar("Downloading ${item.name}…") }
                                            }
                                        }
                                    },
                                    onBack = { overlayStack.removeAt(overlayStack.lastIndex) },
                                )
                            }
                            OverlayRoute.Documents -> if (activeApi != null) {
                                DocumentsBrowserScreen(
                                    api = activeApi,
                                    transferManager = viewModel.transferManager,
                                    onBack = { overlayStack.removeAt(overlayStack.lastIndex) },
                                )
                            }
                            is OverlayRoute.Photos -> if (activeApi != null) {
                                PhotoGalleryScreen(
                                    api = activeApi,
                                    imageLoader = imageLoader,
                                    favorites = settings.photoFavorites,
                                    initialFavoritesOnly = route.favoritesOnly,
                                    onToggleFavorite = { vol, path -> viewModel.togglePhotoFavorite(vol, path) },
                                    onOpenViewer = { photos, idx -> overlayStack.add(OverlayRoute.PhotoViewer(photos, idx)) },
                                    onBack = { overlayStack.removeAt(overlayStack.lastIndex) },
                                )
                            }
                            is OverlayRoute.PhotoViewer -> if (activeApi != null) {
                                PhotoViewerScreen(
                                    api = activeApi,
                                    imageLoader = imageLoader,
                                    transferManager = viewModel.transferManager,
                                    photos = route.photos,
                                    initialIndex = route.index,
                                    favorites = settings.photoFavorites,
                                    onToggleFavorite = { vol, path -> viewModel.togglePhotoFavorite(vol, path) },
                                    onBack = { overlayStack.removeAt(overlayStack.lastIndex) },
                                )
                            }
                            is OverlayRoute.MediaLibrary -> if (activeApi != null) {
                                MediaBrowserScreen(
                                    api = activeApi,
                                    imageLoader = imageLoader,
                                    category = route.category,
                                    showYtMusic = settings.ytStreamingEnabled,
                                    onOpenYtMusic = { overlayStack.add(OverlayRoute.YtMusic) },
                                    onPlayAudio = { tracks, idx ->
                                        val track = tracks.getOrNull(idx)
                                        if (track != null) {
                                            viewModel.recordActivity("track", track.name, track.path, track.volume.takeIf { it != "internal" })
                                        }
                                        miniPlayerDismissed = false
                                        viewModel.playHostOrLocalPlaylist(tracks, idx)
                                        overlayStack.add(OverlayRoute.NowPlaying)
                                    },
                                    onPlayVideo = { video ->
                                        viewModel.recordActivity("file", video.name, video.path, video.volume.takeIf { it != "internal" })
                                        overlayStack.add(OverlayRoute.VideoPlayer(video))
                                    },
                                    onBack = { overlayStack.removeAt(overlayStack.lastIndex) },
                                )
                            }
                            OverlayRoute.NowPlaying -> Unit
                            is OverlayRoute.VideoPlayer -> if (activeApi != null) {
                                VideoPlayerScreen(
                                    api = activeApi,
                                    video = route.video,
                                    settings = settings,
                                    onUpdateVideoSettings = { timeout, seek, speed, fit, gestures, pinch, tapToggle, autoFs ->
                                        scope.launch {
                                            viewModel.settingsRepository.updateVideoOptions(
                                                controlsTimeoutSeconds = timeout,
                                                seekSeconds = seek,
                                                defaultSpeed = speed,
                                                fitMode = fit,
                                                gesturesEnabled = gestures,
                                                pinchZoomEnabled = pinch,
                                                tapToTogglePlayback = tapToggle,
                                                autoFullscreen = autoFs,
                                            )
                                        }
                                    },
                                    onBack = { overlayStack.removeAt(overlayStack.lastIndex) },
                                )
                            }
                        }
                    }
                }
            }

            // Dedicated Android App-Opening Container-Transform layer for NowPlaying:
            // Morphs exact bounding box from/to Mini Player (when enabled/visible)
            // OR directly from/to the circular Music dock button (when Mini Player is disabled),
            // keeping inner artwork & controls aspect-correct without squashing.
            if (nowPlayingMounted) {
                val p = nowPlayingProgress.value.coerceIn(0f, 1f)
                val isMiniPlayerTarget = settings.showMiniPlayer &&
                    (!miniPlayerDismissed || audioState.isPlaying) &&
                    audioState.currentTrack != null &&
                    miniPlayerBounds != null

                val fallbackWidthPx = with(density) { 64.dp.toPx() }
                val rw = rootSizePx.width.toFloat().coerceAtLeast(1f)
                val rh = rootSizePx.height.toFloat().coerceAtLeast(1f)
                val fallbackRect = Rect(
                    left = rw - fallbackWidthPx - with(density) { 14.dp.toPx() },
                    top = rh - fallbackWidthPx - with(density) { 20.dp.toPx() },
                    right = rw - with(density) { 14.dp.toPx() },
                    bottom = rh - with(density) { 20.dp.toPx() },
                )
                val targetAnchor = if (isMiniPlayerTarget) {
                    miniPlayerBounds ?: fallbackRect
                } else {
                    musicButtonBounds ?: fallbackRect
                }
                val collapsedCornerDp = if (isMiniPlayerTarget) 18f else (minOf(targetAnchor.width, targetAnchor.height) / density.density / 2f).coerceAtLeast(28f)

                val curLeft = lerp(targetAnchor.left, 0f, p)
                val curTop = lerp(targetAnchor.top, 0f, p)
                val curWidthPx = lerp(targetAnchor.width.coerceAtLeast(1f), rw, p)
                val curHeightPx = lerp(targetAnchor.height.coerceAtLeast(1f), rh, p)
                val curCornerDp = lerp(collapsedCornerDp, 0f, p).dp
                val containerAlpha = (p / 0.14f).coerceIn(0f, 1f)
                val contentAlpha = ((p - 0.12f) / 0.34f).coerceIn(0f, 1f)
                val contentScale = lerp(if (isMiniPlayerTarget) 0.94f else 0.76f, 1f, p)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.48f * p)),
                ) {
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(curLeft.roundToInt(), curTop.roundToInt()) }
                            .width(with(density) { curWidthPx.toDp() })
                            .height(with(density) { curHeightPx.toDp() })
                            .graphicsLayer { alpha = containerAlpha }
                            .clip(RoundedCornerShape(curCornerDp))
                            .background(Color(0xFF140B14)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = contentAlpha
                                    scaleX = contentScale
                                    scaleY = contentScale
                                },
                        ) {
                            NowPlayingScreen(
                                playbackState = audioState,
                                audioController = viewModel.audioController,
                                showStatsForNerds = settings.showStatsForNerds,
                                enableGestures = settings.enableNowPlayingGestures,
                                shortcuts = settings.floatingShortcuts,
                                imageLoader = imageLoader,
                                showArtwork = settings.ytShowArtwork,
                                lyricsEnabled = settings.ytLyricsEnabled,
                                lyrics = lyrics,
                                ytAudioQuality = settings.ytAudioQuality,
                                autoplayEnabled = settings.ytAutoQueueRelated,
                                onToggleAutoplay = { enabled -> viewModel.setAutoplayEnabled(enabled) },
                                onLoadMoreSuggestions = { viewModel.requestMoreAutoplaySuggestions() },
                                onChangeYtAudioQuality = { tier -> viewModel.setYtAudioQuality(tier) },
                                onSelectShortcut = { shortcutId ->
                                    dismissNowPlaying { openShortcut(shortcutId) }
                                },
                                onBack = { dismissNowPlaying() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainConnectedShell(
    viewModel: PocketLinkViewModel,
    activeApi: com.example.data.network.PocketLinkApi,
    connection: ConnectionUiState,
    settings: com.example.data.model.AppSettingsState,
    audioState: com.example.media.AudioPlaybackState,
    transfers: List<com.example.data.model.TransferItem>,
    imageLoader: coil.ImageLoader,
    snackbarHostState: SnackbarHostState,
    selectedTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    filesInitialVolume: String?,
    filesInitialPath: String,
    onUpdateFilesLocation: (String?, String) -> Unit,
    miniPlayerDismissed: Boolean,
    onSetMiniPlayerDismissed: (Boolean) -> Unit,
    dockQuickMenuExpanded: Boolean,
    onSetDockQuickMenuExpanded: (Boolean) -> Unit,
    overlayStackEmpty: Boolean,
    onOpenOverlay: (OverlayRoute) -> Unit,
    onOpenShortcut: (String) -> Unit,
    onOpenActivity: (HomeActivityItem) -> Unit,
    onUploadFiles: () -> Unit,
    onQuickThemeChange: (com.example.data.model.AppThemeMode, Boolean) -> Unit,
    onMusicButtonBounds: (Rect) -> Unit,
    onMiniPlayerBounds: (Rect) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val homeDrawerState = rememberDrawerState(DrawerValue.Closed)
    var homeOnline by remember { mutableStateOf<Boolean?>(null) }
    var homeLatencyMs by remember { mutableStateOf<Long?>(null) }
    var homeTelemetry by remember { mutableStateOf<HostSystemTelemetry?>(null) }
    var homeVolumes by remember { mutableStateOf<List<StorageVolume>>(emptyList()) }

    BackHandler(enabled = overlayStackEmpty && (homeDrawerState.isOpen || dockQuickMenuExpanded || selectedTab != MainTab.HOME)) {
        when {
            homeDrawerState.isOpen -> scope.launch { homeDrawerState.close() }
            dockQuickMenuExpanded -> onSetDockQuickMenuExpanded(false)
            selectedTab != MainTab.HOME -> onSelectTab(MainTab.HOME)
        }
    }

    val visibleMainTabs = remember(settings.navigationOrder, settings.visibleNavigationItems) {
        settings.navigationOrder
            .filter { it != "browse" && it in settings.visibleNavigationItems }
            .mapNotNull { id -> MainTab.entries.firstOrNull { it.id == id } }
            .ifEmpty { listOf(MainTab.HOME) }
    }

    ModalNavigationDrawer(
        drawerState = homeDrawerState,
        gesturesEnabled = selectedTab == MainTab.HOME && overlayStackEmpty && !dockQuickMenuExpanded,
        drawerContent = {
            HomeNavigationDrawerSheet(
                drawerState = homeDrawerState,
                api = activeApi,
                profile = connection.activeProfile,
                settings = settings,
                online = homeOnline,
                latencyMs = homeLatencyMs,
                telemetry = homeTelemetry,
                volumes = homeVolumes,
                transfers = transfers,
                onSwitchServer = { viewModel.disconnect() },
                onOpenHostSetup = { onOpenOverlay(OverlayRoute.HostSetup) },
                onOpenFiles = { vol, path ->
                    onUpdateFilesLocation(vol, path)
                    onSelectTab(MainTab.FILES)
                },
                onOpenPhotos = { fav -> onOpenOverlay(OverlayRoute.Photos(fav)) },
                onOpenMusic = { onOpenOverlay(OverlayRoute.MediaLibrary(MediaCategory.MUSIC)) },
                onOpenYtMusic = {
                    if (settings.ytStreamingEnabled) onOpenOverlay(OverlayRoute.YtMusic)
                    else onOpenOverlay(OverlayRoute.Settings(openYtStreaming = true))
                },
                onOpenVideos = { onOpenOverlay(OverlayRoute.MediaLibrary(MediaCategory.VIDEO)) },
                onOpenDocuments = { onOpenOverlay(OverlayRoute.Documents) },
                onOpenSearch = { onOpenOverlay(OverlayRoute.Search) },
                onOpenTransfers = { onOpenOverlay(OverlayRoute.Transfers) },
                onOpenSettings = { onOpenOverlay(OverlayRoute.Settings()) },
                onQuickThemeChange = onQuickThemeChange,
            )
        },
    ) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 680.dp

        val tabContent: @Composable (Modifier) -> Unit = { mod ->
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val forward = targetState.ordinal >= initialState.ordinal
                    if (forward) {
                        (slideInHorizontally(tween(240)) { it / 6 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(200)) { -it / 8 } + fadeOut(tween(180)))
                    } else {
                        (slideInHorizontally(tween(240)) { -it / 6 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(200)) { it / 8 } + fadeOut(tween(180)))
                    }
                },
                label = "main_tab_transition",
                modifier = mod.pointerInput(visibleMainTabs, selectedTab) {
                    var totalDrag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onHorizontalDrag = { _, delta -> totalDrag += delta },
                        onDragEnd = {
                            if (kotlin.math.abs(totalDrag) < 120f) return@detectHorizontalDragGestures
                            val idx = visibleMainTabs.indexOf(selectedTab)
                            if (idx < 0) return@detectHorizontalDragGestures
                            val nextIdx = if (totalDrag < 0f) {
                                (idx + 1).coerceAtMost(visibleMainTabs.lastIndex)
                            } else {
                                (idx - 1).coerceAtLeast(0)
                            }
                            onSelectTab(visibleMainTabs[nextIdx])
                        },
                    )
                },
            ) { tab ->
                when (tab) {
                    MainTab.HOME -> HomeDashboardScreen(
                        api = activeApi,
                        profile = connection.activeProfile,
                        settings = settings,
                        transfers = transfers,
                        transferManager = viewModel.transferManager,
                        onSwitchServer = { viewModel.disconnect() },
                        onOpenHostSetup = { onOpenOverlay(OverlayRoute.HostSetup) },
                        onOpenFiles = { vol, path ->
                            onUpdateFilesLocation(vol, path)
                            onSelectTab(MainTab.FILES)
                        },
                        onOpenPhotos = { fav -> onOpenOverlay(OverlayRoute.Photos(fav)) },
                        onOpenMusic = { onOpenOverlay(OverlayRoute.MediaLibrary(MediaCategory.MUSIC)) },
                        onOpenYtMusic = {
                            if (settings.ytStreamingEnabled) onOpenOverlay(OverlayRoute.YtMusic)
                            else onOpenOverlay(OverlayRoute.Settings(openYtStreaming = true))
                        },
                        onOpenVideos = { onOpenOverlay(OverlayRoute.MediaLibrary(MediaCategory.VIDEO)) },
                        onOpenDocuments = { onOpenOverlay(OverlayRoute.Documents) },
                        onOpenSearch = { onOpenOverlay(OverlayRoute.Search) },
                        onOpenTransfers = { onOpenOverlay(OverlayRoute.Transfers) },
                        onOpenSettings = { onOpenOverlay(OverlayRoute.Settings()) },
                        onUploadFiles = onUploadFiles,
                        onOpenRecentFile = { recent ->
                            val item = IndexedFile(
                                volume = recent.volume ?: "internal",
                                path = recent.path,
                                name = recent.file.name,
                                type = recent.file.type,
                                size = recent.file.size,
                                modified = recent.file.modified,
                                mime = "",
                            )
                            when {
                                MediaExtensions.isAudio(recent.file.name) -> {
                                    viewModel.recordActivity("track", recent.file.name, recent.path, recent.volume)
                                    onSetMiniPlayerDismissed(false)
                                    viewModel.playHostOrLocalPlaylist(listOf(item), 0)
                                    onOpenOverlay(OverlayRoute.NowPlaying)
                                }
                                MediaExtensions.isVideo(recent.file.name) -> {
                                    viewModel.recordActivity("file", recent.file.name, recent.path, recent.volume)
                                    onOpenOverlay(OverlayRoute.VideoPlayer(item))
                                }
                                MediaExtensions.isPhoto(recent.file.name) -> {
                                    viewModel.recordActivity("file", recent.file.name, recent.path, recent.volume)
                                    onOpenOverlay(OverlayRoute.PhotoViewer(listOf(item), 0))
                                }
                                else -> {
                                    viewModel.recordActivity("file", recent.file.name, recent.path, recent.volume)
                                    onUpdateFilesLocation(recent.volume, recent.parentPath)
                                    onSelectTab(MainTab.FILES)
                                }
                            }
                        },
                        onOpenActivityItem = onOpenActivity,
                        onSaveQuickActions = { next ->
                            scope.launch { viewModel.settingsRepository.setQuickActions(next) }
                        },
                        onQuickThemeChange = onQuickThemeChange,
                        onOpenDrawer = { scope.launch { homeDrawerState.open() } },
                        onHostSummaryChanged = { online, latencyMs, telemetry, volumes ->
                            homeOnline = online
                            homeLatencyMs = latencyMs
                            homeTelemetry = telemetry
                            homeVolumes = volumes
                        },
                    )

                    MainTab.EXPLORE -> ExploreScreen(
                        showYtMusic = settings.ytStreamingEnabled,
                        onOpenYtMusic = { onOpenOverlay(OverlayRoute.YtMusic) },
                        onOpenPhotos = { onOpenOverlay(OverlayRoute.Photos(false)) },
                        onOpenMusic = { onOpenOverlay(OverlayRoute.MediaLibrary(MediaCategory.MUSIC)) },
                        onOpenVideos = { onOpenOverlay(OverlayRoute.MediaLibrary(MediaCategory.VIDEO)) },
                        onOpenDocuments = { onOpenOverlay(OverlayRoute.Documents) },
                        onOpenFiles = {
                            onUpdateFilesLocation(null, "")
                            onSelectTab(MainTab.FILES)
                        },
                        onOpenSearch = { onOpenOverlay(OverlayRoute.Search) },
                        onOpenDrives = {
                            onUpdateFilesLocation(null, "")
                            onSelectTab(MainTab.FILES)
                        },
                    )

                    MainTab.FILES -> FileManagerScreen(
                        api = activeApi,
                        transferManager = viewModel.transferManager,
                        initialVolume = filesInitialVolume,
                        initialPath = filesInitialPath,
                        onOpenAudio = { tracks, idx ->
                            tracks.getOrNull(idx)?.let { t ->
                                viewModel.recordActivity("track", t.name, t.path, t.volume.takeIf { it != "internal" })
                            }
                            onSetMiniPlayerDismissed(false)
                            viewModel.playHostOrLocalPlaylist(tracks, idx)
                            onOpenOverlay(OverlayRoute.NowPlaying)
                        },
                        onOpenVideo = { video ->
                            viewModel.recordActivity("file", video.name, video.path, video.volume.takeIf { it != "internal" })
                            onOpenOverlay(OverlayRoute.VideoPlayer(video))
                        },
                        onOpenPhoto = { photos, idx ->
                            photos.getOrNull(idx)?.let { p ->
                                viewModel.recordActivity("file", p.name, p.path, p.volume.takeIf { it != "internal" })
                            }
                            onOpenOverlay(OverlayRoute.PhotoViewer(photos, idx))
                        },
                    )
                }
            }
        }

        if (useRail) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail {
                    MainTab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = selectedTab == tab,
                            onClick = { onSelectTab(tab) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            modifier = Modifier.testTag("nav_tab_${tab.id}"),
                        )
                    }
                    NavigationRailItem(
                        selected = false,
                        onClick = {
                            viewModel.openOrStartMusicFromDock(
                                onOpenNowPlaying = { onOpenOverlay(OverlayRoute.NowPlaying) },
                                onOpenMusicLibrary = { onOpenOverlay(OverlayRoute.MediaLibrary(MediaCategory.MUSIC)) },
                            )
                        },
                        icon = { Icon(Icons.Rounded.MusicNote, contentDescription = "Music") },
                        label = { Text("Music") },
                    )
                    NavigationRailItem(
                        selected = false,
                        onClick = { onOpenOverlay(OverlayRoute.Transfers) },
                        icon = { Icon(Icons.Rounded.SwapHoriz, contentDescription = "Transfers") },
                        label = { Text("Transfers") },
                    )
                    NavigationRailItem(
                        selected = false,
                        onClick = { onOpenOverlay(OverlayRoute.Settings()) },
                        icon = { Icon(Icons.Rounded.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                    )
                }
                val railBackdrop = rememberBackdropGlassState(settings.useLiquidGlass)
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    tabContent(Modifier.fillMaxSize().backdropGlassSource(railBackdrop))
                    CompositionLocalProvider(LocalBackdropGlassState provides railBackdrop) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(bottom = 8.dp),
                        ) {
                            SnackbarHost(hostState = snackbarHostState)
                            PersistentMiniPlayer(
                                state = audioState,
                                visible = settings.showMiniPlayer && (!miniPlayerDismissed || audioState.isPlaying),
                                useLiquidGlass = settings.useLiquidGlass,
                                imageLoader = imageLoader,
                                showArtwork = settings.ytShowArtwork,
                                onOpenNowPlaying = { onOpenOverlay(OverlayRoute.NowPlaying) },
                                onTogglePlayPause = { viewModel.audioController.togglePlayPause() },
                                onPrevious = { viewModel.audioController.previous() },
                                onNext = { viewModel.audioController.next() },
                                onDismiss = { onSetMiniPlayerDismissed(true) },
                                onMiniPlayerBounds = onMiniPlayerBounds,
                            )
                        }
                    }
                }
            }
        } else {
            val dockBackdrop = rememberBackdropGlassState(settings.useLiquidGlass)
            val contentBlurRadius by animateDpAsState(
                targetValue = if (dockQuickMenuExpanded) 18.dp else 0.dp,
                animationSpec = tween(200),
                label = "dock_menu_blur",
            )
            Box(modifier = Modifier.fillMaxSize()) {
                tabContent(
                    Modifier
                        .fillMaxSize()
                        .backdropGlassSource(dockBackdrop)
                        .liquidBackdropBlur(contentBlurRadius)
                )

                // Soft atmospheric backdrop behind the floating dock / mini player
                FloatingDockScrimBackdrop(
                    useLiquidGlass = settings.useLiquidGlass,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .height(if (settings.showMiniPlayer && audioState.currentTrack != null && (!miniPlayerDismissed || audioState.isPlaying)) 180.dp else 112.dp),
                )

                AnimatedVisibility(
                    visible = dockQuickMenuExpanded,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(150)),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.36f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                onSetDockQuickMenuExpanded(false)
                            }
                    )
                }

                CompositionLocalProvider(LocalBackdropGlassState provides dockBackdrop) {
                    AnimatedVisibility(
                        visible = dockQuickMenuExpanded,
                        enter = fadeIn(tween(200)) +
                            slideInVertically(initialOffsetY = { it / 4 }, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)) +
                            scaleIn(initialScale = 0.90f, transformOrigin = TransformOrigin(0.9f, 1f)),
                        exit = fadeOut(tween(150)) +
                            slideOutVertically(targetOffsetY = { it / 5 }) +
                            scaleOut(targetScale = 0.92f, transformOrigin = TransformOrigin(0.9f, 1f)),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .padding(end = 14.dp, bottom = 92.dp),
                    ) {
                        MusicQuickAccessMenu(
                            shortcutIds = settings.floatingShortcuts,
                            useLiquidGlass = settings.useLiquidGlass,
                            onSelect = onOpenShortcut,
                            onOpenNowPlaying = {
                                onSetDockQuickMenuExpanded(false)
                                onSetMiniPlayerDismissed(false)
                                viewModel.openOrStartMusicFromDock(
                                    onOpenNowPlaying = { onOpenOverlay(OverlayRoute.NowPlaying) },
                                    onOpenMusicLibrary = { onOpenOverlay(OverlayRoute.MediaLibrary(MediaCategory.MUSIC)) },
                                )
                            },
                        )
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                    ) {
                        SnackbarHost(hostState = snackbarHostState)
                        PersistentMiniPlayer(
                            state = audioState,
                            visible = settings.showMiniPlayer && (!miniPlayerDismissed || audioState.isPlaying),
                            useLiquidGlass = settings.useLiquidGlass,
                            imageLoader = imageLoader,
                            showArtwork = settings.ytShowArtwork,
                            onOpenNowPlaying = { onOpenOverlay(OverlayRoute.NowPlaying) },
                            onTogglePlayPause = { viewModel.audioController.togglePlayPause() },
                            onPrevious = { viewModel.audioController.previous() },
                            onNext = { viewModel.audioController.next() },
                            onDismiss = { onSetMiniPlayerDismissed(true) },
                            onMiniPlayerBounds = onMiniPlayerBounds,
                        )
                        if (settings.showMiniPlayer && audioState.currentTrack != null && (!miniPlayerDismissed || audioState.isPlaying)) {
                            Spacer(Modifier.height(4.dp))
                        }
                        FloatingNavigationDock(
                            order = settings.navigationOrder,
                            visibleItems = settings.visibleNavigationItems,
                            selectedTabId = selectedTab.id,
                            useLiquidGlass = settings.useLiquidGlass,
                            isPlaying = audioState.isPlaying,
                            hasMedia = audioState.currentTrack != null,
                            isVideo = false,
                            expandedMenuOpen = dockQuickMenuExpanded,
                            onSelectTab = { id ->
                                onSetDockQuickMenuExpanded(false)
                                onSelectTab(MainTab.entries.firstOrNull { it.id == id } ?: MainTab.HOME)
                            },
                            onBrowsePressed = {
                                if (dockQuickMenuExpanded) {
                                    onSetDockQuickMenuExpanded(false)
                                } else {
                                    onSetMiniPlayerDismissed(false)
                                    viewModel.openOrStartMusicFromDock(
                                        onOpenNowPlaying = { onOpenOverlay(OverlayRoute.NowPlaying) },
                                        onOpenMusicLibrary = { onOpenOverlay(OverlayRoute.MediaLibrary(MediaCategory.MUSIC)) },
                                    )
                                }
                            },
                            onBrowseLongPress = {
                                if (settings.floatingShortcutLongPressEnabled) {
                                    onSetDockQuickMenuExpanded(!dockQuickMenuExpanded)
                                }
                            },
                            onSwipeMusicPrevious = {
                                onSetMiniPlayerDismissed(false)
                                viewModel.skipTrackFromDock(next = false) { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            },
                            onSwipeMusicNext = {
                                onSetMiniPlayerDismissed(false)
                                viewModel.skipTrackFromDock(next = true) { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            },
                            onMusicButtonBounds = onMusicButtonBounds,
                        )
                    }
                }
            }
        }
    }
    }
}

