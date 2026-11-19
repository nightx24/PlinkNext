package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.FileEntry
import com.example.data.model.FileSortField
import com.example.data.model.IndexedFile
import com.example.data.model.MediaExtensions
import com.example.data.model.StorageVolume
import com.example.data.model.formatBytes
import com.example.data.network.PocketLinkApi
import com.example.media.TransferManager
import com.example.ui.components.FileIconBadge
import com.example.ui.components.PocketLinkTopBar
import kotlinx.coroutines.launch

@Composable
fun FileManagerScreen(
    api: PocketLinkApi,
    transferManager: TransferManager,
    initialVolume: String? = null,
    initialPath: String = "",
    onBack: (() -> Unit)? = null,
    onOpenAudio: (List<IndexedFile>, Int) -> Unit,
    onOpenVideo: (IndexedFile) -> Unit,
    onOpenPhoto: (List<IndexedFile>, Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var volumes by remember { mutableStateOf<List<StorageVolume>>(emptyList()) }
    var activeVolume by remember(initialVolume) { mutableStateOf(initialVolume?.takeIf { it != "internal" }) }
    var currentPath by remember(initialPath) { mutableStateOf(initialPath) }
    var items by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var sortField by remember { mutableStateOf(FileSortField.NAME) }
    var gridMode by remember { mutableStateOf(false) }

    var showCreateMenu by remember { mutableStateOf(false) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<FileEntry?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }

    suspend fun loadDirectory(vol: String? = activeVolume, path: String = currentPath) {
        loading = true
        errorMessage = null
        runCatching {
            if (vol == null) api.listDirectory(path) else api.listStorageDirectory(vol, path)
        }.onSuccess { listing ->
            currentPath = if (listing.path == ".") "" else listing.path
            items = listing.items
        }.onFailure { err ->
            errorMessage = err.message ?: "Could not load folder"
        }
        loading = false
    }

    LaunchedEffect(api.baseUrl, initialVolume, initialPath) {
        runCatching { api.listStorageVolumes() }.onSuccess { volumes = it }
        loadDirectory(activeVolume, currentPath)
    }

    val documentUploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                transferManager.enqueueUpload(api, uri, currentPath, activeVolume) {
                    scope.launch { loadDirectory() }
                }
            }
            scope.launch { snackbarHostState.showSnackbar("Uploading ${uris.size} file(s)…") }
        }
    }

    val photoUploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                transferManager.enqueueUpload(api, uri, currentPath, activeVolume) {
                    scope.launch { loadDirectory() }
                }
            }
            scope.launch { snackbarHostState.showSnackbar("Uploading ${uris.size} media item(s)…") }
        }
    }

    BackHandler(enabled = currentPath.isNotEmpty() || onBack != null) {
        if (currentPath.isNotEmpty()) {
            val parent = currentPath.substringBeforeLast('/', "")
            scope.launch { loadDirectory(activeVolume, parent) }
        } else {
            onBack?.invoke()
        }
    }

    val displayedItems = remember(items, searchQuery, sortField) {
        val filtered = if (searchQuery.isBlank()) items else items.filter {
            it.name.contains(searchQuery.trim(), ignoreCase = true)
        }
        filtered.sortedWith(
            compareBy<FileEntry> { !it.isDirectory }
                .thenComparator { a, b ->
                    when (sortField) {
                        FileSortField.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                        FileSortField.MODIFIED -> b.modified.compareTo(a.modified)
                        FileSortField.SIZE -> b.size.compareTo(a.size)
                    }
                }
        )
    }

    fun itemPath(name: String): String = if (currentPath.isEmpty()) name else "$currentPath/$name"

    fun openEntry(entry: FileEntry) {
        val fullPath = itemPath(entry.name)
        if (entry.isDirectory) {
            scope.launch { loadDirectory(activeVolume, fullPath) }
            return
        }
        val volId = activeVolume ?: "internal"
        when {
            MediaExtensions.isAudio(entry.name) -> {
                val tracks = displayedItems.filter { !it.isDirectory && MediaExtensions.isAudio(it.name) }
                    .map {
                        IndexedFile(
                            volume = volId,
                            path = itemPath(it.name),
                            name = it.name,
                            type = it.type,
                            size = it.size,
                            modified = it.modified,
                            mime = "",
                            category = "music",
                        )
                    }
                val idx = tracks.indexOfFirst { it.path == fullPath }.coerceAtLeast(0)
                onOpenAudio(tracks, idx)
            }
            MediaExtensions.isVideo(entry.name) -> {
                onOpenVideo(
                    IndexedFile(
                        volume = volId,
                        path = fullPath,
                        name = entry.name,
                        type = entry.type,
                        size = entry.size,
                        modified = entry.modified,
                        mime = "",
                        category = "video",
                    )
                )
            }
            MediaExtensions.isPhoto(entry.name) -> {
                val photos = displayedItems.filter { !it.isDirectory && MediaExtensions.isPhoto(it.name) }
                    .map {
                        IndexedFile(
                            volume = volId,
                            path = itemPath(it.name),
                            name = it.name,
                            type = it.type,
                            size = it.size,
                            modified = it.modified,
                            mime = "",
                            category = "photo",
                        )
                    }
                val idx = photos.indexOfFirst { it.path == fullPath }.coerceAtLeast(0)
                onOpenPhoto(photos, idx)
            }
            else -> {
                transferManager.enqueueDownload(api, fullPath, activeVolume, entry.name, entry.size) { local ->
                    val opened = transferManager.openDownloadedFile(context, local.absolutePath)
                    if (!opened) {
                        scope.launch { snackbarHostState.showSnackbar("Saved to ${local.name}") }
                    }
                }
                scope.launch { snackbarHostState.showSnackbar("Downloading ${entry.name}…") }
            }
        }
    }

    Scaffold(
        topBar = {
            PocketLinkTopBar(
                title = volumes.firstOrNull { it.id == activeVolume }?.name ?: "Internal storage",
                subtitle = if (currentPath.isEmpty()) "Root folder" else "/$currentPath",
                onBack = if (currentPath.isNotEmpty()) {
                    {
                        val parent = currentPath.substringBeforeLast('/', "")
                        scope.launch { loadDirectory(activeVolume, parent) }
                        Unit
                    }
                } else onBack,
                actions = {
                    IconButton(onClick = { gridMode = !gridMode }) {
                        Icon(if (gridMode) Icons.Rounded.ViewList else Icons.Rounded.GridView, contentDescription = "Toggle layout")
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "Sort files")
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            FileSortField.entries.forEach { field ->
                                DropdownMenuItem(
                                    text = { Text(field.label) },
                                    onClick = {
                                        sortField = field
                                        showSortMenu = false
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { scope.launch { loadDirectory() } }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        floatingActionButton = {
            Box(
                modifier = Modifier.padding(bottom = if (onBack == null) 96.dp else 8.dp),
            ) {
                FloatingActionButton(
                    onClick = { showCreateMenu = true },
                    modifier = Modifier.testTag("files_fab"),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add or upload")
                }
                DropdownMenu(expanded = showCreateMenu, onDismissRequest = { showCreateMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Upload files") },
                        leadingIcon = { Icon(Icons.Rounded.CloudUpload, contentDescription = null) },
                        onClick = {
                            showCreateMenu = false
                            documentUploadLauncher.launch(arrayOf("*/*"))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Upload photos & videos") },
                        leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null) },
                        onClick = {
                            showCreateMenu = false
                            photoUploadLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("New folder") },
                        leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null) },
                        onClick = {
                            showCreateMenu = false
                            showCreateFolderDialog = true
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (volumes.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = activeVolume == null,
                            onClick = {
                                activeVolume = null
                                scope.launch { loadDirectory(null, "") }
                            },
                            label = { Text("Internal") },
                        )
                    }
                    items(volumes.filter { it.available }, key = { it.id }) { vol ->
                        FilterChip(
                            selected = activeVolume == vol.id,
                            onClick = {
                                activeVolume = vol.id
                                scope.launch { loadDirectory(vol.id, "") }
                            },
                            leadingIcon = { Icon(Icons.Rounded.Usb, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text(vol.name) },
                        )
                    }
                }
            }

            if (currentPath.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = false,
                        onClick = { scope.launch { loadDirectory(activeVolume, "") } },
                        label = { Text("Root") },
                    )
                    val segments = currentPath.split('/')
                    segments.forEachIndexed { idx, seg ->
                        val target = segments.take(idx + 1).joinToString("/")
                        FilterChip(
                            selected = idx == segments.lastIndex,
                            onClick = { scope.launch { loadDirectory(activeVolume, target) } },
                            label = { Text(seg) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filter this folder…") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear filter")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )

            when {
                loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                errorMessage != null -> {
                    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                            Button(onClick = { scope.launch { loadDirectory() } }) { Text("Retry") }
                        }
                    }
                }
                displayedItems.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("This folder is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                gridMode -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(148.dp),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = if (onBack == null) 180.dp else 108.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(displayedItems, key = { it.name }) { entry ->
                            FileGridTile(
                                entry = entry,
                                onClick = { openEntry(entry) },
                                onDownload = {
                                    transferManager.enqueueDownload(api, itemPath(entry.name), activeVolume, entry.name, entry.size)
                                    scope.launch { snackbarHostState.showSnackbar("Downloading ${entry.name}…") }
                                },
                                onShare = {
                                    transferManager.enqueueDownload(api, itemPath(entry.name), activeVolume, entry.name, entry.size) { file ->
                                        transferManager.shareDownloadedFile(context, file.absolutePath)
                                    }
                                    scope.launch { snackbarHostState.showSnackbar("Preparing ${entry.name} to share…") }
                                },
                                onRename = { renameTarget = entry },
                                onDelete = { deleteTarget = entry },
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 6.dp,
                            bottom = if (onBack == null) 180.dp else 108.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(displayedItems, key = { it.name }) { entry ->
                            FileRowTile(
                                entry = entry,
                                onClick = { openEntry(entry) },
                                onDownload = {
                                    transferManager.enqueueDownload(api, itemPath(entry.name), activeVolume, entry.name, entry.size)
                                    scope.launch { snackbarHostState.showSnackbar("Downloading ${entry.name}…") }
                                },
                                onShare = {
                                    transferManager.enqueueDownload(api, itemPath(entry.name), activeVolume, entry.name, entry.size) { file ->
                                        transferManager.shareDownloadedFile(context, file.absolutePath)
                                    }
                                    scope.launch { snackbarHostState.showSnackbar("Preparing ${entry.name} to share…") }
                                },
                                onRename = { renameTarget = entry },
                                onDelete = { deleteTarget = entry },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("New folder") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = folderName.trim()
                        if (trimmed.isNotEmpty()) {
                            showCreateFolderDialog = false
                            scope.launch {
                                runCatching { api.createFolder(currentPath, trimmed, activeVolume) }
                                    .onSuccess { loadDirectory() }
                                    .onFailure { err -> snackbarHostState.showSnackbar(err.message ?: "Could not create folder") }
                            }
                        }
                    }
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) { Text("Cancel") }
            },
        )
    }

    renameTarget?.let { target ->
        var newName by remember(target.name) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newName.trim()
                        if (trimmed.isNotEmpty() && trimmed != target.name) {
                            renameTarget = null
                            scope.launch {
                                runCatching { api.renameItem(itemPath(target.name), trimmed, activeVolume) }
                                    .onSuccess { loadDirectory() }
                                    .onFailure { err -> snackbarHostState.showSnackbar(err.message ?: "Rename failed") }
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
            title = { Text("Delete item?") },
            text = { Text("Delete \"${target.name}\"? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            runCatching { api.deleteItem(itemPath(target.name), activeVolume) }
                                .onSuccess { loadDirectory() }
                                .onFailure { err -> snackbarHostState.showSnackbar(err.message ?: "Delete failed") }
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
private fun FileRowTile(
    entry: FileEntry,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FileIconBadge(entry)
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = if (entry.isDirectory) "Folder" else formatBytes(entry.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "File options")
                }
                FileActionsMenu(
                    expanded = menuOpen,
                    isDirectory = entry.isDirectory,
                    onDismiss = { menuOpen = false },
                    onDownload = onDownload,
                    onShare = onShare,
                    onRename = onRename,
                    onDelete = onDelete,
                )
            }
        }
    }
}

@Composable
private fun FileGridTile(
    entry: FileEntry,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                FileIconBadge(entry)
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Options", modifier = Modifier.size(18.dp))
                    }
                    FileActionsMenu(
                        expanded = menuOpen,
                        isDirectory = entry.isDirectory,
                        onDismiss = { menuOpen = false },
                        onDownload = onDownload,
                        onShare = onShare,
                        onRename = onRename,
                        onDelete = onDelete,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                if (entry.isDirectory) "Folder" else formatBytes(entry.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileActionsMenu(
    expanded: Boolean,
    isDirectory: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (!isDirectory) {
            DropdownMenuItem(
                text = { Text("Download") },
                leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                onClick = { onDismiss(); onDownload() },
            )
            DropdownMenuItem(
                text = { Text("Share") },
                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                onClick = { onDismiss(); onShare() },
            )
        }
        DropdownMenuItem(
            text = { Text("Rename") },
            leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null) },
            onClick = { onDismiss(); onRename() },
        )
        DropdownMenuItem(
            text = { Text("Delete") },
            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
            onClick = { onDismiss(); onDelete() },
        )
    }
}
