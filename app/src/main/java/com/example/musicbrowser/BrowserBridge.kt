package com.example.musicbrowser

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension

/** One native WebExtension port and one transport-command route for all supported sites. */
object BrowserBridge {
    private const val TAG = "BrowserBridge"
    @Volatile private var appContext: Context? = null
    @Volatile private var nativePort: WebExtension.Port? = null
    @Volatile var currentTrack: MusicTrack? = null
        private set
    @Volatile var currentWebFps: Int = 0
        private set
    @Volatile private var lastHistoryKey: String = ""
    @Volatile private var installStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun install(context: Context, runtime: GeckoRuntime) {
        val app = context.applicationContext
        appContext = app
        synchronized(this) {
            if (installStarted) return
            installStarted = true
        }

        runtime.webExtensionController
            .installBuiltIn("resource://android/assets/extensions/background_play/")
            .accept({ }, { error -> Log.e(TAG, "Background extension install failed", error) })

        runtime.webExtensionController
            .installBuiltIn("resource://android/assets/extensions/skip_outro/")
            .accept({ extension ->
                if (extension == null) {
                    Log.e(TAG, "Media extension installation returned null")
                    return@accept
                }
                extension.setMessageDelegate(object : WebExtension.MessageDelegate {
                    override fun onConnect(port: WebExtension.Port) {
                        nativePort = port
                        port.setDelegate(object : WebExtension.PortDelegate {
                            override fun onPortMessage(message: Any, port: WebExtension.Port) {
                                val json = message as? JSONObject ?: return
                                when (json.optString("action")) {
                                    "request_config" -> sendConfig(app)
                                    "trigger_media_next" -> dispatchAndroidMediaNext()
                                    "report_web_fps" -> {
                                        currentWebFps = json.optInt("fps", 0)
                                    }
                                }
                                if (json.optString("type") == "MEDIA_STATE") {
                                    receiveMediaState(app, json)
                                }
                            }
                        })
                        sendConfig(app)
                    }
                }, "musicbrowser.internal")
            }, { error ->
                synchronized(this) { installStarted = false }
                Log.e(TAG, "Media bridge extension install failed", error)
            })
    }

    fun sendConfig(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("BrowserPrefs", Context.MODE_PRIVATE)
        post(
            JSONObject()
                .put("type", "SET_CONFIG")
                .put("skipTime", prefs.getInt("SKIP_OUTRO_TIME", 0))
                .put("skipMethod", prefs.getString("SKIP_OUTRO_METHOD", "B"))
        )
    }

    fun sendMediaCommand(command: String) {
        post(JSONObject().put("type", "MEDIA_CMD").put("cmd", command))
    }

    private fun post(message: JSONObject) {
        mainHandler.post {
            try {
                nativePort?.postMessage(message)
            } catch (error: Exception) {
                Log.e(TAG, "Unable to post message to Gecko extension", error)
            }
        }
    }

    private fun dispatchAndroidMediaNext() {
        val context = appContext
        if (context == null) {
            sendMediaCommand("next")
            return
        }
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
        } catch (error: Exception) {
            Log.e(TAG, "Unable to dispatch Android media-next key", error)
            sendMediaCommand("next")
        }
    }

    private fun receiveMediaState(context: Context, json: JSONObject) {
        val duration = (json.optDouble("duration", 0.0) * 1000.0).toLong().coerceAtLeast(0L)
        val position = (json.optDouble("currentTime", 0.0) * 1000.0).toLong().coerceAtLeast(0L)
        val track = MusicTrack(
            title = json.optString("title", "").trim(),
            artist = json.optString("artist", "").trim(),
            url = json.optString("url", ""),
            provider = json.optString("provider", "Web"),
            artworkUrl = json.optString("artwork", ""),
            durationMs = duration,
            positionMs = position,
            isPlaying = json.optBoolean("isPlaying", false)
        )
        currentTrack = track

        if (track.isPlaying && track.stableKey() != lastHistoryKey) {
            lastHistoryKey = track.stableKey()
            MusicLibrary.recordHistory(context, track)
        }

        val stateIntent = Intent(context, BrowserService::class.java)
            .setAction(BrowserService.ACTION_MEDIA_STATE)
            .putExtra(BrowserService.EXTRA_TITLE, track.title)
            .putExtra(BrowserService.EXTRA_ARTIST, track.artist)
            .putExtra(BrowserService.EXTRA_URL, track.url)
            .putExtra(BrowserService.EXTRA_PROVIDER, track.provider)
            .putExtra(BrowserService.EXTRA_ARTWORK, track.artworkUrl)
            .putExtra(BrowserService.EXTRA_DURATION, track.durationMs)
            .putExtra(BrowserService.EXTRA_POSITION, track.positionMs)
            .putExtra(BrowserService.EXTRA_PLAYING, track.isPlaying)
        try {
            context.startService(stateIntent)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to update media service", error)
        }
    }
}