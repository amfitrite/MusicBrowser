package com.example.musicbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat

/** Android MediaSession, lock-screen and notification controls for browser playback. */
class BrowserService : Service() {
    companion object {
        const val ACTION_MEDIA_STATE = "com.example.musicbrowser.MEDIA_STATE"
        const val ACTION_NEXT = "com.example.musicbrowser.NEXT"
        const val ACTION_PREVIOUS = "com.example.musicbrowser.PREVIOUS"
        const val ACTION_TOGGLE = "com.example.musicbrowser.TOGGLE"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_URL = "url"
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_ARTWORK = "artwork"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_POSITION = "position"
        const val EXTRA_PLAYING = "playing"
    }

    private val channelId = "MusicBrowserChannel"
    private val notificationId = 1
    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    private var title = "Music Browser"
    private var artist = ""
    private var provider = ""
    private var durationMs = 0L
    private var positionMs = 0L
    private var playing = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicBrowser::Playback").apply {
            setReferenceCounted(false)
        }
        mediaSession = MediaSessionCompat(this, "MusicBrowserMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = BrowserBridge.sendMediaCommand("play")
                override fun onPause() = BrowserBridge.sendMediaCommand("pause")
                override fun onSkipToNext() = BrowserBridge.sendMediaCommand("next")
                override fun onSkipToPrevious() = BrowserBridge.sendMediaCommand("prev")
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_MEDIA_STATE) updateFromIntent(intent)

        if (!foregroundStarted) {
            startForeground(notificationId, buildNotification())
            foregroundStarted = true
        } else {
            refreshSession()
            updateNotification()
        }
        return START_STICKY
    }

    private fun updateFromIntent(intent: Intent) {
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Music Browser" }
        artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty()
        provider = intent.getStringExtra(EXTRA_PROVIDER).orEmpty()
        durationMs = intent.getLongExtra(EXTRA_DURATION, 0L)
        positionMs = intent.getLongExtra(EXTRA_POSITION, 0L)
        playing = intent.getBooleanExtra(EXTRA_PLAYING, false)
        updateWakeLock()
        refreshSession()
    }

    private fun refreshSession() {
        val session = mediaSession ?: return
        val state = when {
            playing -> PlaybackStateCompat.STATE_PLAYING
            title != "Music Browser" -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_NONE
        }
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, positionMs, if (playing) 1f else 0f)
                .build()
        )
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, provider)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                .build()
        )
        session.isActive = true
    }

    private fun updateWakeLock() {
        val lock = wakeLock ?: return
        if (playing) {
            try {
                if (lock.isHeld) lock.release()
                // Media-state heartbeats renew a bounded wake lock during active playback only.
                lock.acquire(10 * 60 * 1000L)
            } catch (_: Exception) {
            }
        } else if (lock.isHeld) {
            try { lock.release() } catch (_: Exception) {}
        }
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(notificationId, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val previous = serviceIntent(ACTION_PREVIOUS, 2)
        val toggle = serviceIntent(ACTION_TOGGLE, 3)
        val next = serviceIntent(ACTION_NEXT, 4)
        val subtitle = listOf(artist, provider).filter { it.isNotBlank() }.joinToString(" · ")
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(subtitle.ifBlank { "Universal Music Control" })
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(android.R.drawable.ic_media_previous, "Previous", previous)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                toggle
            )
            .addAction(android.R.drawable.ic_media_next, "Next", next)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
                    .setMediaSession(mediaSession?.sessionToken)
            )
            .build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, BrowserService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) {
            try { wakeLock?.release() } catch (_: Exception) {}
        }
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Music playback", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}