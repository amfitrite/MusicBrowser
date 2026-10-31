package com.example.musicbrowser

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Toast
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
    private val PREF_PLAYLIST = "playlist_items"
    
    // Variables to track the current page
    private var currentTitle: String = "Unknown Title"
    private var currentUrl: String = ""

    // Cached SharedPreferences values for performance
    private var bgPlayEnabled: Boolean = true
    private var autoHideToolbar: Boolean = true
    private var toolbarSensitivity: String = "FAST"
    private var toolbarPosition: String = "BOTTOM"

    // ActivityResultLauncher for picking .xpi files
    private val xpiFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { installXpiFromUri(it) }
    }

    private val mediaCtrlReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val cmd = intent?.getStringExtra("cmd")
            if (cmd == "play_pause" || cmd == "next" || cmd == "prev") {
                val config = org.json.JSONObject()
                config.put("type", "MEDIA_CMD")
                config.put("cmd", cmd)
                
                try {
                    skipOutroPort?.postMessage(config)
                } catch (e: Exception) {
                    android.util.Log.e("MediaCtrl", "Cannot send message to extension", e)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        geckoView = findViewById<org.mozilla.geckoview.GeckoView>(R.id.geckoView)
        val urlEditText = findViewById<EditText>(R.id.urlEditText)
        menuContainer = findViewById<MaterialCardView>(R.id.menuContainer)
        val settingsButton = findViewById<ImageButton>(R.id.settingsButton)

        // Initialize GeckoView Runtime once with ALLOW_ALL for insecure/mixed content media
        val runtimeSettings = org.mozilla.geckoview.GeckoRuntimeSettings.Builder()
            .allowInsecureConnections(org.mozilla.geckoview.GeckoRuntimeSettings.ALLOW_ALL)
            .build()
        geckoRuntime = org.mozilla.geckoview.GeckoRuntime.create(applicationContext, runtimeSettings)
        
        val initPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        bgPlayEnabled = initPrefs.getBoolean("BACKGROUND_PLAY_ENABLED", true)
        autoHideToolbar = initPrefs.getBoolean("AUTO_HIDE_TOOLBAR", true)
        toolbarSensitivity = initPrefs.getString("TOOLBAR_SENSITIVITY", "FAST") ?: "FAST"
        toolbarPosition = initPrefs.getString("TOOLBAR_POSITION", "BOTTOM") ?: "BOTTOM"
        val settings = org.mozilla.geckoview.GeckoSessionSettings.Builder()
            .suspendMediaWhenInactive(!bgPlayEnabled)
            .build()
        geckoSession = GeckoSession(settings)

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

                val hideThreshold = if (toolbarSensitivity == "SLOW") 30 else 2
                val showThreshold = if (toolbarSensitivity == "SLOW") -20 else -2

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
        geckoSession.open(geckoRuntime)
        geckoView.setSession(geckoSession)

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        // Start Foreground Service to keep app alive when screen is off
        val serviceIntent = android.content.Intent(this, BrowserService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
        
        // Register receiver for Media Control from Notification
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            mediaCtrlReceiver,
            android.content.IntentFilter("com.example.musicbrowser.MEDIA_CTRL"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Setup Built-in WebExtensions
        installBackgroundPlayExtension()
        installSkipOutroExtension()

        // Load YouTube by default
        geckoSession.loadUri("https://m.youtube.com")

        // Setup Gesture, Menu Mode, and Navbar Position
        setupGestureDetector()
        updateMenuTriggerMode()
        applyToolbarPosition()

        // UI Logic: Collapse menu when touching the GeckoView
        geckoView.setOnTouchListener { _, _ ->
            if (menuContainer.visibility == View.VISIBLE) {
                closeMenu()
            }
            false
        }

        urlEditText.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                loadUrl(urlEditText.text.toString())
                closeMenu()
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

    private fun closeMenu() {
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

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev != null && ::gestureDetector.isInitialized) {
            gestureDetector.onTouchEvent(ev)
        }
        return super.dispatchTouchEvent(ev)
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
            val times = arrayOf("Off", "5 Seconds", "10 Seconds", "15 Seconds")
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, times)
            val currentTime = prefs.getInt("SKIP_OUTRO_TIME", 0)
            setSelection(arrayOf(0, 5, 10, 15).indexOf(currentTime).takeIf { it >= 0 } ?: 0)
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

        AlertDialog.Builder(this)
            .setTitle("Browser Settings")
            .setView(settingsLayout)
            .setPositiveButton("Save") { dialog, _ ->
                val engineValues = arrayOf("google", "duckduckgo", "bing")
                val timeValues = arrayOf(0, 5, 10, 15)
                val methodValues = arrayOf("A", "B")
                val posValues = arrayOf("BOTTOM", "TOP")
                val sensitivityValues = arrayOf("FAST", "SLOW")
                val bgEnabled = bgPlaySpinner.selectedItemPosition == 0
                val autoHideEnabled = autoHideSpinner.selectedItemPosition == 0
                val selectedPos = posValues[posSpinner.selectedItemPosition]
                val selectedSens = sensitivityValues[sensitivitySpinner.selectedItemPosition]

                prefs.edit()
                    .putString("SEARCH_ENGINE", engineValues[searchSpinner.selectedItemPosition])
                    .putInt("SKIP_OUTRO_TIME", timeValues[outroSpinner.selectedItemPosition])
                    .putString("SKIP_OUTRO_METHOD", methodValues[methodSpinner.selectedItemPosition])
                    .putBoolean("BACKGROUND_PLAY_ENABLED", bgEnabled)
                    .putBoolean("AUTO_HIDE_TOOLBAR", autoHideEnabled)
                    .putString("TOOLBAR_POSITION", selectedPos)
                    .putString("TOOLBAR_SENSITIVITY", selectedSens)
                    .apply()
                    
                bgPlayEnabled = bgEnabled
                autoHideToolbar = autoHideEnabled
                toolbarSensitivity = selectedSens
                toolbarPosition = selectedPos ?: "BOTTOM"

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

    private var skipOutroPort: WebExtension.Port? = null

    private fun installBackgroundPlayExtension() {
        geckoRuntime.webExtensionController.installBuiltIn("resource://android/assets/extensions/background_play/")
    }

    private fun installSkipOutroExtension() {
        geckoRuntime.webExtensionController
            .installBuiltIn("resource://android/assets/extensions/skip_outro/")
            .accept({ ext ->
                if (ext != null) {
                    ext.setMessageDelegate(object : WebExtension.MessageDelegate {
                        override fun onConnect(port: WebExtension.Port) {
                            skipOutroPort = port
                            
                            // When connection is established, send current config
                            sendSkipOutroConfigToExtension()
                            
                            port.setDelegate(object : WebExtension.PortDelegate {
                                override fun onPortMessage(message: Any, port: WebExtension.Port) {
                                    if (message is org.json.JSONObject) {
                                        val action = message.optString("action")
                                        if (action == "trigger_media_next") {
                                            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                                            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
                                            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
                                        } else if (action == "request_config") {
                                            sendSkipOutroConfigToExtension()
                                        }
                                    }
                                }
                            })
                        }
                    }, "musicbrowser.internal")
                }
            }, { e ->
                android.util.Log.e("SkipOutroExt", "Failed to install built-in extension", e)
            })
    }

    private fun sendSkipOutroConfigToExtension() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val config = org.json.JSONObject()
        config.put("type", "SET_CONFIG")
        config.put("skipTime", prefs.getInt("SKIP_OUTRO_TIME", 0))
        config.put("skipMethod", prefs.getString("SKIP_OUTRO_METHOD", "B"))
        
        skipOutroPort?.postMessage(config)
    }

    private fun showPlaylistDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val playlistJson = prefs.getString(PREF_PLAYLIST, "[]")
        val array = org.json.JSONArray(playlistJson)
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        val scrollView = android.widget.ScrollView(this)
        scrollView.addView(layout)
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("My Playlist")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
            
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val title = item.optString("title", "Unknown")
            val url = item.optString("url", "")
            
            val itemLayout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, 16, 0, 16)
            }
            
            val titleView = android.widget.TextView(this).apply {
                text = title
                textSize = 16f
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    geckoSession.loadUri(url)
                    dialog.dismiss()
                }
            }
            
            val deleteBtn = android.widget.ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                setBackgroundResource(0)
                setOnClickListener {
                    array.remove(i)
                    prefs.edit().putString(PREF_PLAYLIST, array.toString()).apply()
                    dialog.dismiss()
                    showPlaylistDialog()
                }
            }
            
            itemLayout.addView(titleView)
            itemLayout.addView(deleteBtn)
            layout.addView(itemLayout)
        }
        
        if (array.length() == 0) {
            layout.addView(android.widget.TextView(this).apply {
                text = "Your playlist is empty."
            })
        }
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

    override fun onPause() {
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
        geckoSession.setActive(true)
    }

    override fun onDestroy() {
        unregisterReceiver(mediaCtrlReceiver)
        geckoSession.close()
        super.onDestroy()
    }
}
