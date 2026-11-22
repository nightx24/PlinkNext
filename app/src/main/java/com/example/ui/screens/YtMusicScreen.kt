package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Queue
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.model.AppSettingsState
import com.example.data.model.YtAudioQuality
import com.example.data.network.LocalLosslessMatcher
import com.example.data.network.YtTrack
import com.example.media.AudioPlaybackState
import com.example.ui.YtMusicUiState
import com.example.ui.components.AnimatedEqualizer
import com.example.ui.components.PocketLinkTopBar
import com.example.ui.theme.LocalPocketLinkColors
import com.example.ui.theme.VioletAccent

private val QuickGenres = listOf("Lo-Fi", "Synthwave", "Chill", "Top Hits", "Acoustic", "Ambient", "Indie")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YtMusicScreen(
    ytState: YtMusicUiState,
    settings: AppSettingsState,
    playbackState: AudioPlaybackState,
    imageLoader: ImageLoader,
    onLoadHome: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: (String) -> Unit,
    onPlayTrack: (YtTrack, List<YtTrack>) -> Unit,
    onQueueTrack: (YtTrack) -> Unit,
    onEnableStreaming: () -> Unit,
    onToggleLocalLossless: (Boolean) -> Unit = {},
    onRefreshLocalLossless: () -> Unit = {},
    onSelectQuality: (YtAudioQuality) -> Unit = {},
    onToggleAutoplay: (Boolean) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenYtSettings: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalPocketLinkColors.current
    val keyboard = LocalSoftwareKeyboardController.current
    val activeVideoId = playbackState.currentTrack
        ?.takeIf { it.isYouTubeStream || it.isLocalLosslessMatch }
        ?.path

    LaunchedEffect(settings.ytStreamingEnabled, settings.ytCountryCode) {
        if (settings.ytStreamingEnabled) {
            onLoadHome(false)
        }
    }

    LaunchedEffect(settings.ytLocalLosslessEnabled) {
        if (settings.ytLocalLosslessEnabled && !ytState.losslessScannedOnce && !ytState.scanningLossless) {
            onRefreshLocalLossless()
        }
    }

    Scaffold(
        topBar = {
            PocketLinkTopBar(
                title = "Discover",
                subtitle = if (settings.ytStreamingEnabled) {
                    buildString {
                        append("${settings.ytAudioQuality.shortLabel} · ${settings.ytCountryCode} · ")
                        append(if (settings.ytLocalLosslessEnabled) "Lossless + Opus" else "Opus First")
                    }
                } else {
                    "Streaming disabled in Settings"
                },
                onBack = onBack,
                actions = {
                    if (activeVideoId != null) {
                        IconButton(onClick = onOpenNowPlaying) {
                            Icon(Icons.Rounded.GraphicEq, contentDescription = "Open Now Playing")
                        }
                    }
                    if (settings.ytStreamingEnabled) {
                        IconButton(onClick = { onLoadHome(true) }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh charts")
                        }
                    }
                    IconButton(
                        onClick = onOpenDiagnostics,
                        modifier = Modifier.testTag("yt_music_diagnostics_btn"),
                    ) {
                        Icon(Icons.Rounded.BugReport, contentDescription = "Open Diagnostics & Log Generator")
                    }
                    IconButton(
                        onClick = onOpenYtSettings,
                        modifier = Modifier.testTag("yt_music_settings_btn"),
                    ) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Discover streaming settings")
                    }
                },
            )
        }
    ) { innerPadding ->
        if (!settings.ytStreamingEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(26.dp),
                    modifier = Modifier
                        .widthIn(max = 460.dp)
                        .fillMaxWidth()
                        .border(1.dp, colors.cardBorder, RoundedCornerShape(26.dp)),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(VioletAccent.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.LibraryMusic,
                                contentDescription = null,
                                tint = VioletAccent,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                        Text(
                            text = "Discover streaming is turned off",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = "Turn on Discover (powered by NewPipe) to search songs, browse regional charts, stream direct audio, and follow synced lyrics.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = onEnableStreaming,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("yt_music_enable_btn"),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Enable Discover", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onOpenYtSettings,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        ) {
                            Icon(Icons.Rounded.Tune, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Customize Discover Settings")
                        }
                    }
                }
            }
            return@Scaffold
        }

        val showingSearchResults = ytState.lastSearchedQuery.isNotBlank() || ytState.searching || ytState.searchResults.isNotEmpty()
        val displayTracks = if (showingSearchResults) ytState.searchResults else ytState.homeFeed.trending
        val losslessMatchBadges = remember(
            settings.ytLocalLosslessEnabled,
            ytState.localLosslessEntries,
            displayTracks,
            ytState.homeFeed.quickPicks,
        ) {
            if (!settings.ytLocalLosslessEnabled || ytState.localLosslessEntries.isEmpty()) {
                emptyMap()
            } else {
                val allVisible = (displayTracks + ytState.homeFeed.quickPicks).distinctBy { it.videoId }
                buildMap {
                    for (track in allVisible) {
                        val matched = LocalLosslessMatcher.findBestMatch(track, ytState.localLosslessEntries)
                        if (matched != null) {
                            put(track.videoId, matched.entry.formatLabel)
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = ytState.searchQuery,
                        onValueChange = onQueryChange,
                        placeholder = { Text("Search songs, artists or moods…") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        trailingIcon = {
                            if (ytState.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onQueryChange("") }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                keyboard?.hide()
                                onSubmitSearch(ytState.searchQuery)
                            }
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("yt_music_search_input"),
                    )
                }

                item {
                    DiscoverLocalLosslessCard(
                        enabled = settings.ytLocalLosslessEnabled,
                        scanning = ytState.scanningLossless,
                        losslessCount = ytState.localLosslessEntries.size,
                        matchedVisibleCount = losslessMatchBadges.size,
                        onToggle = onToggleLocalLossless,
                        onRefresh = onRefreshLocalLossless,
                    )
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Opus Quality",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        YtAudioQuality.entries.forEach { tier ->
                            FilterChip(
                                selected = settings.ytAudioQuality == tier,
                                onClick = { onSelectQuality(tier) },
                                label = { Text(tier.shortLabel, fontWeight = if (settings.ytAudioQuality == tier) FontWeight.Bold else FontWeight.Normal) },
                                modifier = Modifier.testTag("discover_quality_${tier.prefKey}"),
                            )
                        }
                    }
                }

                item {
                    AnimatedVisibility(visible = ytState.suggestions.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, colors.cardBorder, RoundedCornerShape(16.dp)),
                        ) {
                            FlowRow(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                ytState.suggestions.forEach { suggestion ->
                                    AssistChip(
                                        onClick = {
                                            keyboard?.hide()
                                            onSubmitSearch(suggestion)
                                        },
                                        label = { Text(suggestion, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingIcon = {
                                            Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(15.dp))
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        QuickGenres.forEach { genre ->
                            val selected = ytState.lastSearchedQuery.equals(genre, ignoreCase = true)
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    keyboard?.hide()
                                    if (selected) onQueryChange("") else onSubmitSearch(genre)
                                },
                                label = { Text(genre) },
                            )
                        }
                    }
                }

                if (!showingSearchResults && ytState.homeFeed.quickPicks.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Quick Picks",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                            )
                            Surface(
                                onClick = { onToggleAutoplay(!settings.ytAutoQueueRelated) },
                                color = if (settings.ytAutoQueueRelated) colors.violet.copy(alpha = 0.16f)
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(99.dp),
                                modifier = Modifier.testTag("discover_autoplay_chip"),
                            ) {
                                Text(
                                    text = if (settings.ytAutoQueueRelated) "Autoplay ∞ On" else "Autoplay Off",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (settings.ytAutoQueueRelated) colors.violet else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    item {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(ytState.homeFeed.quickPicks, key = { "qp_${it.videoId}" }) { track ->
                                YtQuickPickCard(
                                    track = track,
                                    isActive = activeVideoId == track.videoId,
                                    isPlaying = activeVideoId == track.videoId && playbackState.isPlaying,
                                    showArtwork = settings.ytShowArtwork,
                                    losslessBadge = losslessMatchBadges[track.videoId],
                                    imageLoader = imageLoader,
                                    onClick = {
                                        onPlayTrack(track, ytState.homeFeed.quickPicks)
                                        onOpenNowPlaying()
                                    },
                                )
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (showingSearchResults) {
                                if (ytState.lastSearchedQuery.isNotBlank()) "Results for \"${ytState.lastSearchedQuery}\"" else "Search Results"
                            } else {
                                ytState.homeFeed.sectionTitle
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (displayTracks.isNotEmpty()) {
                            FilledTonalButton(
                                onClick = {
                                    val shuffled = displayTracks.shuffled()
                                    onPlayTrack(shuffled.first(), shuffled)
                                    onOpenNowPlaying()
                                },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Shuffle", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                ytState.errorMessage?.let { err ->
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    if (showingSearchResults) onSubmitSearch(ytState.searchQuery)
                                    else onLoadHome(true)
                                }) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }

                if ((showingSearchResults && ytState.searching) || (!showingSearchResults && ytState.loadingHome && displayTracks.isEmpty())) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 42.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = if (showingSearchResults) "Searching Discover…" else "Loading regional charts…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    itemsIndexed(displayTracks, key = { _, it -> it.videoId }) { index, track ->
                        // Auto-paginate infinitely when scrolling near the end of the loaded list
                        if (index == displayTracks.lastIndex - 2 && !ytState.loadingMore) {
                            LaunchedEffect(displayTracks.size) {
                                onLoadMore()
                            }
                        }
                        val isActive = activeVideoId == track.videoId
                        YtTrackRow(
                            track = track,
                            isActive = isActive,
                            isPlaying = isActive && playbackState.isPlaying,
                            showArtwork = settings.ytShowArtwork,
                            losslessBadge = losslessMatchBadges[track.videoId],
                            imageLoader = imageLoader,
                            onPlay = {
                                onPlayTrack(track, displayTracks)
                                onOpenNowPlaying()
                            },
                            onQueue = { onQueueTrack(track) },
                        )
                    }
                    if (displayTracks.isNotEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (ytState.loadingMore) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Text(
                                            text = "Loading more tracks…",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = onLoadMore,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.testTag("yt_music_load_more_btn"),
                                    ) {
                                        Text("Load more tracks ∞", fontWeight = FontWeight.SemiBold)
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

@Composable
private fun DiscoverLocalLosslessCard(
    enabled: Boolean,
    scanning: Boolean,
    losslessCount: Int,
    matchedVisibleCount: Int,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    val colors = LocalPocketLinkColors.current
    val accent = colors.emerald
    Surface(
        color = if (enabled) accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (enabled) accent.copy(alpha = 0.42f) else colors.cardBorder,
                shape = RoundedCornerShape(18.dp),
            )
            .testTag("discover_local_lossless_card"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (enabled) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.HighQuality,
                    contentDescription = null,
                    tint = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Local Lossless Integration",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (enabled) {
                        Surface(
                            color = accent.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(99.dp),
                        ) {
                            Text(
                                text = when {
                                    scanning -> "Scanning…"
                                    losslessCount > 0 -> "$losslessCount files"
                                    else -> "0 local"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = accent,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = if (enabled) {
                        if (matchedVisibleCount > 0) {
                            "$matchedVisibleCount match(es) here · Plays local FLAC/WAV/ALAC first, else NewPipe Opus."
                        } else {
                            "Detects local FLAC/WAV/ALAC first · Auto-fallback to NewPipe Opus when missing."
                        }
                    } else {
                        "Turn on to detect matching local lossless files before streaming from NewPipe."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (enabled) {
                IconButton(
                    onClick = onRefresh,
                    enabled = !scanning,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("discover_local_lossless_refresh_btn"),
                ) {
                    if (scanning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Rescan local lossless library",
                            tint = accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag("discover_local_lossless_switch"),
            )
        }
    }
}

@Composable
private fun YtQuickPickCard(
    track: YtTrack,
    isActive: Boolean,
    isPlaying: Boolean,
    showArtwork: Boolean,
    losslessBadge: String? = null,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
) {
    val colors = LocalPocketLinkColors.current
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .width(146.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("yt_quick_pick_${track.videoId}"),
        color = if (isActive) VioletAccent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .border(
                    width = if (isActive) 1.5.dp else 1.dp,
                    color = if (isActive) VioletAccent.copy(alpha = 0.6f) else colors.cardBorder,
                    shape = RoundedCornerShape(18.dp),
                )
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(126.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(VioletAccent.copy(alpha = 0.25f), MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (showArtwork && track.thumbnailUrl.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(track.thumbnailUrl)
                            .size(360)
                            .crossfade(true)
                            .build(),
                        imageLoader = imageLoader,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = VioletAccent,
                        modifier = Modifier.size(34.dp),
                    )
                }
                if (!losslessBadge.isNullOrBlank()) {
                    Surface(
                        color = colors.emerald.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                    ) {
                        Text(
                            text = losslessBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedEqualizer(color = Color.White, modifier = Modifier.scale(0.65f))
                    }
                }
            }
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun YtTrackRow(
    track: YtTrack,
    isActive: Boolean,
    isPlaying: Boolean,
    showArtwork: Boolean,
    losslessBadge: String? = null,
    imageLoader: ImageLoader,
    onPlay: () -> Unit,
    onQueue: () -> Unit,
) {
    val colors = LocalPocketLinkColors.current
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onPlay)
            .testTag("yt_track_card_${track.videoId}"),
        color = if (isActive) VioletAccent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (isActive) 1.4.dp else 1.dp,
                    color = if (isActive) VioletAccent.copy(alpha = 0.55f) else colors.cardBorder,
                    shape = RoundedCornerShape(18.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(VioletAccent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                if (showArtwork && track.thumbnailUrl.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(track.thumbnailUrl)
                            .size(220)
                            .crossfade(true)
                            .build(),
                        imageLoader = imageLoader,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = VioletAccent)
                }
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedEqualizer(color = Color.White, modifier = Modifier.scale(0.75f))
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive) VioletAccent else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!losslessBadge.isNullOrBlank()) {
                        Surface(
                            color = colors.emerald.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                text = losslessBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.emerald,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                val meta = buildString {
                    append(track.artist)
                    if (track.album.isNotBlank()) {
                        append(" · ")
                        append(track.album)
                    }
                    if (track.durationText.isNotBlank()) {
                        append(" · ")
                        append(track.durationText)
                    }
                }
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(
                onClick = onQueue,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                    contentDescription = "Add ${track.title} to queue",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
