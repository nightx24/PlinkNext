package io.github.augztn.pocketlink.ssdbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelManager
import io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelState
import io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelStatusSnapshot
import java.security.SecureRandom
import java.util.UUID

class BridgeService : Service() {
    private lateinit var tunnelManager: TunnelManager

    override fun onCreate() {
        super.onCreate()
        tunnelManager = TunnelManager.getInstance(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.deleteNotificationChannel("homeserver-ssd-bridge")
            val channel = NotificationChannel(CHANNEL_ID, "PocketLink server", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
        val notification = buildNotification(tunnelManager.status.value)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure {
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }

        try {
            val boundPort = ensureInProcessServersRunning(this)
            tunnelManager.setStateListener { snapshot ->
                updateNotification(snapshot)
            }
            if (apiRunning && tunnelManager.isAutoStartEnabled() && !tunnelManager.status.value.isActive) {
                tunnelManager.startActiveTunnel(boundPort)
            }
        } catch (error: Exception) {
            lastError = error.message ?: "Could not start the PocketLink service."
        }
    }

    // Android may recreate this service with a null intent after reclaiming
    // the process. onCreate() rehydrates both listeners from persisted credentials.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            if (!apiRunning) {
                ensureInProcessServersRunning(this)
            }
        }
        when (intent?.action) {
            ACTION_START_TUNNEL -> {
                if (apiRunning) {
                    tunnelManager.startActiveTunnel(activeApiPort)
                }
            }
            ACTION_STOP_TUNNEL -> {
                tunnelManager.stopActiveTunnel()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching {
            if (::tunnelManager.isInitialized) {
                tunnelManager.setStateListener(null)
            }
        }
        super.onDestroy()
    }

    private fun buildNotification(tunnelSnapshot: TunnelStatusSnapshot): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, NOTIFICATION_ID, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val title = when (tunnelSnapshot.state) {
            TunnelState.CONNECTED -> "PocketLink + Cloudflare Tunnel active"
            TunnelState.CONNECTING -> "PocketLink running · Connecting Cloudflare…"
            else -> "PocketLink is running"
        }
        val text = when (tunnelSnapshot.state) {
            TunnelState.CONNECTED -> tunnelSnapshot.publicUrl?.let { "Public HTTPS: $it" }
                ?: "Local server & Cloudflare Tunnel active."
            else -> "Your personal server is active. Tap to manage storage and Cloudflare Tunnel."
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(snapshot: TunnelStatusSnapshot) {
        if (!isRunning) return
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, buildNotification(snapshot))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "pocketlink-ssd-bridge"
        private const val NOTIFICATION_ID = 8765
        private const val SOCKET_READ_TIMEOUT_MS = 30_000
        const val ACTION_START_TUNNEL = "io.github.augztn.pocketlink.ACTION_START_TUNNEL"
        const val ACTION_STOP_TUNNEL = "io.github.augztn.pocketlink.ACTION_STOP_TUNNEL"

        private val serverLock = Any()

        @Volatile var isRunning: Boolean = false
        @Volatile var apiRunning: Boolean = false
        @Volatile var bridgeRunning: Boolean = false
        @Volatile var lastError: String? = null
        @Volatile var activeApiPort: Int = NativeApiServer.PORT
        @Volatile var activeBridgePort: Int = SafHttpServer.DEFAULT_PORT
        val activeInstanceId: String = UUID.randomUUID().toString()

        @Volatile private var activeApiServer: NativeApiServer? = null
        @Volatile private var activeBridgeServer: SafHttpServer? = null

        fun ensureInProcessServersRunning(context: Context): Int = synchronized(serverLock) {
            val appContext = context.applicationContext
            val existingApi = activeApiServer
            if (existingApi != null && existingApi.isAlive && apiRunning) {
                isRunning = true
                lastError = null
                return activeApiPort
            }

            // Clean up any stale instance first
            runCatching { existingApi?.stop() }
            activeApiServer = null
            apiRunning = false

            val prefs = appContext.getSharedPreferences("ssd-bridge", MODE_PRIVATE)
            val token = prefs.getString("token", null).orEmpty()
            if (token.isNotBlank() && (activeBridgeServer == null || activeBridgeServer?.isAlive != true)) {
                runCatching { activeBridgeServer?.stop() }
                activeBridgeServer = null
                bridgeRunning = false
                for (candidateBridgePort in listOf(8765, 8766, 8767, 8768, 8769, 8770)) {
                    val started = runCatching {
                        SafHttpServer(appContext, token, candidateBridgePort).also {
                            it.start(SOCKET_READ_TIMEOUT_MS, false)
                        }
                    }.getOrNull()
                    if (started != null) {
                        activeBridgeServer = started
                        activeBridgePort = started.listeningPort.takeIf { it > 0 } ?: candidateBridgePort
                        bridgeRunning = true
                        break
                    }
                }
            }

            val credentials = ServerCredentials(appContext)
            if (!credentials.isConfigured()) {
                val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
                val generated = Base64.encodeToString(
                    bytes,
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                )
                credentials.setPassword(generated)
            }

            val candidatePorts = listOf(8000, 8001, 8002, 8003, 8004, 8005, 8080, 0)
            var lastBindException: Exception? = null
            for (candidatePort in candidatePorts) {
                try {
                    val server = NativeApiServer(appContext, credentials, listenPort = candidatePort)
                    server.start(SOCKET_READ_TIMEOUT_MS, false)
                    val actualPort = server.listeningPort.takeIf { it > 0 } ?: candidatePort
                    activeApiServer = server
                    activeApiPort = actualPort
                    apiRunning = true
                    isRunning = true
                    lastError = null
                    server.ensureMediaIndexStarted()
                    runCatching {
                        val sub = TunnelManager.getInstance(appContext).ensureAutoProvisioned().autoSubdomain
                        io.github.augztn.pocketlink.ssdbridge.tunnel.PocketLinkPeerBridge.startHostServices(sub, actualPort)
                    }
                    return actualPort
                } catch (e: Exception) {
                    lastBindException = e
                }
            }

            lastError = lastBindException?.message ?: "Could not bind local PocketLink API server."
            isRunning = bridgeRunning || apiRunning
            if (!apiRunning && lastBindException != null) {
                throw lastBindException
            }
            return activeApiPort
        }

        fun stopInProcessServers() = synchronized(serverLock) {
            runCatching { io.github.augztn.pocketlink.ssdbridge.tunnel.PocketLinkPeerBridge.stopHostServices() }
            runCatching { activeApiServer?.stop() }
            runCatching { activeBridgeServer?.stop() }
            activeApiServer = null
            activeBridgeServer = null
            apiRunning = false
            bridgeRunning = false
            isRunning = false
        }

        fun mediaIndexStatus(): String? = activeApiServer?.mediaIndexStatus()

        fun refreshMediaIndex(): String? = activeApiServer?.refreshMediaIndex()
    }
}
