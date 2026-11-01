package com.example.musicbrowser

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.Display
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var geckoView: org.mozilla.geckoview.GeckoView
    private lateinit var geckoSession: GeckoSession
    private lateinit var geckoRuntime: GeckoRuntime
    private lateinit var menuContainer: MaterialCardView
    private lateinit var gestureDetector: android.view.GestureDetector
    
    private val PREFS_NAME = "BrowserPrefs"
    
    // Variables to track the current page
    private var currentTitle: String = "Unknown Title"
    private var currentUrl: String = ""

    // Cached SharedPreferences values for performance
    private var bgPlayEnabled: Boolean = true
    private var autoHideToolbar: Boolean = true
    private var toolbarSensitivity: String = "FAST"
    private var toolbarPosition: String = "BOTTOM"
    private var instantTapEnabled: Boolean = true

    // FPS meter + display performance variables
    private lateinit var fpsMeterText: TextView
    private var isFpsMeterEnabled = true
    private var performanceModeEnabled = true
    private var maxSupportedRefreshRate = 60f
    private var displayRefreshRate = 60f
    private var targetRefreshRate = 60f
    private var frameCount = 0
    private var droppedFrameCount = 0
    private var frameIntervalTotalNanos = 0L
    private var frameIntervalSamples = 0
    private var fpsWindowStartNanos = 0L
    private var lastFrameTimeNanos = 0L

    // Choreographer callbacks are synchronized to the display. This reports
    // actual UI cadence; it must not be presented as uncapped Gecko render FPS.
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isFpsMeterEnabled) return

            if (fpsWindowStartNanos == 0L) {
                fpsWindowStartNanos = frameTimeNanos
            }
            if (lastFrameTimeNanos != 0L) {
                val delta = frameTimeNanos - lastFrameTimeNanos
                val expectedInterval = 1_000_000_000.0 / displayRefreshRate.coerceAtLeast(1f)
                frameIntervalTotalNanos += delta
                frameIntervalSamples++
                if (delta > expectedInterval * 1.5) {
                    droppedFrameCount += ((delta / expectedInterval).toInt() - 1).coerceAtLeast(1)
                }
            }
            lastFrameTimeNanos = frameTimeNanos
            frameCount++

            val elapsedNanos = frameTimeNanos - fpsWindowStartNanos
            if (elapsedNanos >= 1_000_000_000L) {
                val fps = (frameCount * 1_000_000_000.0 / elapsedNanos).roundToInt()
                val averageFrameMs = if (frameIntervalSamples > 0) {
                    frameIntervalTotalNanos.toDouble() / frameIntervalSamples / 1_000_000.0
                } else {
                    0.0
                }
                val targetFps = targetRefreshRate.roundToInt().coerceAtLeast(1)
                val averageText = String.format(java.util.Locale.US, "%.1f", averageFrameMs)
                fpsMeterText.text = "FPS: $fps/$targetFps | Frame: $averageText ms | Drops: $droppedFrameCount"

                val ratio = fps.toFloat() / targetFps
                when {
                    ratio >= 0.9f && droppedFrameCount <= 1 -> fpsMeterText.setTextColor(Color.GREEN)
                    ratio >= 0.75f && droppedFrameCount <= 4 -> fpsMeterText.setTextColor(Color.YELLOW)
                    else -> fpsMeterText.setTextColor(Color.RED)
                }

                frameCount = 0
                droppedFrameCount = 0
                frameIntervalTotalNanos = 0L
                frameIntervalSamples = 0
                fpsWindowStartNanos = frameTimeNanos
                displayRefreshRate = currentRefreshRate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ActivityResultLauncher for picking .xpi files
    private val xpiFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { installXpiFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        geckoView = findViewById<org.mozilla.geckoview.GeckoView>(R.id.geckoView)
        val urlEditText = findViewById<EditText>(R.id.urlEditText)
        menuContainer = findViewById<MaterialCardView>(R.id.menuContainer)
        val settingsButton = findViewById<ImageButton>(R.id.settingsButton)
        fpsMeterText = findViewById(R.id.fpsMeterText)

        val initPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isFpsMeterEnabled = initPrefs.getBoolean("FPS_METER_ENABLED", true)
        performanceModeEnabled = initPrefs.getBoolean("PERFORMANCE_MODE_ENABLED", true)
        instantTapEnabled = initPrefs.getBoolean("INSTANT_TAP_ENABLED", true)
        applyDisplayPerformance()
        updateFpsMeterVisibility()

        bgPlayEnabled = initPrefs.getBoolean("BACKGROUND_PLAY_ENABLED", true)
        autoHideToolbar = initPrefs.getBoolean("AUTO_HIDE_TOOLBAR", true)
        toolbarSensitivity = initPrefs.getString("TOOLBAR_SENSITIVITY", "FAST") ?: "FAST"
        toolbarPosition = initPrefs.getString("TOOLBAR_POSITION", "BOTTOM") ?: "BOTTOM"

        if (BrowserSessionStore.runtime == null) {
            val runtimeSettings = org.mozilla.geckoview.GeckoRuntimeSettings.Builder()
                .allowInsecureConnections(org.mozilla.geckoview.GeckoRuntimeSettings.ALLOW_ALL)
                .consoleOutput(false)
                .doubleTapZoomingEnabled(false)
                .inputAutoZoomEnabled(false)
                .build()
            BrowserSessionStore.runtime = org.mozilla.geckoview.GeckoRuntime.create(applicationContext, runtimeSettings)
        }
        geckoRuntime = BrowserSessionStore.runtime!!
        geckoRuntime.settings.setDoubleTapZoomingEnabled(false)
        geckoRuntime.settings.setInputAutoZoomEnabled(false)

        // Keep pages on the lighter mobile viewport/user-agent path. Desktop
        // layout on a phone means more pixels, more layout work and lower FPS.
        val sessionSettings = org.mozilla.geckoview.GeckoSessionSettings.Builder()
            .suspendMediaWhenInactive(!bgPlayEnabled)
            .userAgentMode(org.mozilla.geckoview.GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(org.mozilla.geckoview.GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build()
        if (BrowserSessionStore.session == null) {
            BrowserSessionStore.session = GeckoSession(sessionSettings)
        }
        geckoSession = BrowserSessionStore.session!!
        geckoSession.settings.setSuspendMediaWhenInactive(!bgPlayEnabled)

        // Automatically grant permissions (like DRM/Widevine, EME, Autoplay, Media)
        geckoSession.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int> {
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback
            ) {
                callback.grant(video?.firstOrNull(), audio?.firstOrNull())
            }
        }

        // Ask the user before GeckoView installs, updates, or grants more access
        // to an add-on. Returning DENY here cancels every install, including
        // installs started from a local .xpi file.
        geckoRuntime.webExtensionController.promptDelegate = object : WebExtensionController.PromptDelegate {
            @Suppress("DEPRECATION")
            override fun onInstallPrompt(extension: WebExtension): GeckoResult<AllowOrDeny> {
                return showExtensionPermissionPrompt(
                    title = "Install \"${extension.displayName()}\"?",
                    extension = extension,
                    permissions = emptyArray(),
                    origins = emptyArray()
                )
            }

            override fun onInstallPrompt(
                extension: WebExtension,
                permissions: Array<out String>,
                origins: Array<out String>
            ): GeckoResult<AllowOrDeny> {
                return showExtensionPermissionPrompt(
                    title = "Install \"${extension.displayName()}\"?",
                    extension = extension,
                    permissions = permissions,
                    origins = origins
                )
            }

            override fun onUpdatePrompt(
                currentlyInstalled: WebExtension,
                updatedExtension: WebExtension,
                newPermissions: Array<out String>,
                newOrigins: Array<out String>
            ): GeckoResult<AllowOrDeny> {
                return showExtensionPermissionPrompt(
                    title = "Update \"${updatedExtension.displayName()}\"?",
                    extension = updatedExtension,
                    permissions = newPermissions,
                    origins = newOrigins,
                    intro = "This update requires these permissions:"
                )
            }

            override fun onOptionalPrompt(
                extension: WebExtension,
                permissions: Array<out String>,
                origins: Array<out String>
            ): GeckoResult<AllowOrDeny> {
                return showExtensionPermissionPrompt(
                    title = "\"${extension.displayName()}\" requests more access",
                    extension = extension,
                    permissions = permissions,
                    origins = origins
                )
            }
        }

        // Handle System Back Button
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                geckoSession.goBack()
            }
        })

        // Track Current Page Details
        geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {}
        geckoSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                currentUrl = url ?: ""
            }

            override fun onNewSession(
                session: GeckoSession,
                uri: String
            ): GeckoResult<GeckoSession> {
                session.loadUri(uri)
                return GeckoResult.fromValue(null)
            }
        }

        var lastScrollY = 0
        var isTopBarHidden = false

        geckoSession.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                if (!autoHideToolbar) {
                    if (isTopBarHidden) {
                        showTopBar()
                        isTopBarHidden = false
                    }
                    return
                }

                // A small dead zone prevents the toolbar from disappearing
                // while the finger is still settling on a button.
                val hideThreshold = if (toolbarSensitivity == "SLOW") 30 else 10
                val showThreshold = if (toolbarSensitivity == "SLOW") -20 else -6

                val dy = scrollY - lastScrollY
                if (dy > hideThreshold && scrollY > 10 && !isTopBarHidden) {
                    hideTopBar()
                    isTopBarHidden = true
                } else if ((dy < showThreshold || scrollY <= 5) && isTopBarHidden) {
                    showTopBar()
                    isTopBarHidden = false
                }
                lastScrollY = scrollY
            }
        }
        
        if (bgPlayEnabled) {
            geckoSession.setActive(true)
        }
        
        // Handle Full Screen Requests (like YouTube Fullscreen Button)
        geckoSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                val windowInsetsController = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                if (fullScreen) {
                    windowInsetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    windowInsetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                    
                    // HIDE ALL NAVBARS COMPLETELY ON FULLSCREEN
                    menuContainer.visibility = View.GONE
                } else {
                    windowInsetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    
                    // RESTORE NAVBARS ACCORDING TO PREFERENCES
                    menuContainer.visibility = View.VISIBLE
                    applyToolbarPosition()
                }
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                // A website (including addons.mozilla.org) is trying to download
                // an XPI. Install it instead of silently dropping the download.
                if (!isXpiResponse(response)) return

                val body = response.body
                if (body == null) {
                    if (response.uri.startsWith("https://")) {
                        installXpiFromUrl(response.uri)
                    }
                    return
                }

                Thread {
                    val tempFile = newXpiFile()
                    try {
                        FileOutputStream(tempFile).use { output ->
                            body.use { input -> input.copyTo(output) }
                        }
                        runOnUiThread { installXpiFile(tempFile) }
                    } catch (e: Exception) {
                        tempFile.delete()
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "Failed to download XPI: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }.start()
            }
        }

        // Attach Session to View
        if (!BrowserSessionStore.sessionOpened) {
            geckoSession.open(geckoRuntime)
            BrowserSessionStore.sessionOpened = true
        }
        geckoView.setSession(geckoSession)

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        // Start Foreground Service to keep app alive when screen is off
        val serviceIntent = android.content.Intent(this, BrowserService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
        // Install shared Media Bridge
        BrowserBridge.install(applicationContext, geckoRuntime)

        // Load YouTube by default if session is fresh
        if (currentUrl.isEmpty()) {
            geckoSession.loadUri("https://m.youtube.com")
        }

        // Setup Gesture, Menu Mode, and Navbar Position
        setupGestureDetector()
        updateMenuTriggerMode()
        applyToolbarPosition()

        // Keep gesture tracking on the browser surface only. Running it from
        // Activity.dispatchTouchEvent added work to every tap, including taps
        // on the URL bar and toolbar. Returning false lets GeckoView handle
        // the tap immediately.
        geckoView.setOnTouchListener { _, event ->
            if (autoHideToolbar && ::gestureDetector.isInitialized) {
                gestureDetector.onTouchEvent(event)
            }
            false
        }

        urlEditText.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                loadUrl(urlEditText.text.toString())
                true
            } else {
                false
            }
        }

        settingsButton.setOnClickListener {
            val popup = PopupMenu(this, settingsButton)
            popup.menuInflater.inflate(R.menu.browser_menu, popup.menu)

            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.menu_back -> {
                        geckoSession.goBack()
                        true
                    }
                    R.id.menu_forward -> {
                        geckoSession.goForward()
                        true
                    }
                    R.id.menu_reload -> {
                        geckoSession.reload()
                        true
                    }
                    R.id.menu_home -> {
                        val homePopup = PopupMenu(this, settingsButton)
                        homePopup.menuInflater.inflate(R.menu.home_menu, homePopup.menu)
                        homePopup.setOnMenuItemClickListener { homeItem ->
                            val url = when (homeItem.itemId) {
                                R.id.home_yt -> "https://m.youtube.com"
                                R.id.home_ytm -> "https://music.youtube.com"
                                R.id.home_spotify -> "https://open.spotify.com"
                                R.id.home_soundcloud -> "https://m.soundcloud.com"
                                R.id.home_applemusic -> "https://music.apple.com"
                                else -> "https://m.youtube.com"
                            }
                            loadUrl(url)
                            true
                        }
                        homePopup.show()
                        true
                    }
                    R.id.menu_my_playlist -> {
                        showPlaylistDialog()
                        true
                    }
                    R.id.menu_my_history -> {
                        showHistoryDialog()
                        true
                    }
                    R.id.menu_add_ext -> {
                        AlertDialog.Builder(this)
                            .setTitle("Add Extension")
                            .setItems(arrayOf("Browse Mozilla Add-ons", "Install from .XPI File")) { _, which ->
                                if (which == 0) {
                                    loadUrl("https://addons.mozilla.org/android/")
                                } else {
                                    xpiFilePickerLauncher.launch("*/*")
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                        true
                    }
                    R.id.menu_my_ext -> {
                        showMyExtensionsDialog()
                        true
                    }
                    R.id.menu_clear_cache -> {
                        geckoRuntime.storageController.clearData(org.mozilla.geckoview.StorageController.ClearFlags.ALL)
                        Toast.makeText(this, "Cache and Data Cleared", Toast.LENGTH_SHORT).show()
                        true
                    }
                    R.id.menu_settings -> {
                        showSettingsDialog()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun updateFpsMeterVisibility() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        if (isFpsMeterEnabled) {
            fpsMeterText.visibility = View.VISIBLE
            resetFpsWindow()
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            fpsMeterText.visibility = View.GONE
        }
    }

    private fun resetFpsWindow() {
        frameCount = 0
        droppedFrameCount = 0
        frameIntervalTotalNanos = 0L
        frameIntervalSamples = 0
        fpsWindowStartNanos = 0L
        lastFrameTimeNanos = 0L
        displayRefreshRate = currentRefreshRate()
        targetRefreshRate = if (performanceModeEnabled) {
            maxSupportedRefreshRate.coerceAtLeast(displayRefreshRate)
        } else {
            displayRefreshRate
        }
    }

    private fun currentRefreshRate(): Float {
        return try {
            val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                window.windowManager.defaultDisplay
            }
            currentDisplay?.refreshRate?.takeIf { it > 0f }
                ?: displayRefreshRate.coerceAtLeast(60f)
        } catch (e: Exception) {
            displayRefreshRate.coerceAtLeast(60f)
        }
    }

    /**
     * Requests the fastest display mode the panel actually supports.
     *
     * A 60 Hz panel is a physical limit: Android cannot present more than 60
     * unique frames per second on it. On 90/120/144 Hz panels this removes the
     * per-app 60 Hz cap so the browser can use the faster panel instead.
     */
    private fun applyDisplayPerformance() {
        try {
            val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                window.windowManager.defaultDisplay
            }
            val modes = currentDisplay?.supportedModes
            if (modes.isNullOrEmpty()) return

            maxSupportedRefreshRate = modes.maxOf { it.refreshRate }
            val params = window.attributes

            if (performanceModeEnabled) {
                // Highest refresh rate first, then highest resolution among
                // modes that share that refresh rate.
                val bestMode = modes
                    .filter { it.refreshRate > 0f }
                    .maxWithOrNull(
                        compareBy<Display.Mode> { it.refreshRate }
                            .thenBy { it.physicalWidth.toLong() * it.physicalHeight }
                    )
                if (bestMode != null) {
                    params.preferredDisplayModeId = bestMode.modeId
                    params.preferredRefreshRate = bestMode.refreshRate
                }
            } else {
                params.preferredDisplayModeId = 0
                params.preferredRefreshRate = 0f
            }
            window.attributes = params

            // Android 15+: keep the panel at its fastest rate while the user
            // is touching or scrolling instead of dropping to a power-saving rate.
            // Performance mode also opts out of adaptive-refresh-rate power
            // saving so the panel is not silently pushed back to 60 Hz.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                window.setFrameRateBoostOnTouchEnabled(performanceModeEnabled)
                window.setFrameRatePowerSavingsBalanced(!performanceModeEnabled)

                // Android 15's adaptive refresh rate is vote-based. Ask the
                // browser view (and its SurfaceView) for the fastest rate too,
                // otherwise the default "normal" vote can pin it near 60 Hz.
                val requestedFrameRate = if (performanceModeEnabled) {
                    maxSupportedRefreshRate
                } else {
                    View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
                }
                geckoView.requestedFrameRate = requestedFrameRate
                (geckoView.getChildAt(0) as? SurfaceView)?.requestedFrameRate = requestedFrameRate
            }

            displayRefreshRate = currentRefreshRate()
            targetRefreshRate = if (performanceModeEnabled) {
                maxSupportedRefreshRate.coerceAtLeast(displayRefreshRate)
            } else {
                displayRefreshRate
            }
            android.util.Log.d(
                "DisplayPerf",
                "performanceMode=$performanceModeEnabled max=${maxSupportedRefreshRate}Hz current=${displayRefreshRate}Hz"
            )
        } catch (e: Exception) {
            android.util.Log.e("DisplayPerf", "Could not apply display performance mode", e)
        }
    }

    private fun applyToolbarPosition() {
        val layoutParams = menuContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        if (toolbarPosition == "TOP") {
            layoutParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            layoutParams.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            layoutParams.topMargin = 12.dpToPx()
            layoutParams.bottomMargin = 0
        } else {
            layoutParams.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            layoutParams.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            layoutParams.topMargin = 0
            layoutParams.bottomMargin = 12.dpToPx()
        }
        menuContainer.layoutParams = layoutParams
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    private fun hideTopBar() {
        val targetY = if (toolbarPosition == "TOP") -menuContainer.height.toFloat() - 200f else menuContainer.height.toFloat() + 200f

        menuContainer.animate()
            .translationY(targetY)
            .setDuration(250)
            .start()
    }

    private fun showTopBar() {
        menuContainer.visibility = View.VISIBLE
        menuContainer.animate()
            .translationY(0f)
            .setDuration(250)
            .start()
    }

    private fun openMenu() {
        showTopBar()
    }

    private fun updateMenuTriggerMode() {
        menuContainer.visibility = View.VISIBLE
    }

    private fun setupGestureDetector() {
        gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null) {
                    val diffX = e2.x - e1.x
                    val diffY = e2.y - e1.y
                    if (Math.abs(diffX) > Math.abs(diffY)) {
                        if (diffX > 80 && Math.abs(velocityX) > 100) {
                            showTopBar()
                            return true
                        }
                    } else {
                        val minDiffY = if (toolbarSensitivity == "SLOW") 100 else 25
                        val minVelocity = if (toolbarSensitivity == "SLOW") 150 else 30

                        if (diffY < -minDiffY && Math.abs(velocityY) > minVelocity) {
                            hideTopBar()
                            return true
                        } else if (diffY > minDiffY && Math.abs(velocityY) > minVelocity) {
                            showTopBar()
                            return true
                        }
                    }
                }
                return false
            }
        })
    }

    private fun loadUrl(input: String) {
        var url = input
        if (url.isNotEmpty()) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = if (url.contains(".") && !url.contains(" ")) {
                    "https://$url"
                } else {
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val engine = prefs.getString("SEARCH_ENGINE", "google")
                    if (engine == "duckduckgo") {
                        "https://duckduckgo.com/?q=$url"
                    } else if (engine == "bing") {
                        "https://www.bing.com/search?q=$url"
                    } else {
                        "https://www.google.com/search?q=$url"
                    }
                }
            }
            geckoSession.loadUri(url)
        }
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val settingsLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        // --- Search Engine Selection ---
        val searchLabel = android.widget.TextView(this).apply {
            text = "Default Search Engine"
            textSize = 14f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 0, 0, 8)
        }
        val searchSpinner = android.widget.Spinner(this).apply {
            val engines = arrayOf("Google", "DuckDuckGo", "Bing")
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, engines)
            val currentEngine = prefs.getString("SEARCH_ENGINE", "google")
            setSelection(arrayOf("google", "duckduckgo", "bing").indexOf(currentEngine).takeIf { it >= 0 } ?: 0)
        }
        
        // --- Skip Outro Selection ---
        val outroLabel = android.widget.TextView(this).apply {
            text = "Skip Outro Time"
            textSize = 14f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 32, 0, 8)
        }
        val outroSpinner = android.widget.Spinner(this).apply {
            val times = arrayOf("Off", "5 Seconds", "10 Seconds", "15 Seconds", "20 Seconds", "25 Seconds", "30 Seconds")
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, times)
            val currentTime = prefs.getInt("SKIP_OUTRO_TIME", 0)
            setSelection(arrayOf(0, 5, 10, 15, 20, 25, 30).indexOf(currentTime).takeIf { it >= 0 } ?: 0)
        }

        // --- Skip Outro Method Selection ---
        val methodLabel = android.widget.TextView(this).apply {
            text = "Skip Outro Method"
            textSize = 14f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 32, 0, 8)
        }
        val methodSpinner = android.widget.Spinner(this).apply {
            val methods = arrayOf("Method A: Android Media Key", "Method B: Web DOM Click")
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, methods)
            val currentMethod = prefs.getString("SKIP_OUTRO_METHOD", "B")
            setSelection(if (currentMethod == "A") 0 else 1)
        }
        
        // --- Background Play Selection ---
        val bgPlayLabel = android.widget.TextView(this).apply {
            text = "Background Play"
            textSize = 14f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 32, 0, 8)
        }
        val bgPlaySpinner = android.widget.Spinner(this).apply {
            val bgOptions = arrayOf("Enabled", "Disabled")
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, bgOptions)
            val currentBg = prefs.getBoolean("BACKGROUND_PLAY_ENABLED", true)
            setSelection(if (currentBg) 0 else 1)
        }

        // --- Auto-Hide Toolbar Selection ---
        val autoHideLabel = android.widget.TextView(this).apply {
            text = "Auto-Hide Toolbar on Scroll"
            textSize = 14f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 32, 0, 8)
        }
        val autoHideSpinner = android.widget.Spinner(this).apply {
            val autoHideOptions = arrayOf("Enabled (Default)", "Disabled")
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, autoHideOptions)
            val currentAutoHide = prefs.getBoolean("AUTO_HIDE_TOOLBAR", true)
            setSelection(if (currentAutoHide) 0 else 1)
        }

        // --- Navbar Position Selection ---
        val posLabel = android.widget.TextView(this).apply {
            text = "Navbar Position"
            textSize = 14f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 32, 0, 8)
        }
        val posSpinner = android.widget.Spinner(this).apply {
            val posOptions = arrayOf("Bottom of Screen (Default)", "Top of Screen")
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, posOptions)
            val currentPos = prefs.getString("TOOLBAR_POSITION", "BOTTOM")
            setSelection(if (currentPos == "BOTTOM") 0 else 1)
        }

        // --- Navbar Sensitivity Selection ---
        val sensitivityLabel = android.widget.TextView(this).apply {
            text = "Navbar Auto-Hide Sensitivity"
            textSize = 14f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 32, 0, 8)
        }
        val sensitivitySpinner = android.widget.Spinner(this).apply {
            val sensitivityOptions = arrayOf("Fast / Cepat Hilang (Default)", "Slow / Lambat Hilang")
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, sensitivityOptions)
            val currentSens = prefs.getString("TOOLBAR_SENSITIVITY", "FAST")
            setSelection(if (currentSens == "FAST") 0 else 1)
        }

        // --- Performance Mode / Max Refresh Rate Selection ---
        val performanceLabel = android.widget.TextView(this).apply {
            text = "Maximum Performance (Highest Refresh Rate)"
            textSize = 14f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 32, 0, 8)
        }
        val performanceSpinner = android.widget.Spinner(this).apply {
            val performanceOptions = arrayOf("Enabled (Default)", "Disabled")
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, performanceOptions)
            val currentPerformance = prefs.getBoolean("PERFORMANCE_MODE_ENABLED", true)
            setSelection(if (currentPerformance) 0 else 1)
        }

        // --- Instant Tap Selection ---
        val instantTapLabel = android.widget.TextView(this).apply {
            text = "Instant Tap (Disable Double-Tap Zoom)"
            textSize = 14f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 32, 0, 8)
        }
        val instantTapSpinner = android.widget.Spinner(this).apply {
            val instantTapOptions = arrayOf("Enabled (Default)", "Disabled")
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, instantTapOptions)
            val currentInstantTap = prefs.getBoolean("INSTANT_TAP_ENABLED", true)
            setSelection(if (currentInstantTap) 0 else 1)
        }

        // --- FPS Meter Selection ---
        val fpsLabel = android.widget.TextView(this).apply {
            text = "Show FPS & Frame Stability"
            textSize = 14f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 32, 0, 8)
        }
        val fpsSpinner = android.widget.Spinner(this).apply {
            val fpsOptions = arrayOf("Enabled (Default)", "Disabled")
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, fpsOptions)
            val currentFps = prefs.getBoolean("FPS_METER_ENABLED", true)
            setSelection(if (currentFps) 0 else 1)
        }

        // --- VSYNC Status Selection ---
        settingsLayout.addView(searchLabel)
        settingsLayout.addView(searchSpinner)
        settingsLayout.addView(outroLabel)
        settingsLayout.addView(outroSpinner)
        settingsLayout.addView(methodLabel)
        settingsLayout.addView(methodSpinner)
        settingsLayout.addView(bgPlayLabel)
        settingsLayout.addView(bgPlaySpinner)
        settingsLayout.addView(autoHideLabel)
        settingsLayout.addView(autoHideSpinner)
        settingsLayout.addView(sensitivityLabel)
        settingsLayout.addView(sensitivitySpinner)
        settingsLayout.addView(posLabel)
        settingsLayout.addView(posSpinner)
        settingsLayout.addView(performanceLabel)
        settingsLayout.addView(performanceSpinner)
        settingsLayout.addView(instantTapLabel)
        settingsLayout.addView(instantTapSpinner)
        settingsLayout.addView(fpsLabel)
        settingsLayout.addView(fpsSpinner)

        // Keep every option reachable on short screens now that the list is longer.
        val settingsScroll = android.widget.ScrollView(this).apply {
            addView(settingsLayout)
        }

        AlertDialog.Builder(this)
            .setTitle("Browser Settings")
            .setView(settingsScroll)
            .setPositiveButton("Save") { dialog, _ ->
                val engineValues = arrayOf("google", "duckduckgo", "bing")
                val timeValues = arrayOf(0, 5, 10, 15, 20, 25, 30)
                val methodValues = arrayOf("A", "B")
                val posValues = arrayOf("BOTTOM", "TOP")
                val sensitivityValues = arrayOf("FAST", "SLOW")
                val bgEnabled = bgPlaySpinner.selectedItemPosition == 0
                val autoHideEnabled = autoHideSpinner.selectedItemPosition == 0
                val selectedPos = posValues[posSpinner.selectedItemPosition]
                val selectedSens = sensitivityValues[sensitivitySpinner.selectedItemPosition]
                val fpsEnabled = fpsSpinner.selectedItemPosition == 0
                val performanceEnabled = performanceSpinner.selectedItemPosition == 0
                val instantTap = instantTapSpinner.selectedItemPosition == 0

                prefs.edit()
                    .putString("SEARCH_ENGINE", engineValues[searchSpinner.selectedItemPosition])
                    .putInt("SKIP_OUTRO_TIME", timeValues[outroSpinner.selectedItemPosition])
                    .putString("SKIP_OUTRO_METHOD", methodValues[methodSpinner.selectedItemPosition])
                    .putBoolean("BACKGROUND_PLAY_ENABLED", bgEnabled)
                    .putBoolean("AUTO_HIDE_TOOLBAR", autoHideEnabled)
                    .putString("TOOLBAR_POSITION", selectedPos)
                    .putString("TOOLBAR_SENSITIVITY", selectedSens)
                    .putBoolean("PERFORMANCE_MODE_ENABLED", performanceEnabled)
                    .putBoolean("INSTANT_TAP_ENABLED", instantTap)
                    .putBoolean("FPS_METER_ENABLED", fpsEnabled)
                    .apply()
                    
                bgPlayEnabled = bgEnabled
                autoHideToolbar = autoHideEnabled
                toolbarSensitivity = selectedSens
                toolbarPosition = selectedPos ?: "BOTTOM"
                isFpsMeterEnabled = fpsEnabled
                performanceModeEnabled = performanceEnabled
                instantTapEnabled = instantTap
                geckoRuntime.settings.setDoubleTapZoomingEnabled(!instantTap)
                applyDisplayPerformance()
                updateFpsMeterVisibility()

                geckoSession.settings.setSuspendMediaWhenInactive(!bgEnabled)
                geckoSession.setActive(bgEnabled)
                
                applyToolbarPosition()
                if (!autoHideEnabled) {
                    showTopBar()
                }
                    
                sendSkipOutroConfigToExtension()

                dialog.dismiss()
                Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendSkipOutroConfigToExtension() {
        BrowserBridge.sendConfig(applicationContext)
    }

    private fun showPlaylistDialog() = showTrackCollection(showHistory = false)

    private fun showHistoryDialog() = showTrackCollection(showHistory = true)

    private fun showTrackCollection(showHistory: Boolean) {
        val tracks = MusicLibrary.getTracks(this, showHistory)
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        val scroll = android.widget.ScrollView(this)
        scroll.addView(content)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (showHistory) "Recently Played" else "Unified Playlist")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .create()

        if (!showHistory) {
            content.addView(android.widget.Button(this).apply {
                text = "＋ Save currently playing track"
                isAllCaps = false
                setOnClickListener {
                    val track = BrowserBridge.currentTrack
                    if (track == null || track.title.isBlank() || track.url.isBlank()) {
                        Toast.makeText(this@MainActivity, "No track information is available yet", Toast.LENGTH_SHORT).show()
                    } else {
                        MusicLibrary.addToPlaylist(this@MainActivity, track)
                        Toast.makeText(this@MainActivity, "Saved to unified playlist", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        showPlaylistDialog()
                    }
                }
            })
        }

        if (tracks.isEmpty()) {
            content.addView(android.widget.TextView(this).apply {
                text = if (showHistory) "No listening history yet." else "Your playlist is empty."
                textSize = 14f
                setPadding(0, 24, 0, 24)
            })
        }

        tracks.forEachIndexed { index, track ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 12, 0, 12)
            }
            val labels = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    if (track.url.isNotBlank()) {
                        geckoSession.loadUri(track.url)
                        dialog.dismiss()
                    }
                }
            }
            labels.addView(android.widget.TextView(this).apply {
                text = track.title
                textSize = 15f
                maxLines = 2
            })
            labels.addView(android.widget.TextView(this).apply {
                text = listOf(track.artist, track.provider).filter { it.isNotBlank() }.joinToString(" · ")
                textSize = 12f
                maxLines = 1
            })
            val remove = android.widget.ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                contentDescription = "Remove track"
                setBackgroundResource(0)
                setOnClickListener {
                    MusicLibrary.removeTrack(this@MainActivity, showHistory, index)
                    dialog.dismiss()
                    showTrackCollection(showHistory)
                }
            }
            row.addView(labels)
            row.addView(remove)
            content.addView(row)
        }
        dialog.show()
    }

    private fun showMyExtensionsDialog() {
        geckoRuntime.webExtensionController.list().accept({ extensions ->
            runOnUiThread {
                if (extensions == null || extensions.isEmpty()) {
                    Toast.makeText(this, "No extensions installed", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                val layout = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(32, 32, 32, 32)
                }

                val scrollView = android.widget.ScrollView(this)
                scrollView.addView(layout)

                val dialog = AlertDialog.Builder(this)
                    .setTitle("My Extensions")
                    .setView(scrollView)
                    .setPositiveButton("Close", null)
                    .show()

                for (ext in extensions) {
                    val itemLayout = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        setPadding(0, 16, 0, 16)
                    }

                    val extName = ext.metaData.name ?: ext.id
                    val nameView = android.widget.TextView(this).apply {
                        text = extName
                        textSize = 16f
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        setOnClickListener {
                            val baseUrl = ext.metaData.baseUrl
                            if (!baseUrl.isNullOrBlank()) {
                                val optionsUrl = ext.metaData.optionsPageUrl
                                if (!optionsUrl.isNullOrBlank()) {
                                    val fullUrl = if (optionsUrl.startsWith("moz-extension://")) {
                                        optionsUrl
                                    } else {
                                        // Ensure baseUrl ends with '/' and optionsUrl doesn't start with '/'
                                        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
                                        val path = if (optionsUrl.startsWith("/")) optionsUrl.substring(1) else optionsUrl
                                        base + path
                                    }
                                    
                                    android.util.Log.d("ExtOptions", "Base: $baseUrl | Options: $optionsUrl | Full: $fullUrl")
                                    
                                    geckoSession.loadUri(fullUrl)
                                    
                                    // Try to update UI asynchronously
                                    runOnUiThread {
                                        findViewById<EditText>(R.id.urlEditText)?.setText(fullUrl)
                                    }
                                    
                                    dialog.dismiss()
                                } else {
                                    Toast.makeText(this@MainActivity, "No options page for $extName", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(this@MainActivity, "No options page for $extName", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    val deleteBtn = android.widget.ImageButton(this).apply {
                        setImageResource(android.R.drawable.ic_menu_delete)
                        setBackgroundResource(0)
                        setOnClickListener {
                            geckoRuntime.webExtensionController.uninstall(ext).accept({
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "Uninstalled $extName", Toast.LENGTH_SHORT).show()
                                    dialog.dismiss()
                                    showMyExtensionsDialog()
                                }
                            }, {})
                        }
                    }

                    itemLayout.addView(nameView)
                    itemLayout.addView(deleteBtn)
                    layout.addView(itemLayout)
                }
            }
        }, {
            runOnUiThread {
                Toast.makeText(this, "Failed to load extensions", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun WebExtension.displayName(): String = metaData.name ?: id

    private fun showExtensionPermissionPrompt(
        title: String,
        extension: WebExtension,
        permissions: Array<out String>,
        origins: Array<out String>,
        intro: String = "This add-on wants permission to:",
        confirmLabel: String = "Install"
    ): GeckoResult<AllowOrDeny> {
        val result = GeckoResult<AllowOrDeny>()
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)

        fun answer(allow: Boolean) {
            if (answered.compareAndSet(false, true)) {
                result.complete(if (allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }
        }

        runOnUiThread {
            if (isFinishing || isDestroyed) {
                answer(false)
                return@runOnUiThread
            }

            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(buildExtensionPromptMessage(extension, permissions, origins, intro))
                .setPositiveButton(confirmLabel) { _, _ -> answer(true) }
                .setNegativeButton("Cancel") { _, _ -> answer(false) }
                .setOnCancelListener { answer(false) }
                .setOnDismissListener { answer(false) }
                .show()
        }

        return result
    }

    private fun buildExtensionPromptMessage(
        extension: WebExtension,
        permissions: Array<out String>,
        origins: Array<out String>,
        intro: String
    ): String {
        val meta = extension.metaData
        return buildString {
            if (!meta.version.isNullOrBlank()) appendLine("Version ${meta.version}")
            if (!meta.description.isNullOrBlank()) {
                appendLine()
                appendLine(meta.description)
            }
            appendLine()
            appendLine(intro)

            val shownPermissions = permissions
                .filterNot { it.startsWith("internal:") }
                .distinct()
            val shownOrigins = origins
                .filterNot { it.startsWith("internal:") }
                .distinct()

            if (shownPermissions.isEmpty() && shownOrigins.isEmpty()) {
                appendLine("• No special permissions")
            } else {
                shownPermissions.forEach { appendLine("• ${friendlyPermission(it)}") }
                shownOrigins.forEach { appendLine("• Access data on $it") }
            }

            appendLine()
            append("Only continue if you trust this add-on.")
        }.trim()
    }

    private fun friendlyPermission(permission: String): String = when (permission) {
        "storage" -> "Store add-on data"
        "unlimitedStorage" -> "Store unlimited add-on data"
        "tabs" -> "Read your open tabs"
        "activeTab" -> "Access the current tab when you click the add-on"
        "webRequest" -> "Observe network requests"
        "webRequestBlocking" -> "Block network requests"
        "bookmarks" -> "Read and modify bookmarks"
        "history" -> "Read and modify browsing history"
        "downloads" -> "Manage downloads"
        "cookies" -> "Read and modify cookies"
        "notifications" -> "Show notifications"
        "clipboardWrite" -> "Write to the clipboard"
        "clipboardRead" -> "Read from the clipboard"
        "contextMenus", "menus" -> "Add items to the browser menu"
        "alarms" -> "Schedule background tasks"
        "geckoViewAddons" -> "Use browser add-on APIs"
        "nativeMessaging", "nativeMessagingFromContent" -> "Communicate with native apps"
        "<all_urls>" -> "Access all websites"
        else -> permission
    }

    private fun isXpiResponse(response: WebResponse): Boolean {
        val cleanUri = response.uri.substringBefore('?').substringBefore('#')
        if (cleanUri.endsWith(".xpi", ignoreCase = true)) return true

        val contentType = response.headers.entries.firstOrNull {
            it.key.equals("content-type", ignoreCase = true)
        }?.value
        return contentType?.contains("application/x-xpinstall", ignoreCase = true) == true
    }

    private fun newXpiFile(): File {
        val dir = File(filesDir, "addons")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "addon_${System.currentTimeMillis()}.xpi")
    }

    private fun installXpiFromUri(uri: Uri) {
        val destFile = newXpiFile()
        try {
            val input = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("The selected file could not be opened")
            input.use { source ->
                FileOutputStream(destFile).use { target -> source.copyTo(target) }
            }
            installXpiFile(destFile)
        } catch (e: Exception) {
            destFile.delete()
            Toast.makeText(this, "Failed to read XPI: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun installXpiFile(file: File) {
        if (!file.exists() || file.length() == 0L) {
            file.delete()
            Toast.makeText(this, "The XPI file is empty or unreadable", Toast.LENGTH_LONG).show()
            return
        }

        if (!isValidXpi(file)) {
            file.delete()
            Toast.makeText(this, "That file is not a valid .xpi add-on", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, "Installing add-on...", Toast.LENGTH_SHORT).show()
        geckoRuntime.webExtensionController
            .install(
                "file://${file.absolutePath}",
                WebExtensionController.INSTALLATION_METHOD_FROM_FILE
            )
            .accept({ ext ->
                runOnUiThread {
                    if (ext != null) {
                        geckoRuntime.webExtensionController.enable(ext, WebExtensionController.EnableSource.USER)
                        Toast.makeText(this, "Add-on installed: ${ext.displayName()}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Installation failed", Toast.LENGTH_LONG).show()
                    }
                }
            }, { e ->
                runOnUiThread {
                    installErrorMessage(e)?.let {
                        Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                    }
                }
            })
    }

    private fun installXpiFromUrl(url: String) {
        Toast.makeText(this, "Installing add-on...", Toast.LENGTH_SHORT).show()
        geckoRuntime.webExtensionController
            .install(url)
            .accept({ ext ->
                runOnUiThread {
                    if (ext != null) {
                        geckoRuntime.webExtensionController.enable(ext, WebExtensionController.EnableSource.USER)
                        Toast.makeText(this, "Add-on installed: ${ext.displayName()}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Installation failed", Toast.LENGTH_LONG).show()
                    }
                }
            }, { e ->
                runOnUiThread {
                    installErrorMessage(e)?.let {
                        Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                    }
                }
            })
    }

    private fun isValidXpi(file: File): Boolean {
        return try {
            java.util.zip.ZipFile(file).use { zip ->
                zip.getEntry("manifest.json") != null
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun installErrorMessage(error: Throwable?): String? {
        val code = (error as? WebExtension.InstallException)?.code
        return when (code) {
            WebExtension.InstallException.ErrorCodes.ERROR_SIGNEDSTATE_REQUIRED ->
                "Add-on is not signed by Mozilla. Only signed add-ons from addons.mozilla.org can be installed."
            WebExtension.InstallException.ErrorCodes.ERROR_CORRUPT_FILE ->
                "The XPI file is corrupt or not a valid add-on."
            WebExtension.InstallException.ErrorCodes.ERROR_FILE_ACCESS ->
                "The XPI file could not be read."
            WebExtension.InstallException.ErrorCodes.ERROR_INCOMPATIBLE ->
                "This add-on is not compatible with this browser."
            WebExtension.InstallException.ErrorCodes.ERROR_BLOCKLISTED ->
                "This add-on has been blocked for security reasons."
            WebExtension.InstallException.ErrorCodes.ERROR_USER_CANCELED,
            WebExtension.InstallException.ErrorCodes.ERROR_POSTPONED -> null
            else -> error?.message?.let { "Installation failed: $it" } ?: "Installation failed"
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyDisplayPerformance()
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        if (bgPlayEnabled) {
            geckoSession.setActive(true)
        }
        super.onPause()
    }

    override fun onStop() {
        if (bgPlayEnabled) {
            geckoSession.setActive(true)
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        applyDisplayPerformance()
        if (isFpsMeterEnabled) {
            resetFpsWindow()
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
        geckoSession.setActive(true)
    }

    override fun onDestroy() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        // BrowserSessionStore owns the session so playback can continue in the background.
        super.onDestroy()
    }
}
