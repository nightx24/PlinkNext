package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.store.AppLogger
import com.example.ui.components.PocketLinkTopBar
import com.example.ui.theme.CoralError
import com.example.ui.theme.LocalPocketLinkColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by AppLogger.entries.collectAsStateWithLifecycle()
    var levelFilter by remember { mutableStateOf("ALL") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    val filtered = remember(entries, levelFilter) {
        if (levelFilter == "ALL") entries else entries.filter { it.level == levelFilter }
    }

    Scaffold(
        topBar = {
            PocketLinkTopBar(
                title = "Diagnostics",
                subtitle = "${filtered.size} log events",
                onBack = onBack,
                actions = {
                    IconButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("PocketLink Logs", AppLogger.exportText()))
                        scope.launch { snackbarHostState.showSnackbar("Copied diagnostic logs") }
                    }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy logs")
                    }
                    IconButton(onClick = { AppLogger.clear() }) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear logs")
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("ALL", "INFO", "WARN", "ERROR").forEach { lvl ->
                    FilterChip(
                        selected = levelFilter == lvl,
                        onClick = { levelFilter = lvl },
                        label = { Text(lvl) },
                    )
                }
            }

            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("No diagnostic logs recorded yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered) { entry ->
                        val colors = LocalPocketLinkColors.current
                        val tint = when (entry.level) {
                            "ERROR" -> CoralError
                            "WARN" -> colors.amber
                            else -> colors.cyan
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(entry.level, style = MaterialTheme.typography.labelSmall, color = tint)
                                    Text(
                                        formatter.format(Date(entry.timestampMs)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(entry.message, style = MaterialTheme.typography.bodyMedium)
                                if (!entry.details.isNullOrBlank()) {
                                    Text(
                                        entry.details,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
