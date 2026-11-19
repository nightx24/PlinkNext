package io.github.augztn.pocketlink.ssdbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/**
 * Android media-session adapter exposing transport controls
 * to Android System UI, Bluetooth headsets and the lock screen.
 */
class MediaPlaybackService : Service() {
    private var session: MediaSessionCompat? = null
    private var title: String = "PocketLink"
    private var artist: String = "Personal music library"
    private var playing = false
    private var positionMs = 0L
    private var durationMs = 0L
    private var audioFocusRequest: AudioFocusRequest? = null
    private var resumeAfterFocus = false
    private var hasAudioFocus = false
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (resumeAfterFocus) {
                    resumeAfterFocus = false
                    playing = true
                    MediaPlaybackBridge.commandHandler?.invoke(ACTION_PLAY, null)
                    updateSessionAndNotification()
                }
            }
            // Notification sounds and other short, duckable sounds should not
            // pause music. Android may briefly lower media volume automatically.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit
            // Calls and other non-duckable interruptions still pause playback.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                resumeAfterFocus = playing
                pauseForFocusLoss()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                resumeAfterFocus = false
                pauseForFocusLoss()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val mediaButtonComponent = ComponentName(this, MediaButtonReceiver::class.java)
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(mediaButtonComponent)
        val mediaButtonPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            mediaButtonIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session = MediaSessionCompat(
            this,
            "PocketLinkMedia",
            mediaButtonComponent,
            mediaButtonPendingIntent,
        ).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = dispatch(ACTION_PLAY)
                override fun onPause() = dispatch(ACTION_PAUSE)
                override fun onSkipToNext() = dispatch(ACTION_NEXT)
                override fun onSkipToPrevious() = dispatch(ACTION_PREVIOUS)
                override fun onSeekTo(pos: Long) {
                    MediaPlaybackBridge.commandHandler?.invoke(ACTION_SEEK, pos)
                }
                override fun onStop() = dispatch(ACTION_STOP)
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            MediaButtonReceiver.handleIntent(session, intent)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PLAY, LEGACY_ACTION_PLAY -> {
                dispatch(ACTION_PLAY)
                return START_NOT_STICKY
            }
            ACTION_PAUSE, LEGACY_ACTION_PAUSE -> {
                dispatch(ACTION_PAUSE)
                return START_NOT_STICKY
            }
            ACTION_NEXT, LEGACY_ACTION_NEXT -> {
                dispatch(ACTION_NEXT)
                return START_NOT_STICKY
            }
            ACTION_PREVIOUS, LEGACY_ACTION_PREVIOUS -> {
                dispatch(ACTION_PREVIOUS)
                return START_NOT_STICKY
            }
            ACTION_STOP, LEGACY_ACTION_STOP -> {
                dispatch(ACTION_STOP)
                return START_NOT_STICKY
            }
            ACTION_DISMISS, LEGACY_ACTION_DISMISS -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        intent?.let {
            title = it.getStringExtra(EXTRA_TITLE) ?: title
            artist = it.getStringExtra(EXTRA_ARTIST) ?: artist
            playing = it.getBooleanExtra(EXTRA_PLAYING, playing)
            positionMs = it.getLongExtra(EXTRA_POSITION, positionMs).coerceAtLeast(0)
            durationMs = it.getLongExtra(EXTRA_DURATION, durationMs).coerceAtLeast(0)
        }
        if (playing && !requestAudioFocus()) {
            playing = false
            MediaPlaybackBridge.commandHandler?.invoke(ACTION_PAUSE, null)
        } else if (!playing && !resumeAfterFocus) {
            abandonAudioFocus()
        }
        updateSession()
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun dispatch(action: String) {
        when (action) {
            ACTION_PLAY -> {
                if (!requestAudioFocus()) return
                playing = true
            }
            ACTION_PAUSE, ACTION_STOP -> {
                playing = false
                resumeAfterFocus = false
                abandonAudioFocus()
            }
        }
        MediaPlaybackBridge.commandHandler?.invoke(action, null)
        updateSessionAndNotification()
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        hasAudioFocus = granted
        return granted
    }

    private fun abandonAudioFocus() {
        hasAudioFocus = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
    }

    private fun pauseForFocusLoss() {
        if (playing) {
            playing = false
            MediaPlaybackBridge.commandHandler?.invoke(ACTION_PAUSE, null)
            updateSessionAndNotification()
        }
    }

    private fun updateSessionAndNotification() {
        updateSession()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun updateSession() {
        MediaPlaybackBridge.isPlaying = playing
        val activeSession = session ?: return
        activeSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                .build()
        )
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP
        activeSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    positionMs,
                    if (playing) 1f else 0f
                )
                .build()
        )
    }

    private fun buildNotification(): Notification {
        val playPauseAction = if (playing) ACTION_PAUSE else ACTION_PLAY
        val playPauseIcon = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(android.R.drawable.ic_media_previous, "Previous", actionIntent(ACTION_PREVIOUS, 1))
            .addAction(playPauseIcon, if (playing) "Pause" else "Play", actionIntent(playPauseAction, 2))
            .addAction(android.R.drawable.ic_media_next, "Next", actionIntent(ACTION_NEXT, 3))
            .setDeleteIntent(actionIntent(ACTION_DISMISS, 4))
            .setStyle(
                MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        return builder.build()
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MediaPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.deleteNotificationChannel("homeserver-music")
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        MediaPlaybackBridge.isPlaying = false
        abandonAudioFocus()
        resumeAfterFocus = false
        session?.isActive = false
        session?.release()
        session = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_UPDATE = "io.github.augztn.pocketlink.media.UPDATE"
        const val ACTION_PLAY = "io.github.augztn.pocketlink.media.PLAY"
        const val ACTION_PAUSE = "io.github.augztn.pocketlink.media.PAUSE"
        const val ACTION_NEXT = "io.github.augztn.pocketlink.media.NEXT"
        const val ACTION_PREVIOUS = "io.github.augztn.pocketlink.media.PREVIOUS"
        const val ACTION_SEEK = "io.github.augztn.pocketlink.media.SEEK"
        const val ACTION_STOP = "io.github.augztn.pocketlink.media.STOP"
        const val ACTION_DISMISS = "io.github.augztn.pocketlink.media.DISMISS"
        private const val LEGACY_ACTION_PLAY = "dev.homeserver.media.PLAY"
        private const val LEGACY_ACTION_PAUSE = "dev.homeserver.media.PAUSE"
        private const val LEGACY_ACTION_NEXT = "dev.homeserver.media.NEXT"
        private const val LEGACY_ACTION_PREVIOUS = "dev.homeserver.media.PREVIOUS"
        private const val LEGACY_ACTION_STOP = "dev.homeserver.media.STOP"
        private const val LEGACY_ACTION_DISMISS = "dev.homeserver.media.DISMISS"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_PLAYING = "playing"
        const val EXTRA_POSITION = "position"
        const val EXTRA_DURATION = "duration"
        private const val CHANNEL_ID = "pocketlink-music"
        private const val NOTIFICATION_ID = 8766
    }
}

internal object MediaPlaybackBridge {
    @Volatile var isPlaying: Boolean = false
    @Volatile var commandHandler: ((String, Long?) -> Unit)? = null
}
