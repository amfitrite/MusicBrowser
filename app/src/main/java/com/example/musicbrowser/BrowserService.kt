package com.example.musicbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat

class BrowserService : Service() {

    private val CHANNEL_ID = "MusicBrowserChannel"
    private val NOTIFICATION_ID = 1
    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MusicBrowser::BgPlayWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        if (mediaSession == null) {
            mediaSession = MediaSessionCompat(this, "BrowserMediaSession")
        }
        
        // Force Android to believe we are constantly PLAYING media.
        // This is the #1 reason Android kills background apps when the screen turns off.
        val stateBuilder = android.support.v4.media.session.PlaybackStateCompat.Builder()
            .setActions(android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY_PAUSE or android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_NEXT or android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            .setState(android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
        mediaSession?.setPlaybackState(stateBuilder.build())
        
        // Setup Media Buttons Intents
        val playPauseIntent = Intent("com.example.musicbrowser.MEDIA_CTRL").apply { putExtra("cmd", "play_pause") }
        val prevIntent = Intent("com.example.musicbrowser.MEDIA_CTRL").apply { putExtra("cmd", "prev") }
        val nextIntent = Intent("com.example.musicbrowser.MEDIA_CTRL").apply { putExtra("cmd", "next") }

        val pPlayPause = PendingIntent.getBroadcast(this, 1, playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pPrev = PendingIntent.getBroadcast(this, 2, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pNext = PendingIntent.getBroadcast(this, 3, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Music Browser")
            .setContentText("Media Controller")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_previous, "Previous", pPrev)
            .addAction(android.R.drawable.ic_media_play, "Play/Pause", pPlayPause)
            .addAction(android.R.drawable.ic_media_next, "Next", pNext)
            .setStyle(MediaNotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .setMediaSession(mediaSession?.sessionToken))
            .build()

        startForeground(NOTIFICATION_ID, notification)
        
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire()
        }

        // START_STICKY tells the OS to recreate the service if it has enough memory
        return START_STICKY
    }

    override fun onDestroy() {
        mediaSession?.release()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Background Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
