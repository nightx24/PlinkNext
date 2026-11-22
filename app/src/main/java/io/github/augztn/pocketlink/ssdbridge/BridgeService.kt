package io.github.augztn.pocketlink.ssdbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelManager
import io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelState
import io.github.augztn.pocketlink.ssdbridge.tunnel.TunnelStatusSnapshot

class BridgeService : Service() {
    private var bridgeServer: SafHttpServer? = null
    private var apiServer: NativeApiServer? = null
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
        startForeground(NOTIFICATION_ID, buildNotification(tunnelManager.status.value))

        try {
            val prefs = getSharedPreferences("ssd-bridge", MODE_PRIVATE)
            val token = prefs.getString("token", null).orEmpty()
            if (token.isNotBlank()) {
                bridgeServer = SafHttpServer(this, token).also { it.start(SOCKET_READ_TIMEOUT_MS, false) }
                bridgeRunning = true
            }

            val credentials = ServerCredentials(this)
            if (credentials.isConfigured()) {
                apiServer = NativeApiServer(this, credentials).also {
                    it.start(SOCKET_READ_TIMEOUT_MS, false)
                    activeApiServer = it
                    it.ensureMediaIndexStarted()
                }
                apiRunning = true
            }

            check(bridgeRunning || apiRunning) {
                "Set a native server password or configure USB storage before starting PocketLink."
            }
            lastError = null
            isRunning = true

            tunnelManager.setStateListener { snapshot ->
                updateNotification(snapshot)
            }
            if (apiRunning && tunnelManager.isAutoStartEnabled()) {
                tunnelManager.startActiveTunnel(NativeApiServer.PORT)
            }
        } catch (error: Exception) {
            stopServers()
            lastError = error.message ?: "Could not start the PocketLink service."
            stopForegroundCompat()
            stopSelf()
        }
    }

    // Android may recreate this service with a null intent after reclaiming
    // the process. onCreate() rehydrates both listeners from persisted credentials.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TUNNEL -> {
                if (apiRunning) {
                    tunnelManager.startActiveTunnel(NativeApiServer.PORT)
                }
            }
            ACTION_STOP_TUNNEL -> {
                tunnelManager.stopActiveTunnel()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServers()
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

    private fun stopServers() {
        runCatching {
            if (::tunnelManager.isInitialized) {
                tunnelManager.setStateListener(null)
                tunnelManager.stopActiveTunnel()
            }
        }
        try { apiServer?.stop() } catch (_: Exception) { }
        try { bridgeServer?.stop() } catch (_: Exception) { }
        apiServer?.let { if (activeApiServer === it) activeApiServer = null }
        apiServer = null
        bridgeServer = null
        apiRunning = false
        bridgeRunning = false
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "pocketlink-ssd-bridge"
        private const val NOTIFICATION_ID = 8765
        private const val SOCKET_READ_TIMEOUT_MS = 30_000
        const val ACTION_START_TUNNEL = "io.github.augztn.pocketlink.ACTION_START_TUNNEL"
        const val ACTION_STOP_TUNNEL = "io.github.augztn.pocketlink.ACTION_STOP_TUNNEL"

        @Volatile var isRunning: Boolean = false
        @Volatile var apiRunning: Boolean = false
        @Volatile var bridgeRunning: Boolean = false
        @Volatile var lastError: String? = null
        @Volatile private var activeApiServer: NativeApiServer? = null

        fun mediaIndexStatus(): String? = activeApiServer?.mediaIndexStatus()

        fun refreshMediaIndex(): String? = activeApiServer?.refreshMediaIndex()
    }
}
