package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.ServerProfile
import com.example.data.model.classifyNetworkRoute
import com.example.data.network.PocketLinkApi
import com.example.ui.ConnectionUiState
import com.example.ui.components.RouteBadge
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.LocalPocketLinkColors
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.augztn.pocketlink.ssdbridge.LocalHostController
import io.github.augztn.pocketlink.ssdbridge.LocalHostStatus
import io.github.augztn.pocketlink.ssdbridge.tunnel.CloudflareTunnelConfig
import io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelState
import io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelStatusSnapshot
import io.github.augztn.pocketlink.ssdbridge.tunnel.buildPocketLinkConnectUri
import io.github.augztn.pocketlink.ssdbridge.tunnel.parsePocketLinkConnectInput
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    connectionState: ConnectionUiState,
    hostStatus: LocalHostStatus,
    tunnelStatus: TunnelStatusSnapshot = TunnelStatusSnapshot(),
    tunnelConfig: CloudflareTunnelConfig = CloudflareTunnelConfig(),
    ytStreamingEnabled: Boolean = true,
    onConnect: (name: String, host: String, port: String, username: String, password: String, profileId: String?) -> Unit,
    onQuickStartLocal: () -> Unit,
    onQuickConnectCloudflare: () -> Unit = {},
    hostPasswordProvider: () -> String = { "" },
    onToggleCloudflareTunnel: (Boolean) -> Unit = {},
    onDeleteProfile: (String) -> Unit,
    onOpenHostSetup: () -> Unit,
    onOpenYtMusic: () -> Unit = {},
    onOpenYtSettings: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenDiagnostics: () -> Unit,
    onOpenAbout: () -> Unit,
    onRefreshHostStatus: () -> Unit = {},
    onClearError: () -> Unit = {},
) {
    var serverName by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("8000") }
    var username by rememberSaveable { mutableStateOf("admin") }
    var password by rememberSaveable { mutableStateOf("") }
    var obscurePassword by rememberSaveable { mutableStateOf(true) }
    var pasteNotice by remember { mutableStateOf<String?>(null) }
    var profileToDelete by remember { mutableStateOf<ServerProfile?>(null) }
    var showTailscaleGuide by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshHostStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(3000L)
            onRefreshHostStatus()
        }
    }

    LaunchedEffect(connectionState.profiles) {
        if (address.isBlank() && connectionState.profiles.isNotEmpty()) {
            val first = connectionState.profiles.first()
            serverName = first.name
            address = first.host
            port = first.port.toString()
            username = first.username
            password = first.password
        }
    }

    Scaffold(
        topBar = {
            com.example.ui.components.PocketLinkTopBar(
                title = "PocketLink",
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .padding(start = 12.dp, end = 4.dp)
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.linearGradient(listOf(IndigoPrimary, CyanAccent))
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("connection_settings_btn")) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = onOpenDiagnostics, modifier = Modifier.testTag("connection_diagnostics_btn")) {
                        Icon(Icons.Rounded.BugReport, contentDescription = "Diagnostics")
                    }
                    IconButton(onClick = onOpenAbout, modifier = Modifier.testTag("connection_about_btn")) {
                        Icon(Icons.Rounded.Info, contentDescription = "About PocketLink")
                    }
                },
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (hostStatus.running) {
                    item {
                        val colors = LocalPocketLinkColors.current
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .clickable {
                                    val savedLocal = connectionState.profiles.firstOrNull {
                                        (it.host == "127.0.0.1" || it.host == "localhost") && it.port == 8000
                                    }
                                    serverName = savedLocal?.name ?: "This Device"
                                    address = "127.0.0.1"
                                    port = "8000"
                                    username = savedLocal?.username ?: "admin"
                                    if (!savedLocal?.password.isNullOrEmpty()) {
                                        password = savedLocal!!.password
                                    }
                                }
                                .testTag("local_host_running_banner"),
                            color = colors.emerald.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .border(1.dp, colors.emerald.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = colors.emerald)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Local host active on port 8000",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = colors.emerald,
                                    )
                                    Text(
                                        text = if (hostStatus.tailscaleIps.isNotEmpty()) {
                                            "Share Tailscale IP ${hostStatus.tailscaleIps.first()} (Port 8000) with your other device."
                                        } else {
                                            "Tap to fill 127.0.0.1:8000 for this device."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                if (tunnelStatus.isActive || tunnelConfig.isReadyToStart) {
                    item {
                        val colors = LocalPocketLinkColors.current
                        val isLive = tunnelStatus.state == TunnelState.CONNECTED
                        val publicUrl = tunnelStatus.publicUrl?.takeIf { it.isNotBlank() }
                            ?: tunnelConfig.activePublicUrl
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .clickable {
                                    if (publicUrl.isNotBlank()) {
                                        address = publicUrl
                                        port = "443"
                                        if (serverName.isBlank()) serverName = "PocketLink Cloud"
                                        val autoPwd = hostPasswordProvider()
                                        if (autoPwd.isNotEmpty()) password = autoPwd
                                    }
                                }
                                .testTag("cloudflare_active_banner"),
                            color = if (isLive) colors.cloudflare.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        if (isLive) colors.cloudflare.copy(alpha = 0.45f) else colors.cardBorder,
                                        RoundedCornerShape(18.dp),
                                    )
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        imageVector = if (isLive) Icons.Rounded.CloudDone else Icons.Rounded.Cloud,
                                        contentDescription = null,
                                        tint = if (isLive) colors.cloudflare else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = when (tunnelStatus.state) {
                                                TunnelState.CONNECTED -> "Cloudflare Tunnel · Online (1-Click Ready)"
                                                TunnelState.CONNECTING -> "Cloudflare Tunnel · Connecting…"
                                                TunnelState.ERROR -> "Cloudflare Tunnel · Error"
                                                TunnelState.STOPPED -> "Cloudflare Tunnel · 1-Click Auto"
                                            },
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (isLive) colors.cloudflare else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        if (publicUrl.isNotBlank()) {
                                            Text(
                                                text = publicUrl,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    if (publicUrl.isNotBlank()) {
                                        IconButton(
                                            onClick = {
                                                val link = buildPocketLinkConnectUri(
                                                    publicUrl = publicUrl,
                                                    username = "admin",
                                                    serverName = "PocketLink Cloud",
                                                    password = hostPasswordProvider(),
                                                )
                                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                cm.setPrimaryClip(android.content.ClipData.newPlainText("PocketLink 1-Tap Invite Link", link))
                                                pasteNotice = "Copied 1-Tap Invite Link (includes password) for your other device"
                                            },
                                            modifier = Modifier.size(40.dp),
                                        ) {
                                            Icon(
                                                Icons.Rounded.ContentCopy,
                                                contentDescription = "Copy Cloudflare 1-Tap Invite Link",
                                                tint = colors.cloudflare,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                    Switch(
                                        checked = tunnelStatus.isActive,
                                        onCheckedChange = onToggleCloudflareTunnel,
                                        modifier = Modifier.testTag("connection_cf_quick_switch"),
                                    )
                                }

                                Button(
                                    onClick = {
                                        val typedClean = address.trim()
                                        val isTypedRemoteCf = typedClean.contains(".trycloudflare.com", ignoreCase = true) &&
                                            !io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelManager.isThisDeviceAutoPublicUrl(typedClean)
                                        if (isTypedRemoteCf) {
                                            onConnect(
                                                serverName.ifBlank { "PocketLink Host" },
                                                typedClean,
                                                port.ifBlank { "443" },
                                                username.ifBlank { "admin" },
                                                password,
                                                null,
                                            )
                                        } else {
                                            onQuickConnectCloudflare()
                                        }
                                    },
                                    enabled = !connectionState.isConnecting,
                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                        containerColor = colors.cloudflare,
                                        contentColor = androidx.compose.ui.graphics.Color.White,
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                        .testTag("one_tap_cloudflare_connect_btn"),
                                ) {
                                    Icon(Icons.Rounded.CloudDone, contentDescription = null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "1-Tap Connect via Cloudflare (No Setup Needed)",
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    val tsActive = hostStatus.tailscaleIps.isNotEmpty()
                    val colors = LocalPocketLinkColors.current
                    val statusTint = if (tsActive) colors.cyan else colors.amber
                    Surface(
                        color = statusTint.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .border(1.dp, statusTint.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = if (tsActive) Icons.Rounded.VpnLock else Icons.Rounded.WarningAmber,
                                contentDescription = null,
                                tint = statusTint,
                                modifier = Modifier.size(20.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (tsActive) "Tailscale VPN connected" else "Tailscale VPN is off",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = statusTint,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = if (tsActive) {
                                        "This device: ${hostStatus.tailscaleIps.first()} · To connect to another phone, enter that Host phone's 100.x.y.z IP below."
                                    } else {
                                        "Turn on Tailscale VPN on both phones (same account) before connecting across devices."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (hostStatus.tailscaleInstalled) {
                                TextButton(
                                    onClick = {
                                        val intent = context.packageManager
                                            .getLaunchIntentForPackage(LocalHostController.TAILSCALE_PACKAGE)
                                            ?.apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                                        if (intent != null) runCatching { context.startActivity(intent) }
                                    }
                                ) {
                                    Text(if (tsActive) "Tailscale" else "Open VPN")
                                }
                            }
                        }
                    }
                }

                if (connectionState.profiles.isNotEmpty()) {
                    item {
                        Text(
                            text = "Saved servers",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    items(connectionState.profiles, key = { it.id }) { profile ->
                        SavedServerCard(
                            profile = profile,
                            isConnecting = connectionState.isConnecting && connectionState.connectingProfileId == profile.id,
                            onTap = {
                                serverName = profile.name
                                address = profile.host
                                port = profile.port.toString()
                                username = profile.username
                                password = profile.password
                                if (profile.password.isNotEmpty()) {
                                    onConnect(profile.name, profile.host, profile.port.toString(), profile.username, profile.password, profile.id)
                                }
                            },
                            onEdit = {
                                serverName = profile.name
                                address = profile.host
                                port = profile.port.toString()
                                username = profile.username
                                password = profile.password
                            },
                            onDelete = { profileToDelete = profile },
                        )
                    }
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            text = "Server details",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(IndigoPrimary.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Dns,
                                    contentDescription = null,
                                    tint = IndigoPrimary,
                                    modifier = Modifier.size(34.dp),
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                            Text(
                                text = "Connect to your server",
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Access your internal storage, USB SSDs, and original media over Cloudflare HTTPS Tunnel, Tailscale, or loopback.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Quick paste from Host",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = {
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clipText = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                    val parsed = parsePocketLinkConnectInput(clipText)
                                    if (parsed != null) {
                                        address = parsed.host
                                        port = parsed.port
                                        val nextUser = parsed.username ?: username.ifBlank { "admin" }
                                        val nextName = parsed.serverName ?: serverName.ifBlank { "Cloudflare Tunnel" }
                                        username = nextUser
                                        serverName = nextName
                                        val nextPwd = parsed.password?.takeIf { it.isNotEmpty() }
                                            ?: hostPasswordProvider().takeIf { it.isNotEmpty() }
                                            ?: "pocketlink-auto-auth"
                                        password = nextPwd
                                        pasteNotice = "Connecting to ${parsed.host}…"
                                        onConnect(nextName, parsed.host, parsed.port, nextUser, nextPwd, null)
                                    } else {
                                        pasteNotice = "Copy a Cloudflare URL, 1-Tap Invite Link, or Tailscale IP from your Host first."
                                    }
                                },
                                modifier = Modifier.testTag("connection_paste_link_btn"),
                            ) {
                                Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Paste Link & Connect", fontWeight = FontWeight.Bold)
                            }
                        }

                        pasteNotice?.let { note ->
                            Text(
                                text = note,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        OutlinedTextField(
                            value = serverName,
                            onValueChange = { serverName = it },
                            label = { Text("Server name (optional)") },
                            leadingIcon = { Icon(Icons.Rounded.Dns, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("server_name_input"),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = address,
                                onValueChange = { next ->
                                    val parsed = if (next.startsWith("pocketlink://", ignoreCase = true)) {
                                        parsePocketLinkConnectInput(next)
                                    } else null
                                    if (parsed != null) {
                                        address = parsed.host
                                        port = parsed.port
                                        parsed.username?.let { username = it }
                                        parsed.serverName?.let { serverName = it }
                                        password = parsed.password?.takeIf { it.isNotEmpty() }
                                            ?: hostPasswordProvider().takeIf { it.isNotEmpty() }
                                            ?: "pocketlink-auto-auth"
                                    } else {
                                        address = next
                                        val lower = next.trim().lowercase()
                                        val isHttpsOrCf = lower.startsWith("https://") ||
                                            lower.contains(".workers.dev") ||
                                            lower.contains(".trycloudflare.com") ||
                                            lower.contains(".cfargotunnel.com")
                                        if (isHttpsOrCf) {
                                            if (port == "8000") port = "443"
                                            if (password.isBlank()) {
                                                password = hostPasswordProvider().takeIf { it.isNotEmpty() } ?: "pocketlink-auto-auth"
                                            }
                                        } else if ((lower.startsWith("127.") || lower.startsWith("100.")) && port == "443") {
                                            port = "8000"
                                        }
                                    }
                                },
                                label = { Text("Server address") },
                                placeholder = { Text("https://….trycloudflare.com, 100.x.y.z, or 127.0.0.1") },
                                leadingIcon = { Icon(Icons.Rounded.Language, contentDescription = null) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(0.68f)
                                    .testTag("server_address_input"),
                            )
                            OutlinedTextField(
                                value = port,
                                onValueChange = { port = it.filter { ch -> ch.isDigit() } },
                                label = { Text("Port") },
                                leadingIcon = { Icon(Icons.Rounded.SettingsEthernet, contentDescription = null) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(0.32f)
                                    .testTag("server_port_input"),
                            )
                        }

                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Username") },
                            leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("server_username_input"),
                        )

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password (optional — auto-managed for Cloudflare)") },
                            placeholder = { Text("Leave blank for automatic Cloudflare connection") },
                            leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { obscurePassword = !obscurePassword }) {
                                    Icon(
                                        imageVector = if (obscurePassword) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                        contentDescription = if (obscurePassword) "Show password" else "Hide password",
                                    )
                                }
                            },
                            visualTransformation = if (obscurePassword) PasswordVisualTransformation() else VisualTransformation.None,
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("server_password_input"),
                        )

                        Button(
                            onClick = { onConnect(serverName, address, port, username, password, null) },
                            enabled = !connectionState.isConnecting,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("connect_server_button"),
                        ) {
                            if (connectionState.isConnecting && connectionState.connectingProfileId == null) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("Connecting…")
                            } else {
                                Icon(Icons.Rounded.Link, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Connect & save server")
                            }
                        }

                        AnimatedVisibility(visible = !connectionState.errorMessage.isNullOrBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.28f),
                                        RoundedCornerShape(14.dp),
                                    ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.WarningAmber,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = connectionState.errorMessage.orEmpty(),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(
                                        onClick = onClearError,
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Dismiss error",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    FilledTonalButton(
                        onClick = onQuickStartLocal,
                        enabled = !connectionState.isConnecting,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("quick_start_local_button"),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Use this device right now (Local Mode)")
                    }
                }

                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(enabled = !connectionState.isConnecting, onClick = onOpenHostSetup)
                            .testTag("host_on_device_card"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                    RoundedCornerShape(20.dp)
                                )
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PhoneAndroid,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Host PocketLink on this device",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "Turn this phone into your private server and grant internal or USB/SSD storage.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                item {
                    val colors = LocalPocketLinkColors.current
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(onClick = if (ytStreamingEnabled) onOpenYtMusic else onOpenYtSettings)
                            .testTag("connection_yt_music_card"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .border(
                                    1.dp,
                                    if (ytStreamingEnabled) colors.violet.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                    RoundedCornerShape(20.dp),
                                )
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(colors.violet.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LibraryMusic,
                                    contentDescription = null,
                                    tint = colors.violet,
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (ytStreamingEnabled) "Discover" else "Discover (Off)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = if (ytStreamingEnabled) {
                                        "Stream directly via NewPipe without a host server · charts, search & synced lyrics."
                                    } else {
                                        "Turned off in Settings · tap to enable or customize Discover."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = onOpenYtSettings,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = "Discover settings",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showTailscaleGuide = !showTailscaleGuide }
                                .padding(14.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.VpnLock, contentDescription = null, tint = IndigoPrimary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "How to connect via Cloudflare Tunnel or Tailscale",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    if (showTailscaleGuide) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = "Toggle guide",
                                )
                            }
                            AnimatedVisibility(visible = showTailscaleGuide) {
                                Column(
                                    modifier = Modifier.padding(top = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        "1-Tap Cloudflare Tunnel (Zero Setup): Tap \"1-Tap Connect via Cloudflare\" at the top of this screen — PocketLink automatically generates your password, starts the host server & Cloudflare tunnel, and connects immediately.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "Connect from another phone via Cloudflare: On the Host phone tap \"Copy 1-Tap Invite Link\" (which includes the Cloudflare URL + password), send it to your other phone, and tap \"Paste Invite Link & Connect\" here.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        "Tailscale VPN: Turn on Tailscale on BOTH phones with the same account, start the server on the HOST phone, then enter its 100.x.y.z IP here (Port 8000, Username admin, Host password).",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Saved passwords are encrypted with Android Keystore. Plaintext HTTP is restricted to loopback and Tailscale.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                    )
                }
            }
        }
    }

    profileToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text("Remove server?") },
            text = { Text("Remove ${target.displayName} (${target.address}) from saved servers?") },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        onDeleteProfile(target.id)
                        profileToDelete = null
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { profileToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SavedServerCard(
    profile: ServerProfile,
    isConnecting: Boolean,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val route = remember(profile.address) {
        runCatching { classifyNetworkRoute(PocketLinkApi.normalizeBaseUrl(profile.address)) }
            .getOrDefault(com.example.data.model.NetworkRouteType.PUBLIC)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onTap)
            .testTag("saved_server_${profile.id}"),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(IndigoPrimary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Dns, contentDescription = null, tint = IndigoPrimary)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = profile.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RouteBadge(route)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${profile.address} · ${profile.username}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isConnecting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Server options")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
