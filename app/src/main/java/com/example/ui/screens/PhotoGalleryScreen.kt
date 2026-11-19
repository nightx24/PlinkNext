package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.model.IndexedFile
import com.example.data.model.MediaCategory
import com.example.data.model.formatBytes
import com.example.data.network.PocketLinkApi
import com.example.media.TransferManager
import com.example.ui.components.PocketLinkTopBar
import com.example.ui.theme.AmberAccent
import kotlinx.coroutines.launch

@Composable
fun PhotoGalleryScreen(
    api: PocketLinkApi,
    imageLoader: ImageLoader,
    favorites: Set<String>,
    initialFavoritesOnly: Boolean = false,
    onToggleFavorite: (volume: String?, path: String) -> Unit,
    onOpenViewer: (photos: List<IndexedFile>, index: Int) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var photos by remember { mutableStateOf<List<IndexedFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var favoritesOnly by remember(initialFavoritesOnly) { mutableStateOf(initialFavoritesOnly) }
    var query by remember { mutableStateOf("") }

    suspend fun loadPhotos() {
        loading = true
        val indexed = runCatching { api.indexedMedia(MediaCategory.PHOTO, limit = 500) }.getOrNull()
        photos = if (indexed != null && indexed.items.isNotEmpty()) {
            indexed.items
        } else {
            api.walkMediaFallback(MediaCategory.PHOTO)
        }
        loading = false
    }

    LaunchedEffect(api.baseUrl) { loadPhotos() }

    val displayed = remember(photos, favoritesOnly, favorites, query) {
        photos.filter { item ->
            val key = "${item.volume}::${item.path}"
            val favOk = !favoritesOnly || key in favorites
            val queryOk = query.isBlank() || item.name.contains(query.trim(), ignoreCase = true)
            favOk && queryOk
        }
    }

    Scaffold(
        topBar = {
            PocketLinkTopBar(
                title = "Photos",
                subtitle = "${displayed.size} items",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { scope.launch { loadPhotos() } }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh photos")
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
                    selected = !favoritesOnly,
                    onClick = { favoritesOnly = false },
                    label = { Text("All photos") },
                )
                FilterChip(
                    selected = favoritesOnly,
                    onClick = { favoritesOnly = true },
                    leadingIcon = { Icon(Icons.Rounded.Favorite, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Favorites") },
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Filter photos…") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                displayed.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (favoritesOnly) "No favorite photos yet" else "No photos found on this server",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(displayed, key = { _, item -> "${item.volume}:${item.path}" }) { idx, photo ->
                        val vol = photo.volume.takeIf { it != "internal" }
                        val url = api.streamUrl(photo.path, vol)
                        val isFav = "${photo.volume}::${photo.path}" in favorites

                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .clickable { onOpenViewer(displayed, idx) },
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(url)
                                    .size(320)
                                    .build(),
                                imageLoader = imageLoader,
                                contentDescription = photo.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            IconButton(
                                onClick = { onToggleFavorite(vol, photo.path) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.45f)),
                            ) {
                                Icon(
                                    imageVector = if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = "Favorite",
                                    tint = if (isFav) AmberAccent else Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewerScreen(
    api: PocketLinkApi,
    imageLoader: ImageLoader,
    transferManager: TransferManager,
    photos: List<IndexedFile>,
    initialIndex: Int,
    favorites: Set<String>,
    onToggleFavorite: (volume: String?, path: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0))) { photos.size }
    val current = photos.getOrNull(pagerState.currentPage)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    if (current != null) {
                        Column {
                            Text(current.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${pagerState.currentPage + 1} of ${photos.size} · ${formatBytes(current.size)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    if (current != null) {
                        val vol = current.volume.takeIf { it != "internal" }
                        val isFav = "${current.volume}::${current.path}" in favorites
                        IconButton(onClick = { onToggleFavorite(vol, current.path) }) {
                            Icon(
                                if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                contentDescription = "Favorite",
                                tint = if (isFav) AmberAccent else Color.White,
                            )
                        }
                        IconButton(onClick = {
                            transferManager.enqueueDownload(api, current.path, vol, current.name, current.size)
                            scope.launch { snackbarHostState.showSnackbar("Downloading ${current.name}…") }
                        }) {
                            Icon(Icons.Rounded.Download, contentDescription = "Download", tint = Color.White)
                        }
                        IconButton(onClick = {
                            transferManager.enqueueDownload(api, current.path, vol, current.name, current.size) { file ->
                                transferManager.shareDownloadedFile(context, file.absolutePath)
                            }
                            scope.launch { snackbarHostState.showSnackbar("Preparing to share…") }
                        }) {
                            Icon(Icons.Rounded.Share, contentDescription = "Share", tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.65f)),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) { page ->
            val photo = photos[page]
            val vol = photo.volume.takeIf { it != "internal" }
            val url = api.streamUrl(photo.path, vol)
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(page) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 4f)
                            scale = nextScale
                            offset = if (nextScale > 1f) offset + pan else Offset.Zero
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = url,
                    imageLoader = imageLoader,
                    contentDescription = photo.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                )
            }
        }
    }
}
