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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.IndexedFile
import com.example.data.model.MediaCategory
import com.example.data.model.formatBytes
import com.example.data.network.PocketLinkApi
import com.example.ui.components.FileIconBadge
import com.example.ui.components.PocketLinkTopBar
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(
    api: PocketLinkApi,
    onOpenResult: (IndexedFile) -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var scopeFilter by remember { mutableStateOf("All") }
    var results by remember { mutableStateOf<List<IndexedFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(query, scopeFilter, api.baseUrl) {
        delay(220L)
        loading = true
        val page = when (scopeFilter) {
            "Music" -> runCatching { api.indexedMedia(MediaCategory.MUSIC, query = query, limit = 120) }.getOrNull()
            "Videos" -> runCatching { api.indexedMedia(MediaCategory.VIDEO, query = query, limit = 120) }.getOrNull()
            "Photos" -> runCatching { api.indexedMedia(MediaCategory.PHOTO, query = query, limit = 120) }.getOrNull()
            else -> runCatching { api.searchIndexedFiles(query = query, limit = 120) }.getOrNull()
        }
        results = page?.items.orEmpty()
        loading = false
    }

    Scaffold(
        topBar = {
            PocketLinkTopBar(
                title = "Search",
                subtitle = "${results.size} results",
                onBack = onBack,
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search files, folders, and media…") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("global_search_input"),
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(listOf("All", "Music", "Videos", "Photos")) { scope ->
                    FilterChip(
                        selected = scopeFilter == scope,
                        onClick = { scopeFilter = scope },
                        label = { Text(scope) },
                    )
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (query.isBlank()) "Start typing or scan the host index" else "No matches for \"$query\"",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(results, key = { "${it.volume}:${it.path}:${it.type}" }) { item ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onOpenResult(item) },
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                FileIconBadge(item.toFileEntry())
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${item.volume} · /${item.parentPath}${if (item.isDirectory) "" else " · ${formatBytes(item.size)}"}",
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
