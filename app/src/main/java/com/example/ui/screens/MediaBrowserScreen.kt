package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.model.IndexedFile
import com.example.data.model.MediaCategory
import com.example.data.model.formatBytes
import com.example.data.network.PocketLinkApi
import com.example.ui.components.PocketLinkTopBar
import com.example.ui.theme.TealAccent
import com.example.ui.theme.VioletAccent
import kotlinx.coroutines.launch

@Composable
fun MediaBrowserScreen(
    api: PocketLinkApi,
    imageLoader: ImageLoader,
    category: MediaCategory,
    onPlayAudio: (tracks: List<IndexedFile>, index: Int) -> Unit,
    onPlayVideo: (video: IndexedFile) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isMusic = category == MediaCategory.MUSIC

    var items by remember { mutableStateOf<List<IndexedFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var folderMode by remember { mutableStateOf(false) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var gridMode by remember { mutableStateOf(!isMusic) }

    suspend fun loadMedia() {
        loading = true
        val indexed = runCatching { api.indexedMedia(category, limit = 500) }.getOrNull()
        items = if (indexed != null && indexed.items.isNotEmpty()) {
            indexed.items
        } else {
            api.walkMediaFallback(category)
        }
        loading = false
    }

    LaunchedEffect(api.baseUrl, category) { loadMedia() }

    val folders = remember(items) {
        items.groupBy { it.parentPath.ifBlank { "Root" } }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }

    val displayed = remember(items, query, folderMode, selectedFolder) {
        val base = if (folderMode && selectedFolder != null) {
            folders[selectedFolder].orEmpty()
        } else {
            items
        }
        if (query.isBlank()) base else base.filter {
            it.name.contains(query.trim(), ignoreCase = true) ||
                it.parentPath.contains(query.trim(), ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            PocketLinkTopBar(
                title = if (folderMode && selectedFolder != null) selectedFolder!! else category.label,
                subtitle = "${displayed.size} items",
                onBack = if (folderMode && selectedFolder != null) {
                    { selectedFolder = null }
                } else onBack,
                actions = {
                    IconButton(onClick = { gridMode = !gridMode }) {
                        Icon(if (gridMode) Icons.Rounded.ViewList else Icons.Rounded.GridView, contentDescription = "Toggle layout")
                    }
                    IconButton(onClick = { scope.launch { loadMedia() } }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !folderMode,
                    onClick = {
                        folderMode = false
                        selectedFolder = null
                    },
                    label = { Text("All ${category.label.lowercase()}") },
                )
                FilterChip(
                    selected = folderMode,
                    onClick = { folderMode = true },
                    label = { Text("Folders") },
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search ${category.label.lowercase()}…") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )

            if (isMusic && displayed.isNotEmpty() && (!folderMode || selectedFolder != null)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { onPlayAudio(displayed, 0) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Play all")
                    }
                    FilledTonalButton(
                        onClick = { onPlayAudio(displayed.shuffled(), 0) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Shuffle")
                    }
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                folderMode && selectedFolder == null -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        folders.forEach { (folder, list) ->
                            item(key = folder) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable { selectedFolder = folder },
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    shape = RoundedCornerShape(16.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Icon(Icons.Rounded.Folder, contentDescription = null, tint = if (isMusic) VioletAccent else TealAccent)
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(folder, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${list.size} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                displayed.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            if (isMusic) Icons.Rounded.Headphones else Icons.Rounded.Movie,
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("No ${category.label.lowercase()} found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                gridMode -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(156.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(displayed, key = { _, it -> "${it.volume}:${it.path}" }) { idx, item ->
                            MediaGridCard(
                                api = api,
                                imageLoader = imageLoader,
                                item = item,
                                isMusic = isMusic,
                                onClick = {
                                    if (isMusic) onPlayAudio(displayed, idx) else onPlayVideo(item)
                                },
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(displayed, key = { _, it -> "${it.volume}:${it.path}" }) { idx, item ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        if (isMusic) onPlayAudio(displayed, idx) else onPlayVideo(item)
                                    },
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    if (isMusic) {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(VioletAccent.copy(alpha = 0.16f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = VioletAccent)
                                        }
                                    } else {
                                        val vol = item.volume.takeIf { it != "internal" }
                                        Box(
                                            modifier = Modifier
                                                .size(width = 72.dp, height = 46.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(MaterialTheme.colorScheme.surfaceContainer),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            AsyncImage(
                                                model = ImageRequest.Builder(LocalContext.current)
                                                    .data(api.videoThumbnailUrl(item.path, vol))
                                                    .size(360)
                                                    .build(),
                                                imageLoader = imageLoader,
                                                contentDescription = item.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name.substringBeforeLast('.'),
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${item.parentPath.ifBlank { item.volume }} · ${formatBytes(item.size)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
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

@Composable
private fun MediaGridCard(
    api: PocketLinkApi,
    imageLoader: ImageLoader,
    item: IndexedFile,
    isMusic: Boolean,
    onClick: () -> Unit,
) {
    val vol = item.volume.takeIf { it != "internal" }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (isMusic) 1.3f else 16f / 9f)
                    .background(
                        if (isMusic) VioletAccent.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.surfaceContainer
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isMusic) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = VioletAccent, modifier = Modifier.size(36.dp))
                } else {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(api.videoThumbnailUrl(item.path, vol))
                            .size(480)
                            .build(),
                        imageLoader = imageLoader,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White)
                    }
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = item.name.substringBeforeLast('.'),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatBytes(item.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
