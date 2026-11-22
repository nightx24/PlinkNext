package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.store.AppLogger
import com.example.data.store.DiagnosticLogGenerator
import com.example.ui.PocketLinkViewModel
import com.example.ui.components.PocketLinkTopBar
import com.example.ui.theme.CoralError
import com.example.ui.theme.LocalPocketLinkColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    viewModel: PocketLinkViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalPocketLinkColors.current
    val entries by AppLogger.entries.collectAsStateWithLifecycle()
    val audioState by viewModel.audioController.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var levelFilter by remember { mutableStateOf("ALL") }
    var generatedReport by remember { mutableStateOf<String?>(null) }
    var lastProbeOutput by remember { mutableStateOf<String?>(null) }
    var probingStreams by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    val saveLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val textToSave = generatedReport ?: viewModel.generateDiagnosticLogReport(lastProbeOutput)
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(textToSave.toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
            }
            snackbarHostState.showSnackbar(if (saved) "Saved diagnostic log file" else "Could not write log file")
        }
    }

    val filtered = remember(entries, levelFilter) {
        if (levelFilter == "ALL") entries else entries.filter { it.level == levelFilter }
    }

    Scaffold(
        topBar = {
            PocketLinkTopBar(
                title = "Diagnostics & Logs",
                subtitle = "${filtered.size} events · ${settings.ytAudioQuality.shortLabel}",
                onBack = onBack,
                actions = {
                    IconButton(onClick = {
                        val report = generatedReport ?: viewModel.generateDiagnosticLogReport(lastProbeOutput)
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("PocketLink Diagnostic Log", report))
                        scope.launch { snackbarHostState.showSnackbar("Copied full diagnostic report") }
                    }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy logs")
                    }
                    IconButton(onClick = {
                        AppLogger.clear()
                        generatedReport = null
                        lastProbeOutput = null
                    }) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear logs")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
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
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Log Generator & Live Stream Quality Probe Card
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, colors.cardBorder, RoundedCornerShape(20.dp))
                            .testTag("log_generator_card"),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, tint = colors.cyan)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Diagnostic Log Generator",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                    )
                                    Text(
                                        text = "Captures Discover Opus itags, HTTP probes, ExoPlayer decoder format, Cloudflare Tunnel & app events.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            // Active stream quick readout
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    val effective = viewModel.ytMusicClient.effectiveAudioQuality()
                                    Text(
                                        text = "Quality: ${settings.ytAudioQuality.shortLabel} (effective ${effective.shortLabel}) · Profile: ${settings.ytResolverProfile}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = "Active stream: ${audioState.streamBadge.ifBlank { "Idle" }} · Decoder: ${audioState.actualAudioFormat.ifBlank { "—" }}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = {
                                        generatedReport = viewModel.generateDiagnosticLogReport(lastProbeOutput)
                                        scope.launch { snackbarHostState.showSnackbar("Generated diagnostic log report") }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("generate_log_report_btn"),
                                ) {
                                    Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Generate Log", fontWeight = FontWeight.Bold)
                                }
                                FilledTonalButton(
                                    onClick = {
                                        if (!probingStreams) {
                                            probingStreams = true
                                            viewModel.runDiscoverStreamProbe { output ->
                                                probingStreams = false
                                                lastProbeOutput = output
                                                generatedReport = viewModel.generateDiagnosticLogReport(output)
                                                scope.launch { snackbarHostState.showSnackbar("Completed High / Med / Low Opus probe") }
                                            }
                                        }
                                    },
                                    enabled = !probingStreams,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("run_stream_probe_btn"),
                                ) {
                                    if (probingStreams) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(17.dp))
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (probingStreams) "Probing…" else "Probe Tiers", fontWeight = FontWeight.Bold)
                                }
                            }

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val report = generatedReport ?: viewModel.generateDiagnosticLogReport(lastProbeOutput).also {
                                            generatedReport = it
                                        }
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        cm?.setPrimaryClip(ClipData.newPlainText("PocketLink Diagnostic Log", report))
                                        scope.launch { snackbarHostState.showSnackbar("Copied diagnostic report (${report.lines().size} lines)") }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("copy_log_report_btn"),
                                ) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Copy")
                                }
                                OutlinedButton(
                                    onClick = {
                                        if (generatedReport == null) {
                                            generatedReport = viewModel.generateDiagnosticLogReport(lastProbeOutput)
                                        }
                                        saveLogLauncher.launch(DiagnosticLogGenerator.buildDefaultFilename())
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("export_log_file_btn"),
                                ) {
                                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Save .log")
                                }
                                OutlinedButton(
                                    onClick = {
                                        val report = generatedReport ?: viewModel.generateDiagnosticLogReport(lastProbeOutput).also {
                                            generatedReport = it
                                        }
                                        scope.launch {
                                            val shareUri = withContext(Dispatchers.IO) {
                                                runCatching { DiagnosticLogGenerator.writeShareableLogFile(context, report) }.getOrNull()
                                            }
                                            if (shareUri != null) {
                                                val send = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_SUBJECT, "PocketLink Diagnostic Log")
                                                    putExtra(Intent.EXTRA_STREAM, shareUri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                runCatching { context.startActivity(Intent.createChooser(send, "Share PocketLink Log")) }
                                            } else {
                                                snackbarHostState.showSnackbar("Could not prepare log file to share")
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("share_log_report_btn"),
                                ) {
                                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Share")
                                }
                            }

                            AnimatedVisibility(visible = !generatedReport.isNullOrBlank()) {
                                val reportText = generatedReport.orEmpty()
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "Generated Preview (${reportText.lines().size} lines · ${reportText.length} chars)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.emerald,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 120.dp, max = 260.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                            .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                                            .verticalScroll(rememberScrollState())
                                            .padding(10.dp)
                                            .testTag("generated_log_preview"),
                                    ) {
                                        Text(
                                            text = reportText,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Live event level filters
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                }

                if (filtered.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Rounded.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("No diagnostic logs recorded yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else {
                    items(filtered) { entry ->
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
                                    Text(entry.level, style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = FontWeight.Bold)
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
                                        fontFamily = FontFamily.Monospace,
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
