package com.example.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.TransferDirection
import com.example.data.model.TransferItem
import com.example.data.model.TransferStatus
import com.example.data.model.formatBytes
import com.example.media.TransferManager
import com.example.ui.components.CapacityProgressBar
import com.example.ui.components.PocketLinkTopBar
import com.example.ui.theme.CoralError
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.LocalPocketLinkColors

@Composable
fun TransferManagerScreen(
    transfers: List<TransferItem>,
    transferManager: TransferManager,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            PocketLinkTopBar(
                title = "Transfers",
                subtitle = "${transfers.count { it.status == TransferStatus.RUNNING }} active",
                onBack = onBack,
                actions = {
                    if (transfers.any { it.status != TransferStatus.RUNNING && it.status != TransferStatus.QUEUED }) {
                        IconButton(onClick = { transferManager.clearFinished() }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear finished")
                        }
                    }
                },
            )
        }
    ) { innerPadding ->
        if (transfers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No active or recent transfers", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(transfers, key = { it.id }) { item ->
                    val isUpload = item.direction == TransferDirection.UPLOAD
                    val colors = LocalPocketLinkColors.current
                    val tint = when (item.status) {
                        TransferStatus.COMPLETED -> colors.emerald
                        TransferStatus.FAILED -> CoralError
                        TransferStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> if (isUpload) MaterialTheme.colorScheme.primary else colors.cyan
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(
                                    imageVector = when (item.status) {
                                        TransferStatus.COMPLETED -> if (isUpload) Icons.Rounded.CloudDone else Icons.Rounded.CheckCircle
                                        TransferStatus.FAILED -> Icons.Rounded.ErrorOutline
                                        else -> if (isUpload) Icons.Rounded.CloudUpload else Icons.Rounded.CloudDownload
                                    },
                                    contentDescription = null,
                                    tint = tint,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        text = when (item.status) {
                                            TransferStatus.RUNNING -> "${formatBytes(item.bytesTransferred)} / ${if (item.totalBytes > 0) formatBytes(item.totalBytes) else "…"}"
                                            TransferStatus.COMPLETED -> "${if (isUpload) "Uploaded" else "Downloaded"} · ${formatBytes(item.totalBytes)}"
                                            TransferStatus.FAILED -> item.errorMessage ?: "Failed"
                                            TransferStatus.CANCELLED -> "Cancelled"
                                            TransferStatus.QUEUED -> "Queued"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                if (item.status == TransferStatus.RUNNING) {
                                    IconButton(onClick = { transferManager.cancel(item.id) }) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Cancel")
                                    }
                                } else if (item.status == TransferStatus.COMPLETED && !isUpload && item.localFilePath != null) {
                                    IconButton(onClick = { transferManager.openDownloadedFile(context, item.localFilePath) }) {
                                        Icon(Icons.Rounded.OpenInNew, contentDescription = "Open")
                                    }
                                    IconButton(onClick = { transferManager.shareDownloadedFile(context, item.localFilePath) }) {
                                        Icon(Icons.Rounded.Share, contentDescription = "Share")
                                    }
                                }
                            }
                            if (item.status == TransferStatus.RUNNING) {
                                CapacityProgressBar(fraction = item.progress, color = tint)
                            }
                        }
                    }
                }
            }
        }
    }
}
