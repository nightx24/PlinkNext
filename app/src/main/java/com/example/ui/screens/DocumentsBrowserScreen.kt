package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.IndexedFile
import com.example.data.model.MediaExtensions
import com.example.data.model.formatBytes
import com.example.data.network.PocketLinkApi
import com.example.media.TransferManager
import com.example.ui.components.FileIconBadge
import com.example.ui.components.PocketLinkTopBar
import kotlinx.coroutines.launch

@Composable
fun DocumentsBrowserScreen(
    api: PocketLinkApi,
    transferManager: TransferManager,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var docs by remember { mutableStateOf<List<IndexedFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var filterCategory by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }

    suspend fun loadDocs() {
        loading = true
        val page = runCatching { api.searchIndexedFiles(query = "", limit = 500) }.getOrNull()
        val found = page?.items?.filter { !it.isDirectory && MediaExtensions.isDocument(it.name) }
        if (!found.isNullOrEmpty()) {
            docs = found
        } else {
            // Walk common folders as fallback
            val fallback = mutableListOf<IndexedFile>()
            for (folder in listOf("", "Documents", "Download")) {
                val listing = runCatching { api.listDirectory(folder) }.getOrNull() ?: continue
                listing.items.filter { !it.isDirectory && MediaExtensions.isDocument(it.name) }.forEach { entry ->
                    val path = if (folder.isEmpty()) entry.name else "$folder/${entry.name}"
                    fallback.add(IndexedFile("internal", path, entry.name, entry.type, entry.size, entry.modified, ""))
                }
            }
            docs = fallback.distinctBy { it.path }
        }
        loading = false
    }

    LaunchedEffect(api.baseUrl) { loadDocs() }

    val filtered = remember(docs, filterCategory, query) {
        docs.filter { item ->
            val ext = item.name.substringAfterLast('.', "").lowercase()
            val catOk = when (filterCategory) {
                "PDF" -> ext in MediaExtensions.pdf
                "Office" -> ext in MediaExtensions.office
                "Text" -> ext in MediaExtensions.text
                "Archives" -> ext in MediaExtensions.archive
                else -> true
            }
            val queryOk = query.isBlank() || item.name.contains(query.trim(), ignoreCase = true)
            catOk && queryOk
        }
    }

    Scaffold(
        topBar = {
            PocketLinkTopBar(
                title = "Documents",
                subtitle = "${filtered.size} files",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { scope.launch { loadDocs() } }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh documents")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(listOf("All", "PDF", "Office", "Text", "Archives")) { cat ->
                    FilterChip(
                        selected = filterCategory == cat,
                        onClick = { filterCategory = cat },
                        label = { Text(cat) },
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search documents…") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("No documents found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { "${it.volume}:${it.path}" }) { doc ->
                        val vol = doc.volume.takeIf { it != "internal" }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    transferManager.enqueueDownload(api, doc.path, vol, doc.name, doc.size) { local ->
                                        val opened = transferManager.openDownloadedFile(context, local.absolutePath)
                                        if (!opened) scope.launch { snackbarHostState.showSnackbar("Saved ${local.name}") }
                                    }
                                    scope.launch { snackbarHostState.showSnackbar("Opening ${doc.name}…") }
                                },
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                FileIconBadge(doc.toFileEntry())
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(doc.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${doc.parentPath.ifBlank { doc.volume }} · ${formatBytes(doc.size)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = {
                                    transferManager.enqueueDownload(api, doc.path, vol, doc.name, doc.size)
                                    scope.launch { snackbarHostState.showSnackbar("Downloading ${doc.name}…") }
                                }) {
                                    Icon(Icons.Rounded.Download, contentDescription = "Download ${doc.name}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
