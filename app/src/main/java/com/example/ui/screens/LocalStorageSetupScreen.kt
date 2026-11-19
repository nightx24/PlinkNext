package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.PocketLinkViewModel
import com.example.ui.components.PocketLinkTopBar
import com.example.ui.theme.IndigoPrimary
import com.example.ui.theme.LocalPocketLinkColors
import io.github.augztn.pocketlink.ssdbridge.LocalHostStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LocalStorageSetupScreen(
    viewModel: PocketLinkViewModel,
    hostStatus: LocalHostStatus,
    onBack: () -> Unit,
    onConnectLocal: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var passwordInput by remember { mutableStateOf("") }
    var obscurePassword by remember { mutableStateOf(true) }
    var showToken by remember { mutableStateOf(false) }
    var pendingVolumeId by remember { mutableStateOf<String?>(null) }
    var pendingScopedMount by remember { mutableStateOf<String?>(null) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        viewModel.refreshHostStatus()
    }

    val volumePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val volumeId = pendingVolumeId
        pendingVolumeId = null
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null && volumeId != null) {
            val uri = result.data!!.data!!
            val flags = result.data!!.flags
            viewModel.persistSafVolume(volumeId, uri, flags)
                .onSuccess { name ->
                    scope.launch { snackbarHostState.showSnackbar("Granted access to $name") }
                }
                .onFailure { err ->
                    scope.launch { snackbarHostState.showSnackbar(err.message ?: "Could not save folder access") }
                }
        }
    }

    val scopedFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val mountTarget = pendingScopedMount
        pendingScopedMount = null
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            val uri = result.data!!.data!!
            val flags = result.data!!.flags
            viewModel.persistScopedFolderMount(mountTarget, uri, flags)
                .onSuccess { name ->
                    scope.launch { snackbarHostState.showSnackbar("Mounted $name for Host scanning") }
                }
                .onFailure { err ->
                    scope.launch { snackbarHostState.showSnackbar(err.message ?: "Could not mount folder") }
                }
        }
    }

    val savedLocalPassword = remember(viewModel.connection.value.profiles) {
        viewModel.connection.value.profiles.firstOrNull {
            (it.host == "127.0.0.1" || it.host == "localhost") && it.port == 8000 && it.password.isNotEmpty()
        }?.password
    }
    var revealSavedPassword by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshHostStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(3000L)
            viewModel.refreshHostStatus()
        }
    }

    LaunchedEffect(hostStatus.mediaIndexState) {
        while (hostStatus.mediaIndexState == "scanning") {
            delay(1500L)
            viewModel.refreshHostStatus()
        }
    }

    Scaffold(
        topBar = {
            PocketLinkTopBar(
                title = "Host on this device",
                subtitle = "Native server · Port 8000",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { viewModel.refreshHostStatus() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh host status")
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
                    .widthIn(max = 620.dp)
                    .fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    HostStatusBanner(
                        hostStatus = hostStatus,
                        onToggleServer = {
                            if (hostStatus.running) {
                                viewModel.stopLocalHost()
                            } else {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                viewModel.startLocalHost().onFailure { err ->
                                    scope.launch { snackbarHostState.showSnackbar(err.message ?: "Could not start server") }
                                }
                            }
                        },
                        onOpenTailscale = {
                            val intent = viewModel.hostController.createOpenTailscaleIntent()
                            if (intent != null) {
                                runCatching { context.startActivity(intent) }
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("Install the Tailscale app on both devices to connect remotely") }
                            }
                        },
                        onCopyAddress = { addr ->
                            copyText(context, "PocketLink Address", addr)
                            scope.launch { snackbarHostState.showSnackbar("Copied $addr") }
                        },
                        onConnectLocal = onConnectLocal,
                    )
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Rounded.Lock, contentDescription = null, tint = IndigoPrimary)
                                Text("1. Server admin password", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.weight(1f))
                                StatusBadge(
                                    label = if (hostStatus.serverPasswordConfigured) "Configured" else "Required",
                                    ok = hostStatus.serverPasswordConfigured,
                                )
                            }
                            Text(
                                text = "Username is always admin. Stored as a salted PBKDF2-HMAC-SHA256 verifier (minimum 12 characters). Enter this same password on your Client phone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!savedLocalPassword.isNullOrEmpty()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "Saved host password (for Client device)",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                text = if (revealSavedPassword) savedLocalPassword else "••••••••••••••••",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                        IconButton(onClick = { revealSavedPassword = !revealSavedPassword }) {
                                            Icon(
                                                if (revealSavedPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                                contentDescription = "Reveal saved host password",
                                            )
                                        }
                                        IconButton(onClick = {
                                            copyText(context, "PocketLink Password", savedLocalPassword)
                                            scope.launch { snackbarHostState.showSnackbar("Copied host password") }
                                        }) {
                                            Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy host password")
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text(if (hostStatus.serverPasswordConfigured) "New admin password" else "Admin password (12+ chars)") },
                                trailingIcon = {
                                    IconButton(onClick = { obscurePassword = !obscurePassword }) {
                                        Icon(
                                            if (obscurePassword) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                            contentDescription = "Toggle password visibility",
                                        )
                                    }
                                },
                                visualTransformation = if (obscurePassword) PasswordVisualTransformation() else VisualTransformation.None,
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("host_password_input"),
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Button(
                                    onClick = {
                                        viewModel.setHostPassword(passwordInput)
                                            .onSuccess {
                                                passwordInput = ""
                                                scope.launch { snackbarHostState.showSnackbar("Server admin password updated") }
                                            }
                                            .onFailure { err ->
                                                scope.launch { snackbarHostState.showSnackbar(err.message ?: "Invalid password") }
                                            }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("save_host_password_btn"),
                                ) {
                                    Text(if (hostStatus.serverPasswordConfigured) "Update password" else "Save password")
                                }
                                OutlinedButton(
                                    onClick = {
                                        viewModel.generateAndSaveHostPassword()
                                            .onSuccess { generated ->
                                                revealSavedPassword = true
                                                copyText(context, "PocketLink Password", generated)
                                                scope.launch { snackbarHostState.showSnackbar("Generated & copied new host password") }
                                            }
                                            .onFailure { err ->
                                                scope.launch { snackbarHostState.showSnackbar(err.message ?: "Could not generate password") }
                                            }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text("Generate 16-char")
                                }
                            }
                        }
                    }
                }

                item {
                    val colors = LocalPocketLinkColors.current
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .border(1.dp, colors.cardBorder, RoundedCornerShape(20.dp))
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Rounded.SdStorage, contentDescription = null, tint = colors.cyan)
                                Text("2. Storage & background access", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                StatusBadge(
                                    label = when (hostStatus.scopedStorageMode) {
                                        "full" -> "All Files"
                                        "mt_scoped" -> "MT Scoped"
                                        else -> "Sandbox"
                                    },
                                    ok = hostStatus.scopedStorageMode != "sandbox_media",
                                )
                            }

                            PermissionActionRow(
                                icon = Icons.Rounded.FolderShared,
                                title = "Internal shared storage (All Files)",
                                subtitle = if (hostStatus.internalStorageGranted) {
                                    "Serving full device shared storage directly"
                                } else {
                                    "Optional on MTK / Restricted Settings. Use MT-Style Folder Scan below if Grant is blocked."
                                },
                                granted = hostStatus.internalStorageGranted,
                                buttonLabel = "Grant",
                                onAction = {
                                    val intent = viewModel.hostController.createInternalStoragePermissionIntent()
                                    if (intent != null) {
                                        runCatching { context.startActivity(intent) }
                                            .onFailure {
                                                runCatching {
                                                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                                }
                                            }
                                    }
                                },
                            )

                            if (!hostStatus.internalStorageGranted) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = "MT-Style Scoped Folder Scan (No Full Permission)",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = colors.teal,
                                        )
                                        Text(
                                            text = "Tap Grant on folders like Music, Movies, Pictures, DCIM, Documents or Download — works on MTK & Android 11–15 without All Files permission.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )

                                        hostStatus.scopedFolders.forEach { folder ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(folder.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                                    Text(
                                                        text = when (folder.mode) {
                                                            "direct" -> "Direct readable"
                                                            "saf" -> "Mounted via SAF"
                                                            "full" -> "Full access"
                                                            else -> "Tap Grant to pick via system files"
                                                        },
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                                when (folder.mode) {
                                                    "saf" -> {
                                                        StatusBadge(label = "SAF Active", ok = true)
                                                        IconButton(
                                                            onClick = { viewModel.removeScopedFolderMount(folder.key) },
                                                            modifier = Modifier.size(32.dp),
                                                        ) {
                                                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Unmount ${folder.label}", modifier = Modifier.size(16.dp))
                                                        }
                                                    }
                                                    "direct", "full" -> StatusBadge(label = "Readable", ok = true)
                                                    else -> FilledTonalButton(
                                                        onClick = {
                                                            pendingScopedMount = folder.mountPath
                                                            scopedFolderLauncher.launch(
                                                                viewModel.createScopedFolderPickerIntent(folder.mountPath)
                                                            )
                                                        },
                                                        shape = RoundedCornerShape(10.dp),
                                                    ) {
                                                        Text("Grant")
                                                    }
                                                }
                                            }
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                pendingScopedMount = null
                                                scopedFolderLauncher.launch(viewModel.createScopedFolderPickerIntent(null))
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("pick_custom_scoped_folder_btn"),
                                        ) {
                                            Icon(Icons.Rounded.CreateNewFolder, contentDescription = null)
                                            Spacer(Modifier.width(8.dp))
                                            Text("Pick any folder or Download subfolder (SAF)")
                                        }
                                    }
                                }
                            }

                            PermissionActionRow(
                                icon = Icons.Rounded.BatteryChargingFull,
                                title = "Unrestricted background",
                                subtitle = "Keep server reachable when screen is off",
                                granted = hostStatus.backgroundAccessGranted,
                                buttonLabel = "Allow",
                                onAction = {
                                    val intent = viewModel.hostController.createBackgroundOptimizationIntent()
                                    if (intent != null) {
                                        runCatching { context.startActivity(intent) }
                                    }
                                },
                            )
                        }
                    }
                }

                item {
                    val colors = LocalPocketLinkColors.current
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .border(1.dp, colors.cardBorder, RoundedCornerShape(20.dp))
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Rounded.Usb, contentDescription = null, tint = colors.cyan)
                                Text("3. USB drives & SAF folders", style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                text = "Grant persistent Storage Access Framework permission for external SSDs or custom folders.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (hostStatus.discoveredVolumes.isEmpty()) {
                                Text(
                                    text = "No removable USB drives detected right now. You can still attach any folder via the system picker.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                hostStatus.discoveredVolumes.forEach { vol ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(Icons.Rounded.Usb, contentDescription = null, tint = colors.cyan)
                                            Spacer(Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(vol.name, style = MaterialTheme.typography.titleSmall)
                                                Text(
                                                    text = "${vol.access.uppercase()} · ${vol.state}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            if (vol.isManual || vol.access == "saf") {
                                                IconButton(onClick = { viewModel.removeSafVolume(vol.id) }) {
                                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove volume")
                                                }
                                            }
                                            if (!vol.selected) {
                                                FilledTonalButton(onClick = {
                                                    pendingVolumeId = vol.id
                                                    volumePickerLauncher.launch(viewModel.createVolumePickerIntent(vol.id))
                                                }) {
                                                    Text("Grant")
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    val newId = viewModel.hostController.generateManualVolumeId()
                                    pendingVolumeId = newId
                                    volumePickerLauncher.launch(viewModel.createVolumePickerIntent(null))
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Rounded.CreateNewFolder, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Add external drive or folder (SAF)")
                            }
                        }
                    }
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Rounded.Sync, contentDescription = null, tint = IndigoPrimary)
                                Text("4. Host file & media index", style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                text = "State: ${hostStatus.mediaIndexState} · ${hostStatus.mediaIndexFileCount} files · ${hostStatus.mediaIndexMediaCount} media items",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (hostStatus.mediaIndexState == "scanning") {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                hostStatus.mediaIndexCurrentSource?.let { src ->
                                    Text("Scanning $src (${hostStatus.mediaIndexScanned} items)…", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        viewModel.refreshHostMediaIndex()
                                            .onFailure { err ->
                                                scope.launch { snackbarHostState.showSnackbar(err.message ?: "Could not start index scan") }
                                            }
                                    },
                                    enabled = hostStatus.running && hostStatus.mediaIndexState != "scanning",
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Scan now")
                                }
                                OutlinedButton(
                                    onClick = {
                                        viewModel.seedSampleMedia(force = true) { res ->
                                            res.onSuccess { count ->
                                                scope.launch {
                                                    snackbarHostState.showSnackbar("Added $count sample Music, Videos, Photos & Docs")
                                                }
                                            }.onFailure { err ->
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(err.message ?: "Could not add sample media")
                                                }
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f).testTag("seed_sample_media_btn"),
                                ) {
                                    Text("Add test media")
                                }
                            }
                        }
                    }
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Local SAF bridge token (127.0.0.1:8765)", style = MaterialTheme.typography.titleSmall)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (showToken) hostStatus.bridgeToken else "••••••••••••••••••••••••",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { showToken = !showToken }) {
                                    Icon(
                                        if (showToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = "Reveal token",
                                    )
                                }
                                IconButton(onClick = {
                                    copyText(context, "SAF Bridge Token", hostStatus.bridgeToken)
                                    scope.launch { snackbarHostState.showSnackbar("Copied bridge token") }
                                }) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy token")
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
private fun HostStatusBanner(
    hostStatus: LocalHostStatus,
    onToggleServer: () -> Unit,
    onOpenTailscale: () -> Unit,
    onCopyAddress: (String) -> Unit,
    onConnectLocal: (() -> Unit)?,
) {
    val colors = LocalPocketLinkColors.current
    val activeColor = if (hostStatus.running) colors.emerald else colors.amber
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(
            modifier = Modifier
                .border(1.dp, activeColor.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(activeColor)
                )
                Text(
                    text = if (hostStatus.running) "PocketLink server is running" else "PocketLink server is stopped",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (!hostStatus.error.isNullOrBlank()) {
                Text(
                    text = hostStatus.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (hostStatus.tailscaleIps.isEmpty()) {
                Surface(
                    color = colors.amber.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = colors.amber, modifier = Modifier.size(18.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Tailscale VPN not detected",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.amber,
                            )
                            Text(
                                "Open Tailscale and connect VPN on BOTH devices so your other phone can connect using this device's 100.x.y.z IP.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (hostStatus.tailscaleInstalled) {
                            FilledTonalButton(onClick = onOpenTailscale, shape = RoundedCornerShape(10.dp)) {
                                Text("Open")
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "Tailscale IP (enter this IP + Port 8000 on your other Client device):",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.emerald,
                    fontWeight = FontWeight.SemiBold,
                )
                hostStatus.tailscaleIps.forEach { ip ->
                    val displayIp = if (ip.contains(':')) "[$ip]" else ip
                    Surface(
                        color = colors.emerald.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .border(1.dp, colors.emerald.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("$displayIp:8000", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text("Username: admin · Port: 8000", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(
                                onClick = { onCopyAddress(ip) },
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy Tailscale IP $ip", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "127.0.0.1:8000 (this device only)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { onCopyAddress("127.0.0.1:8000") },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy 127.0.0.1:8000", modifier = Modifier.size(16.dp))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onToggleServer,
                    colors = if (hostStatus.running) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("toggle_local_host_btn"),
                ) {
                    Icon(if (hostStatus.running) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (hostStatus.running) "Stop server" else "Start server")
                }
                if (hostStatus.running && onConnectLocal != null) {
                    FilledTonalButton(
                        onClick = onConnectLocal,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("Open client")
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    buttonLabel: String,
    onAction: () -> Unit,
) {
    val colors = LocalPocketLinkColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (granted) colors.emerald else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) {
            StatusBadge(label = "Granted", ok = true)
        } else {
            FilledTonalButton(onClick = onAction, shape = RoundedCornerShape(10.dp)) {
                Text(buttonLabel)
            }
        }
    }
}

@Composable
private fun StatusBadge(label: String, ok: Boolean) {
    val colors = LocalPocketLinkColors.current
    val tint = if (ok) colors.emerald else colors.amber
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

private fun copyText(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
