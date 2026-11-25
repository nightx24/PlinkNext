package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.formatBytes
import com.example.ui.PocketLinkViewModel
import com.example.ui.theme.LocalPocketLinkColors
import io.github.augztn.pocketlink.ssdbridge.tunnel.CloudflareTunnelAdapter
import io.github.augztn.pocketlink.ssdbridge.tunnel.CloudflareTunnelConfig
import io.github.augztn.pocketlink.ssdbridge.tunnel.CloudflareTunnelMode
import io.github.augztn.pocketlink.ssdbridge.tunnel.CloudflareWorkerTemplate
import io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelState
import io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelStatusSnapshot
import io.github.augztn.pocketlink.ssdbridge.tunnel.buildPocketLinkConnectUri
import io.github.augztn.pocketlink.ssdbridge.tunnel.parseCloudflareTunnelToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CloudflareTunnelCard(
    viewModel: PocketLinkViewModel,
    tunnelStatus: TunnelStatusSnapshot,
    tunnelConfig: CloudflareTunnelConfig,
    hostRunning: Boolean,
    onConnectCloudflare: (() -> Unit)? = null,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalPocketLinkColors.current
    val cfColor = colors.cloudflare

    var selectedMode by remember(tunnelConfig.mode) { mutableStateOf(tunnelConfig.mode) }
    var workerUrlInput by remember(tunnelConfig.workerPublicUrl) { mutableStateOf(tunnelConfig.workerPublicUrl) }
    var workerSecretInput by remember { mutableStateOf("") }
    var revealWorkerSecret by remember { mutableStateOf(false) }

    var zeroTrustUrlInput by remember(tunnelConfig.zeroTrustPublicUrl) { mutableStateOf(tunnelConfig.zeroTrustPublicUrl) }
    var zeroTrustTokenInput by remember { mutableStateOf("") }
    var revealZeroTrustToken by remember { mutableStateOf(false) }

    var verifying by remember { mutableStateOf(false) }
    var showConfigSection by rememberSaveable { mutableStateOf(false) }
    var showWorkerGuide by rememberSaveable { mutableStateOf(false) }
    var showLogs by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(tunnelConfig.hasWorkerSecret) {
        if (tunnelConfig.hasWorkerSecret && workerSecretInput.isEmpty()) {
            val saved = viewModel.readCloudflareWorkerSecret()
            if (saved.isNotEmpty()) workerSecretInput = saved
        }
    }

    val parsedTokenPreview = remember(zeroTrustTokenInput, tunnelConfig.tokenInfo) {
        if (zeroTrustTokenInput.isNotBlank()) parseCloudflareTunnelToken(zeroTrustTokenInput)
        else tunnelConfig.tokenInfo
    }

    val statusTint = when (tunnelStatus.state) {
        TunnelState.CONNECTED -> colors.emerald
        TunnelState.CONNECTING -> colors.amber
        TunnelState.ERROR -> MaterialTheme.colorScheme.error
        TunnelState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    fun persistCurrentInputs(autoStartOverride: Boolean? = null): Result<CloudflareTunnelConfig> {
        return viewModel.saveCloudflareTunnelConfig(
            mode = selectedMode,
            workerPublicUrl = if (selectedMode == CloudflareTunnelMode.EDGE_BRIDGE) workerUrlInput else null,
            workerSecret = if (selectedMode == CloudflareTunnelMode.EDGE_BRIDGE && workerSecretInput.isNotBlank()) workerSecretInput else null,
            zeroTrustPublicUrl = if (selectedMode == CloudflareTunnelMode.ZERO_TRUST) zeroTrustUrlInput else null,
            zeroTrustToken = if (selectedMode == CloudflareTunnelMode.ZERO_TRUST && zeroTrustTokenInput.isNotBlank()) zeroTrustTokenInput else null,
            autoStartWithServer = autoStartOverride,
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (tunnelStatus.state == TunnelState.CONNECTED) cfColor.copy(alpha = 0.55f) else colors.cardBorder,
                shape = RoundedCornerShape(22.dp),
            )
            .testTag("cloudflare_tunnel_card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header row: Cloudflare badge + status pill + 1-click toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(cfColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (tunnelStatus.state == TunnelState.CONNECTED) Icons.Rounded.CloudDone else Icons.Rounded.Cloud,
                        contentDescription = null,
                        tint = cfColor,
                        modifier = Modifier.size(23.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Cloudflare Tunnel",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Surface(
                            color = cfColor.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(99.dp),
                        ) {
                            Text(
                                text = "1-CLICK AUTO",
                                style = MaterialTheme.typography.labelSmall,
                                color = cfColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = "Zero setup · Auto-handles credentials, password & HTTPS URL",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    color = statusTint.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(99.dp),
                    modifier = Modifier.testTag("cloudflare_tunnel_status_badge"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(statusTint),
                        )
                        Text(
                            text = tunnelStatus.state.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusTint,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Switch(
                    checked = tunnelStatus.isActive,
                    onCheckedChange = { turnOn ->
                        if (!turnOn) {
                            viewModel.stopCloudflareTunnel()
                            onMessage("Cloudflare Tunnel turned off")
                        } else {
                            runCatching { persistCurrentInputs() }
                            viewModel.startCloudflareTunnel()
                                .onSuccess {
                                    showConfigSection = false
                                    onMessage("Cloudflare Tunnel is live — no manual setup needed")
                                }
                                .onFailure { err ->
                                    onMessage(err.message ?: "Could not start Cloudflare Tunnel")
                                }
                        }
                    },
                    modifier = Modifier.testTag("cloudflare_quick_switch"),
                )
            }

            // Live Public URL pill + telemetry + 1-Tap Connect & 1-Tap Invite Link
            val activeUrl = tunnelStatus.publicUrl?.takeIf { it.isNotBlank() }
                ?: tunnelConfig.activePublicUrl.takeIf { it.isNotBlank() }
            if (!activeUrl.isNullOrBlank()) {
                Surface(
                    color = if (tunnelStatus.state == TunnelState.CONNECTED) cfColor.copy(alpha = 0.10f)
                    else MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (tunnelStatus.state == TunnelState.CONNECTED) cfColor.copy(alpha = 0.35f) else colors.cardBorder,
                            RoundedCornerShape(14.dp),
                        ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Language,
                                contentDescription = null,
                                tint = cfColor,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = activeUrl,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    copyTunnelClipboard(context, "Cloudflare Tunnel URL", activeUrl)
                                    onMessage("Copied $activeUrl")
                                },
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("cloudflare_copy_url_btn"),
                            ) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy Cloudflare URL", modifier = Modifier.size(18.dp))
                            }
                        }

                        // Telemetry badges (CF-Ray, Latency, Proxied Requests & Bytes)
                        val telemetry = buildList {
                            tunnelStatus.cloudflareRay?.takeIf { it.isNotBlank() }?.let { add("CF-Ray $it") }
                            tunnelStatus.latencyMs?.let { add("${it} ms") }
                            if (tunnelStatus.requestsProxied > 0) {
                                add("${tunnelStatus.requestsProxied} req · ${formatBytes(tunnelStatus.bytesProxied)}")
                            }
                            if (tunnelStatus.state == TunnelState.CONNECTED) {
                                add("-> 127.0.0.1:8000")
                            }
                        }
                        if (telemetry.isNotEmpty()) {
                            Text(
                                text = telemetry.joinToString("  ·  "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // Primary 1-Tap Connect on This Device button
                        if (onConnectCloudflare != null) {
                            Button(
                                onClick = { onConnectCloudflare() },
                                colors = ButtonDefaults.buttonColors(containerColor = cfColor, contentColor = Color.White),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .testTag("cloudflare_one_tap_connect_btn"),
                            ) {
                                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("1-Tap Connect via Cloudflare Now", fontWeight = FontWeight.Bold)
                            }
                        }

                        // One-tap Client Invite Link (includes URL + auto-configured password!)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    val hostPwd = viewModel.resolveLocalHostPassword()
                                    val connectLink = buildPocketLinkConnectUri(
                                        publicUrl = activeUrl,
                                        username = "admin",
                                        serverName = "PocketLink Cloud",
                                        password = hostPwd,
                                    )
                                    copyTunnelClipboard(context, "PocketLink 1-Tap Invite Link", connectLink)
                                    onMessage("Copied 1-Tap Invite Link (includes password) — tap Paste Invite Link on your other phone")
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("cloudflare_share_connect_btn"),
                            ) {
                                Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Copy 1-Tap Invite Link", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = {
                                    val hostPwd = viewModel.resolveLocalHostPassword()
                                    val connectLink = buildPocketLinkConnectUri(
                                        publicUrl = activeUrl,
                                        username = "admin",
                                        serverName = "PocketLink Cloud",
                                        password = hostPwd,
                                    )
                                    val share = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "Connect to PocketLink")
                                        putExtra(Intent.EXTRA_TEXT, connectLink)
                                    }
                                    runCatching { context.startActivity(Intent.createChooser(share, "Share 1-Tap Invite Link")) }
                                },
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Icon(Icons.Rounded.Share, contentDescription = "Share Client Connect Link", modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Share", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            // Collapsible advanced configuration section (optional custom domain / Worker / Zero Trust)
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { showConfigSection = !showConfigSection }
                    .testTag("cloudflare_config_toggle"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Tune, contentDescription = null, tint = cfColor, modifier = Modifier.size(18.dp))
                    Text(
                        text = "Advanced / Custom Domain (Optional — ${selectedMode.shortLabel})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (showConfigSection) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = "Toggle Cloudflare Tunnel settings",
                    )
                }
            }

            AnimatedVisibility(visible = showConfigSection) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Mode switcher: Built-in Edge Tunnel vs Cloudflare Zero Trust
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CloudflareTunnelMode.entries.forEach { mode ->
                    val selected = selectedMode == mode
                    val tag = when (mode) {
                        CloudflareTunnelMode.AUTO_EDGE -> "cf_mode_auto_edge"
                        CloudflareTunnelMode.EDGE_BRIDGE -> "cf_mode_edge_bridge"
                        CloudflareTunnelMode.ZERO_TRUST -> "cf_mode_zero_trust"
                    }
                    FilterChip(
                        selected = selected,
                        onClick = {
                            selectedMode = mode
                            viewModel.saveCloudflareTunnelConfig(mode = mode)
                        },
                        label = { Text(mode.shortLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) },
                        leadingIcon = {
                            Icon(
                                imageVector = when (mode) {
                                    CloudflareTunnelMode.AUTO_EDGE -> Icons.Rounded.AutoAwesome
                                    CloudflareTunnelMode.EDGE_BRIDGE -> Icons.Rounded.Cloud
                                    CloudflareTunnelMode.ZERO_TRUST -> Icons.Rounded.Shield
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = cfColor.copy(alpha = 0.18f),
                            selectedLabelColor = cfColor,
                            selectedLeadingIconColor = cfColor,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag(tag),
                    )
                }
            }

            Text(
                text = selectedMode.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (selectedMode == CloudflareTunnelMode.AUTO_EDGE) {
                Surface(
                    color = colors.emerald.copy(alpha = 0.11f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VerifiedUser,
                            contentDescription = null,
                            tint = colors.emerald,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Automatic 1-Click Provisioning Enabled",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.emerald,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Device credentials, tunnel token, and Cloudflare HTTPS session (${tunnelConfig.activePublicUrl.ifBlank { "auto" }}) are managed automatically — no manual typing required.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else if (selectedMode == CloudflareTunnelMode.EDGE_BRIDGE) {
                OutlinedTextField(
                    value = workerUrlInput,
                    onValueChange = { workerUrlInput = it },
                    label = { Text("Cloudflare Worker HTTPS URL") },
                    placeholder = { Text("https://pocketlink-relay.yourname.workers.dev") },
                    leadingIcon = { Icon(Icons.Rounded.Language, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cf_worker_url_input"),
                )

                OutlinedTextField(
                    value = workerSecretInput,
                    onValueChange = { workerSecretInput = it },
                    label = { Text("Tunnel Secret (TUNNEL_SECRET)") },
                    placeholder = { Text("Tap Generate or paste your Worker secret") },
                    leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { revealWorkerSecret = !revealWorkerSecret }) {
                                Icon(
                                    imageVector = if (revealWorkerSecret) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (revealWorkerSecret) "Hide tunnel secret" else "Show tunnel secret",
                                )
                            }
                            if (workerSecretInput.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        copyTunnelClipboard(context, "TUNNEL_SECRET", workerSecretInput)
                                        onMessage("Copied TUNNEL_SECRET")
                                    }
                                ) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy tunnel secret")
                                }
                            }
                        }
                    },
                    visualTransformation = if (revealWorkerSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cf_worker_secret_input"),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            val fresh = viewModel.generateCloudflareWorkerSecret()
                            workerSecretInput = fresh
                            revealWorkerSecret = true
                            copyTunnelClipboard(context, "TUNNEL_SECRET", fresh)
                            onMessage("Generated & copied TUNNEL_SECRET")
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cf_generate_secret_btn"),
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Generate Secret")
                    }
                    OutlinedButton(
                        onClick = {
                            copyTunnelClipboard(context, "PocketLink Cloudflare Worker", CloudflareWorkerTemplate.WORKER_SCRIPT)
                            onMessage("Copied Cloudflare Worker script")
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("cf_copy_worker_script_btn"),
                    ) {
                        Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy Worker")
                    }
                }

                // Expandable 3-step Worker deploy instructions
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { showWorkerGuide = !showWorkerGuide },
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Code, contentDescription = null, tint = cfColor, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "3-step free Cloudflare Worker setup",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                if (showWorkerGuide) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = "Toggle Worker setup guide",
                            )
                        }
                        AnimatedVisibility(visible = showWorkerGuide) {
                            Column(
                                modifier = Modifier.padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    "1. Tap \"Copy Worker\" and deploy it on Cloudflare Workers (free plan) with a Durable Object binding named POCKETLINK_RELAY -> class PocketLinkRelay.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "2. Tap \"Generate Secret\" and save it in Cloudflare Worker Variables & Secrets as TUNNEL_SECRET.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "3. Paste your https://<worker>.workers.dev URL above and tap Start Tunnel — PocketLink holds an encrypted WSS relay straight to port 8000.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = {
                                        copyTunnelClipboard(context, "wrangler.toml", CloudflareWorkerTemplate.WRANGLER_TOML)
                                        onMessage("Copied wrangler.toml")
                                    }
                                ) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Copy wrangler.toml")
                                }
                            }
                        }
                    }
                }
            } else {
                // Cloudflare Zero Trust / Quick Tunnel mode
                OutlinedTextField(
                    value = zeroTrustUrlInput,
                    onValueChange = { zeroTrustUrlInput = it },
                    label = { Text("Public Cloudflare HTTPS URL") },
                    placeholder = { Text("https://files.yourdomain.com or https://….trycloudflare.com") },
                    leadingIcon = { Icon(Icons.Rounded.Language, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cf_zerotrust_url_input"),
                )

                OutlinedTextField(
                    value = zeroTrustTokenInput,
                    onValueChange = { zeroTrustTokenInput = it },
                    label = {
                        Text(
                            if (tunnelConfig.hasZeroTrustToken && zeroTrustTokenInput.isBlank())
                                "Zero Trust Token (saved in Keystore)"
                            else "Zero Trust Tunnel Token (optional eyJ…)"
                        )
                    },
                    placeholder = { Text("Paste eyJ… token from Cloudflare Zero Trust") },
                    leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { revealZeroTrustToken = !revealZeroTrustToken }) {
                                Icon(
                                    imageVector = if (revealZeroTrustToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = "Toggle token visibility",
                                )
                            }
                            if (tunnelConfig.hasZeroTrustToken) {
                                IconButton(
                                    onClick = {
                                        zeroTrustTokenInput = ""
                                        viewModel.clearCloudflareSecret(CloudflareTunnelMode.ZERO_TRUST)
                                        onMessage("Cleared saved Zero Trust token")
                                    }
                                ) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Clear saved token")
                                }
                            }
                        }
                    },
                    visualTransformation = if (revealZeroTrustToken) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cf_zerotrust_token_input"),
                )

                parsedTokenPreview?.let { info ->
                    Surface(
                        color = colors.emerald.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = colors.emerald, modifier = Modifier.size(18.dp))
                            Text(
                                text = "Token verified · Account ${info.shortAccountId} · Tunnel ${info.shortTunnelId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.emerald,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                // Ready-to-copy cloudflared commands (Quick & Named)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val cmd = CloudflareTunnelAdapter.buildQuickTunnelCommand(8000)
                            copyTunnelClipboard(context, "Cloudflare Quick Tunnel command", cmd)
                            onMessage("Copied Quick Tunnel command")
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Quick Cmd", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            val hasToken = zeroTrustTokenInput.isNotBlank() || tunnelConfig.hasZeroTrustToken
                            val cmd = CloudflareTunnelAdapter.buildNamedTunnelCommand(hasToken)
                            copyTunnelClipboard(context, "Cloudflare Named Tunnel command", cmd)
                            onMessage("Copied Named Tunnel command")
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Named Cmd", maxLines = 1)
                    }
                }
            }

            // Auto-start with Host Server switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-start with Host Server",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Bring Cloudflare Tunnel online whenever port 8000 starts",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = tunnelConfig.autoStartWithServer,
                    onCheckedChange = { checked ->
                        persistCurrentInputs(autoStartOverride = checked)
                            .onFailure { err -> onMessage(err.message ?: "Save tunnel settings first") }
                    },
                    modifier = Modifier.testTag("cf_auto_start_switch"),
                )
            }
                }
            }

            // Error callout if present
            tunnelStatus.lastError?.takeIf { it.isNotBlank() }?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            // Action buttons: Start/Stop Tunnel + Verify HTTPS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        if (tunnelStatus.isActive) {
                            viewModel.stopCloudflareTunnel()
                            onMessage("Cloudflare Tunnel stopped")
                        } else {
                            runCatching { persistCurrentInputs() }
                            viewModel.startCloudflareTunnel()
                                .onSuccess {
                                    onMessage(
                                        if (!hostRunning) "Auto-configured & starting Host Server + Cloudflare Tunnel…"
                                        else "Auto-configured & starting Cloudflare Tunnel…"
                                    )
                                }
                                .onFailure { err -> onMessage(err.message ?: "Could not start Cloudflare Tunnel") }
                        }
                    },
                    colors = if (tunnelStatus.isActive) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors(containerColor = cfColor, contentColor = Color.White)
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1.15f)
                        .height(48.dp)
                        .testTag("cloudflare_tunnel_toggle_btn"),
                ) {
                    Icon(
                        imageVector = if (tunnelStatus.isActive) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (tunnelStatus.isActive) "Stop Tunnel" else "Start Tunnel",
                        fontWeight = FontWeight.Bold,
                    )
                }

                OutlinedButton(
                    onClick = {
                        persistCurrentInputs()
                            .onSuccess {
                                verifying = true
                                viewModel.verifyCloudflareTunnel { res ->
                                    verifying = false
                                    res.onSuccess { probe ->
                                        val ray = probe.cloudflareRay?.let { " · CF-Ray $it" }.orEmpty()
                                        onMessage("Verified HTTPS (${probe.latencyMs} ms$ray)")
                                    }.onFailure { err ->
                                        onMessage(err.message ?: "Verification failed")
                                    }
                                }
                            }
                            .onFailure { err -> onMessage(err.message ?: "Enter a valid Cloudflare URL first") }
                    },
                    enabled = !verifying,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(0.85f)
                        .height(48.dp)
                        .testTag("cloudflare_tunnel_verify_btn"),
                ) {
                    if (verifying) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Verify")
                }
            }

            // Expandable live Tunnel Console
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { showLogs = !showLogs }
                    .testTag("cloudflare_tunnel_logs_toggle"),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Tunnel console (${tunnelStatus.logs.size})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (showLogs && tunnelStatus.logs.isNotEmpty()) {
                            TextButton(
                                onClick = { viewModel.clearTunnelLogs() },
                                modifier = Modifier.height(34.dp),
                            ) {
                                Text("Clear", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Icon(
                            if (showLogs) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = "Toggle tunnel console",
                        )
                    }
                    AnimatedVisibility(visible = showLogs) {
                        val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
                        Column(
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .fillMaxWidth()
                                .heightIn(max = 170.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (tunnelStatus.logs.isEmpty()) {
                                Text(
                                    text = "No tunnel events yet. Configure your Cloudflare URL and tap Start Tunnel.",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                tunnelStatus.logs.takeLast(30).forEach { line ->
                                    val lineColor = when (line.level) {
                                        "ERROR" -> MaterialTheme.colorScheme.error
                                        "WARN" -> colors.amber
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                    Text(
                                        text = "[${timeFmt.format(Date(line.timestampMs))}] ${line.level} ${line.message}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = lineColor,
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

private fun copyTunnelClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
