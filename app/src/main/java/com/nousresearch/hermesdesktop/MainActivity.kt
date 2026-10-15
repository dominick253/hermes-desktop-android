package com.nousresearch.hermesdesktop

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * Thin, fast WebView shell around the Hermes dashboard desktop renderer
 * (http://<host>:9119/desktop/). No third-party dependencies: everything is
 * framework, which keeps the APK small and the cold start short.
 */
class MainActivity : Activity() {

    companion object {
        const val PREFS = "hermes"
        const val KEY_URL = "server_url"
        const val KEY_LAYOUT = "layout_mode"
        const val KEY_DESKTOP_WIDTH = "desktop_width"
        const val KEY_TEXT_ZOOM = "text_zoom"
        const val KEY_KEEP_ALIVE = "keep_alive"
        const val KEY_IMMERSIVE = "immersive"
        val DEFAULT_URL: String = BuildConfig.DEFAULT_SERVER_URL
        const val LAYOUT_FIT = 0
        const val LAYOUT_DESKTOP = 1
        const val REQ_UPLOAD = 4100
        const val REQ_NOTIF = 4101
        const val REQ_MIC = 4102
        val TEXT_STEPS = intArrayOf(85, 100, 115, 130, 150)
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorDetail: TextView

    private var pendingUpload: ValueCallback<Array<Uri>>? = null
    private var pendingMicRequest: PermissionRequest? = null
    private var pendingKeepAlive = false
    private var lastBackAt = 0L
    private var loadFailed = false

    // ------------------------------------------------------------------ setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        webView = findViewById(R.id.web)
        progress = findViewById(R.id.progress)
        errorPanel = findViewById(R.id.error_panel)
        errorDetail = findViewById(R.id.error_detail)

        applyEdgeToEdge()
        applyImmersive(prefs.getBoolean(KEY_IMMERSIVE, false))

        configureWebView()

        findViewById<ImageButton>(R.id.menu_button).setOnClickListener { showMenu() }
        findViewById<View>(R.id.retry_button).setOnClickListener { hideError(); loadUrl(currentUrl()) }
        findViewById<View>(R.id.server_button).setOnClickListener { showServerDialog() }

        if (prefs.getBoolean(KEY_KEEP_ALIVE, false) && hasNotificationPermission()) {
            startKeepAlive()
        }

        loadUrl(currentUrl())
    }

    private fun configureWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // Bound priority: Chromium keeps the renderer process alive when the app
        // is backgrounded, so an in-flight streamed turn is not thrown away.
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
        webView.setBackgroundColor(Color.parseColor("#0B0B0C"))
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // Assets ship with `cache-control: public, max-age=31536000, immutable`,
            // so LOAD_DEFAULT means the ~9.5 MB bundle is fetched once and then served
            // from the on-device cache on every later launch.
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            textZoom = prefs.getInt(KEY_TEXT_ZOOM, 100)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // The renderer already ships its own dark theme; do not let the
                // platform force-invert it.
                isAlgorithmicDarkeningAllowed = false
            }
        }

        webView.webViewClient = HermesWebViewClient()
        webView.webChromeClient = HermesChromeClient()

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    // ------------------------------------------------------------------ state

    private fun currentUrl(): String = prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL

    private fun layoutMode(): Int = prefs.getInt(KEY_LAYOUT, LAYOUT_FIT)

    private fun desktopWidth(): Int = prefs.getInt(KEY_DESKTOP_WIDTH, 1400)

    private fun textZoom(): Int = prefs.getInt(KEY_TEXT_ZOOM, 100)

    private fun serverHost(): String = Uri.parse(currentUrl()).host ?: currentUrl()

    private fun isInternal(url: String): Boolean {
        val a = Uri.parse(url)
        val b = Uri.parse(currentUrl())
        return a.host != null && a.host == b.host &&
            (a.port == b.port || (a.port == -1 && b.port == -1))
    }

    private fun loadUrl(url: String) {
        hideError()
        webView.loadUrl(url)
    }

    // ------------------------------------------------------------- web clients

    private inner class HermesWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (isInternal(url)) return false
            openExternal(request.url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (!loadFailed) progress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String) {
            progress.visibility = View.GONE
            applyViewportMode()
            title = "Hermes"
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    "${error.errorCode}: ${error.description}"
                } else {
                    "error ${error.errorCode}"
                }
                showError("Cannot reach ${serverHost()}\n$reason")
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && response.statusCode >= 500) {
                showError("The dashboard returned HTTP ${response.statusCode}.")
            }
        }

        /** Chromium killed the renderer (OOM / eviction). Recover instead of dying. */
        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            rebuildWebView()
            return true
        }
    }

    private inner class HermesChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
            progress.visibility = if (newProgress in 1..97 && !loadFailed) View.VISIBLE else View.GONE
        }

        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            pendingUpload?.onReceiveValue(null)
            pendingUpload = callback
            return try {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                startActivityForResult(Intent.createChooser(intent, "Attach"), REQ_UPLOAD)
                true
            } catch (e: Exception) {
                pendingUpload = null
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val wantsAudio = request.resources.any { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
            val wantsVideo = request.resources.any { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE }
            if (!wantsAudio && !wantsVideo) {
                request.deny()
                return
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                request.grant(request.resources)
            } else {
                pendingMicRequest = request
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            }
        }
    }

    // ------------------------------------------------------- viewport strategy

    /**
     * The renderer declares `width=device-width`, which on a phone squeezes a
     * three-pane desktop layout into ~411 CSS px. In desktop mode we rewrite the
     * meta viewport so the page lays out at a real desktop width and the user
     * pans/zooms, exactly like "desktop site" in Chrome.
     */
    private fun applyViewportMode() {
        if (layoutMode() != LAYOUT_DESKTOP) return
        val w = desktopWidth()
        webView.evaluateJavascript(
            "(function(){var m=document.querySelector('meta[name=viewport]');" +
                "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
                "document.head.appendChild(m);}" +
                "m.setAttribute('content','width=$w, user-scalable=yes');})();",
            null
        )
    }

    // ------------------------------------------------------------- error state

    private fun showError(detail: String) {
        loadFailed = true
        errorDetail.text = detail
        errorPanel.visibility = View.VISIBLE
        progress.visibility = View.GONE
    }

    private fun hideError() {
        loadFailed = false
        errorPanel.visibility = View.GONE
    }

    private fun rebuildWebView() {
        root.removeView(webView)
        try {
            webView.destroy()
        } catch (ignored: Exception) {
        }
        val fresh = WebView(this)
        root.addView(fresh, 0, FrameLayout.LayoutParams(-1, -1))
        webView = fresh
        configureWebView()
        loadUrl(currentUrl())
        toast("View rebuilt after the renderer was killed")
    }

    // -------------------------------------------------------------- app menu

    private fun showMenu() {
        val items = arrayOf(
            "Reload",
            "Layout: " + if (layoutMode() == LAYOUT_DESKTOP) "desktop width (${desktopWidth()} px)" else "fit screen",
            "Text size: ${textZoom()}%",
            "Wider / narrower desktop view",
            "Keep streaming in background: " + if (KeepAliveService.running) "ON" else "OFF",
            "Full screen (hide status bar): " + if (prefs.getBoolean(KEY_IMMERSIVE, false)) "ON" else "OFF",
            "Server: ${serverHost()}",
            "Sign out",
            "About"
        )
        AlertDialog.Builder(this)
            .setTitle("Hermes")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { hideError(); loadUrl(currentUrl()) }
                    1 -> toggleLayout()
                    2 -> cycleTextZoom()
                    3 -> adjustDesktopWidth()
                    4 -> toggleKeepAlive()
                    5 -> toggleImmersive()
                    6 -> showServerDialog()
                    7 -> signOut()
                    8 -> showAbout()
                }
            }
            .show()
    }

    private fun toggleLayout() {
        val next = if (layoutMode() == LAYOUT_DESKTOP) LAYOUT_FIT else LAYOUT_DESKTOP
        prefs.edit().putInt(KEY_LAYOUT, next).apply()
        toast(if (next == LAYOUT_DESKTOP) "Desktop width ${desktopWidth()} px" else "Fitted to screen")
        loadUrl(currentUrl())
    }

    private fun cycleTextZoom() {
        val cur = textZoom()
        val next = TEXT_STEPS.firstOrNull { it > cur } ?: TEXT_STEPS.first()
        prefs.edit().putInt(KEY_TEXT_ZOOM, next).apply()
        webView.settings.textZoom = next
        toast("Text size $next%")
    }

    private fun adjustDesktopWidth() {
        val widths = intArrayOf(1024, 1280, 1440, 1600, 1920)
        val cur = desktopWidth()
        val next = widths.firstOrNull { it > cur } ?: widths.first()
        prefs.edit().putInt(KEY_DESKTOP_WIDTH, next).apply()
        toast(if (layoutMode() == LAYOUT_DESKTOP) "Desktop width $next px" else "Set desktop width $next px (switch Layout to use it)")
        if (layoutMode() == LAYOUT_DESKTOP) {
            applyViewportMode()
        }
    }

    private fun showServerDialog() {
        val field = EditText(this).apply {
            setText(currentUrl())
            setSingleLine()
            setHorizontallyScrolling(true)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        AlertDialog.Builder(this)
            .setTitle("Dashboard address")
            .setMessage("Use the full address of the desktop renderer, e.g. http://192.168.1.100:9119/desktop/")
            .setView(wrap(field, pad))
            .setPositiveButton("Save and load") { _, _ ->
                var url = field.text.toString().trim()
                if (url.isEmpty()) {
                    url = DEFAULT_URL
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "http://$url"
                }
                prefs.edit().putString(KEY_URL, url).apply()
                toast("Using $url")
                loadUrl(url)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun wrap(view: View, pad: Int): android.widget.FrameLayout {
        val container = FrameLayout(this)
        val lp = FrameLayout.LayoutParams(-1, -2)
        lp.setMargins(pad, pad, pad, 0)
        container.addView(view, lp)
        return container
    }

    private fun signOut() {
        AlertDialog.Builder(this)
            .setTitle("Sign out")
            .setMessage("Clears the dashboard session cookie and this app's stored page state.")
            .setPositiveButton("Sign out") { _, _ ->
                val cm = CookieManager.getInstance()
                cm.removeAllCookies(null)
                cm.flush()
                webView.evaluateJavascript("try{localStorage.clear();sessionStorage.clear();}catch(e){}", null)
                webView.clearCache(true)
                webView.clearFormData()
                val base = Uri.parse(currentUrl())
                val origin = "${base.scheme}://${base.authority}"
                loadUrl("$origin/login?next=${android.net.Uri.encode(base.path ?: "/desktop/")}")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAbout() {
        val ua = webView.settings.userAgentString
        AlertDialog.Builder(this)
            .setTitle("Hermes ${BuildConfig.VERSION_NAME}")
            .setMessage(
                "Server: ${currentUrl()}\n" +
                    "WebView: ${webViewVersion()}\n" +
                    "Layout: " + if (layoutMode() == LAYOUT_DESKTOP) "desktop ${desktopWidth()} px" else "fit screen" +
                    "\nText size: ${textZoom()}%\n" +
                    "Background keep-alive: " + if (KeepAliveService.running) "on" else "off" +
                    "\n\nUA: ${ua.takeLast(48)}"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun webViewVersion(): String = try {
        val info = packageManager.getPackageInfo("com.google.android.webview", 0)
        "Chrome ${info.versionName}"
    } catch (e: Exception) {
        "system WebView"
    }

    // ------------------------------------------------------- full screen mode

    /**
     * Edge-to-edge: no ActionBar, transparent system bars, and the WebView padded
     * by the status/nav/cutout insets so nothing important hides behind them.
     */
    private fun applyEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        root.setOnApplyWindowInsetsListener { v, insets ->
            val immersive = prefs.getBoolean(KEY_IMMERSIVE, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout())
                // With decorFitsSystemWindows(false) the IME no longer resizes the
                // window on its own. Feed the keyboard inset into the bottom padding,
                // otherwise the chat composer ends up hidden behind the keyboard.
                val imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
                if (immersive) {
                    v.setPadding(0, 0, 0, imeBottom)
                } else {
                    v.setPadding(i.left, i.top, i.right, maxOf(i.bottom, imeBottom))
                }
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            insets
        }
    }

    private fun applyImmersive(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            if (on) {
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on) {
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
        }
        if (on) {
            root.setPadding(0, 0, 0, 0)
        }
    }

    private fun toggleImmersive() {
        val next = !prefs.getBoolean(KEY_IMMERSIVE, false)
        prefs.edit().putBoolean(KEY_IMMERSIVE, next).apply()
        applyImmersive(next)
        toast(if (next) "Full screen: swipe an edge for the system bars" else "System bars shown")
    }

    // ------------------------------------------------------------- keep alive

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun toggleKeepAlive() {
        if (KeepAliveService.running) {
            stopService(Intent(this, KeepAliveService::class.java))
            prefs.edit().putBoolean(KEY_KEEP_ALIVE, false).apply()
            toast("Background keep-alive off")
            return
        }
        if (!hasNotificationPermission()) {
            pendingKeepAlive = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        startKeepAlive()
    }

    private fun startKeepAlive() {
        val intent = Intent(this, KeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        prefs.edit().putBoolean(KEY_KEEP_ALIVE, true).apply()
        toast("Streaming continues with the screen off")
    }

    // ---------------------------------------------------------- system plumbing

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            toast("No app can open that link")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (errorPanel.visibility == View.VISIBLE || !webView.canGoBack()) {
            val now = System.currentTimeMillis()
            if (now - lastBackAt < 2000) {
                finish()
            } else {
                lastBackAt = now
                toast("Press back again to close Hermes")
            }
        } else {
            webView.goBack()
        }
    }

    override fun onPause() {
        super.onPause()
        // Only throttle the page when the user has NOT asked for background
        // streaming; otherwise a live turn would stall.
        if (!KeepAliveService.running) {
            webView.onPause()
        }
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        if (isFinishing) {
            try {
                webView.destroy()
            } catch (ignored: Exception) {
            }
        }
        super.onDestroy()
    }

    @Deprecated("Framework activity-result API; no AndroidX dependency in this shell")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_UPLOAD) return
        val callback = pendingUpload
        pendingUpload = null
        if (callback == null) return
        val uris = ArrayList<Uri>()
        if (resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            } else {
                data.data?.let { uris.add(it) }
            }
        }
        callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_MIC -> {
                val request = pendingMicRequest
                pendingMicRequest = null
                if (request != null) {
                    if (granted) request.grant(request.resources) else request.deny()
                }
            }
            REQ_NOTIF -> {
                if (granted && pendingKeepAlive) {
                    startKeepAlive()
                } else {
                    pendingKeepAlive = false
                    if (!granted) toast("Notifications are blocked, so keep-alive stays off")
                }
            }
        }
    }
}
