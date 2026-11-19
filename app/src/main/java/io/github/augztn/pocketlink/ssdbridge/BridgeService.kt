package io.github.augztn.pocketlink.ssdbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.app.PendingIntent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class BridgeService : Service() {
    private var bridgeServer: SafHttpServer? = null
    private var apiServer: NativeApiServer? = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "pocketlink-ssd-bridge"
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.deleteNotificationChannel("homeserver-ssd-bridge")
            val channel = NotificationChannel(channelId, "PocketLink server", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 8765, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("PocketLink is running")
            .setContentText("Your personal server is active. Tap to manage storage and connection settings.")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        startForeground(8765, notification)

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
        } catch (error: Exception) {
            stopServers()
            lastError = error.message ?: "Could not start the PocketLink service."
            stopForegroundCompat()
            stopSelf()
        }
    }

    // Android may recreate this service with a null intent after reclaiming
    // the process. onCreate() rehydrates both listeners from persisted credentials.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopServers()
        super.onDestroy()
    }

    private fun stopServers() {
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
        private const val SOCKET_READ_TIMEOUT_MS = 30_000
        @Volatile var isRunning: Boolean = false
        @Volatile var apiRunning: Boolean = false
        @Volatile var bridgeRunning: Boolean = false
        @Volatile var lastError: String? = null
        @Volatile private var activeApiServer: NativeApiServer? = null

        fun mediaIndexStatus(): String? = activeApiServer?.mediaIndexStatus()

        fun refreshMediaIndex(): String? = activeApiServer?.refreshMediaIndex()
    }
}
