package com.xware

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.graphics.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.*
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "XWare"
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val ASSET_URL  = "https://$ASSET_HOST/assets/index.html"
    }

    private lateinit var webView:     WebView
    private lateinit var bridge:      AndroidBridge
    private lateinit var assetLoader: WebViewAssetLoader

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val OVERLAY_PERMISSION_REQUEST = 1001
    private var isOverlayActive = false

    private val overlayControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val js = intent?.getStringExtra("js") ?: return
            webView.evaluateJavascript(js, null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupCrashReporter()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupFullscreen()

        val root = FrameLayout(this)
        webView  = WebView(this)
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        showPreviousCrashIfAny()

        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(ASSET_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/res/",    WebViewAssetLoader.ResourcesPathHandler(this))
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
            WebView.setWebContentsDebuggingEnabled(true)

        val filter = IntentFilter("com.xware.OVERLAY_CONTROL")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(overlayControlReceiver, filter, RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(overlayControlReceiver, filter)
        }

        setupWebView()
        startMusicService()
        requestNotificationPermission()
        webView.postDelayed({ requestBatteryOptimizationExemption() }, 3000)

        webView.loadUrl(ASSET_URL)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        // ★ 핵심: onPause 에서 아무것도 하지 않음
        // webView.onPause() 호출 시 Samsung WebView 렌더러가
        // 즉시 미디어 스트림을 중단시킴
    }

    override fun onStop() {
        super.onStop()
        // ★ 핵심: onStop 에서도 webView 건드리지 않음
        // resumeTimers() 도 불필요 — onPause 에서 pause 하지 않았으므로
    }

    override fun onDestroy() {
        try { unregisterReceiver(overlayControlReceiver) } catch (_: Exception) {}
        stopMusicService()
        scope.cancel()
        webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated")
    override fun onBackPressed() {
        webView.evaluateJavascript("window.xwareHandleBack && window.xwareHandleBack()") { r ->
            if (r != "true") moveTaskToBack(true)
        }
    }

    fun dlog(msg: String, level: String = "I") {
        when (level) { "E" -> Log.e(TAG, msg); "W" -> Log.w(TAG, msg); else -> Log.i(TAG, msg) }
    }

    // ════════════════════════════════════════════
    //  크래시 리포터
    // ════════════════════════════════════════════
    private fun setupCrashReporter() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val log = buildString {
                    append("Thread: ${thread.name}\n")
                    append("Error: ${throwable.message}\n\n")
                    append(throwable.stackTraceToString().take(3000))
                }
                getSharedPreferences("xware_crash", Context.MODE_PRIVATE)
                    .edit().putString("log", log).apply()
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun showPreviousCrashIfAny() {
        val prefs = getSharedPreferences("xware_crash", Context.MODE_PRIVATE)
        val log   = prefs.getString("log", null) ?: return
        prefs.edit().remove("log").apply()
        AlertDialog.Builder(this)
            .setTitle("크래시 로그")
            .setMessage(log)
            .setPositiveButton("확인", null)
            .show()
    }

    // ════════════════════════════════════════════
    //  WebView 설정
    // ════════════════════════════════════════════
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled               = true
            domStorageEnabled               = true
            userAgentString                 =
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.6099.144 Mobile Safari/537.36"
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort                 = true
            loadWithOverviewMode            = true
            setSupportZoom(false)
            builtInZoomControls             = false
            displayZoomControls             = false
            allowFileAccess                 = true
            allowContentAccess              = true
            mixedContentMode                = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode                       = WebSettings.LOAD_DEFAULT
            databaseEnabled                 = true
            defaultTextEncodingName         = "UTF-8"
        }

        // ★ 하드웨어 가속은 기본값(NONE) 유지
        // LAYER_TYPE_HARDWARE 는 Samsung WebView 에서 미디어 재생 충돌 유발
        webView.setLayerType(View.LAYER_TYPE_NONE, null)

        // ★ 렌더러 우선순위: 백그라운드에서도 IMPORTANT 유지
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(
                WebView.RENDERER_PRIORITY_IMPORTANT,
                false  // 백그라운드에서도 우선순위 낮추지 않음
            )
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                if (msg == null) return false
                val lv = when (msg.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR   -> "E"
                    ConsoleMessage.MessageLevel.WARNING -> "W"
                    else -> "I"
                }
                dlog("[${msg.sourceId()?.substringAfterLast('/') ?: ""}:${msg.lineNumber()}] ${msg.message()}", lv)
                return true
            }
            override fun onPermissionRequest(req: PermissionRequest?) {
                req?.grant(req.resources)
            }
            override fun onJsAlert(v: WebView?, u: String?, m: String?, r: JsResult?): Boolean {
                Toast.makeText(this@MainActivity, m, Toast.LENGTH_SHORT).show()
                r?.confirm(); return true
            }
            private var customView: View? = null
            private var customViewCb: CustomViewCallback? = null
            override fun onShowCustomView(view: View?, cb: CustomViewCallback?) {
                if (customView != null) { cb?.onCustomViewHidden(); return }
                customView = view; customViewCb = cb
                (window.decorView as? FrameLayout)
                    ?.addView(view, FrameLayout.LayoutParams(-1, -1))
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
            override fun onHideCustomView() {
                (window.decorView as? FrameLayout)
                    ?.takeIf { customView != null }?.removeView(customView)
                customView = null
                customViewCb?.onCustomViewHidden()
                customViewCb = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                val uri = request?.url ?: return null
                return assetLoader.shouldInterceptRequest(uri)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                dlog("로딩완료: $url")
                view?.evaluateJavascript("""
                    (function(){
                        window.onerror = function(m,s,l){
                            console.error('[ERR] '+m+' @'+s+':'+l); return false;
                        };
                        window.onunhandledrejection = function(e){
                            console.error('[PROMISE] '+(e.reason||e));
                        };
                    })();
                """.trimIndent(), null)
            }
            override fun onRenderProcessGone(
                view: WebView?, detail: RenderProcessGoneDetail?
            ): Boolean {
                dlog("WebView 렌더러 종료 (crashed=${detail?.didCrash()})", "W")
                return true
            }
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val url = request?.url?.toString() ?: ""
                    if (url.contains(ASSET_HOST)) dlog("Asset오류: $url", "E")
                }
            }
            override fun onReceivedSslError(
                view: WebView?, handler: SslErrorHandler?,
                err: android.net.http.SslError?
            ) {
                handler?.proceed()
            }
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                val keep = listOf(
                    ASSET_HOST, "youtube.com", "googlevideo.com",
                    "ytimg.com", "lrclib.net", "googleapis.com",
                    "fonts.google", "suggestqueries", "gstatic.com"
                )
                if (keep.any { url.contains(it) }) return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); true
                } catch (_: Exception) { false }
            }
        }

        bridge = AndroidBridge(WeakReference(this), webView, scope)
        webView.addJavascriptInterface(bridge, "AndroidBridge")
    }

    // ════════════════════════════════════════════
    //  전체화면
    // ════════════════════════════════════════════
    private fun setupFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            window.setDecorFitsSystemWindows(false)
        else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            window.statusBarColor = Color.TRANSPARENT
    }

    // ════════════════════════════════════════════
    //  오버레이 모드
    // ════════════════════════════════════════════
    fun setOverlayMode(active: Boolean) {
        isOverlayActive = active
        if (active) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.canDrawOverlays(this)) {
                isOverlayActive = false
                Toast.makeText(
                    this,
                    "설정 → 앱 → X-WARE → '다른 앱 위에 표시' 허용 후 다시 시도하세요",
                    Toast.LENGTH_LONG
                ).show()
                try {
                    startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ))
                } catch (_: Exception) {
                    startActivity(Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    ))
                }
                return
            }
            startLyricsOverlay()
        } else {
            stopLyricsOverlay()
        }
    }

    private var _pendingOverlay = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && _pendingOverlay) {
            _pendingOverlay = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                Settings.canDrawOverlays(this)) {
                isOverlayActive = true
                startLyricsOverlay()
                webView.evaluateJavascript(
                    "document.getElementById('bt-overlay-btn')?.classList.add('on');" +
                    "document.getElementById('np-overlay-btn')?.classList.add('on');",
                    null
                )
            }
        }
    }

    private fun startLyricsOverlay() {
        startService(Intent(this, LyricsOverlayService::class.java))
    }

    private fun stopLyricsOverlay() {
        isOverlayActive = false
        stopService(Intent(this, LyricsOverlayService::class.java))
    }

    fun syncPlayStateToOverlay(playing: Boolean) {
        if (!isOverlayActive) return
        LyricsOverlayService.updatePlayState(this, playing)
    }

    fun syncTrackToOverlay(title: String, thumb: String) {
        if (!isOverlayActive) return
        LyricsOverlayService.updateTrack(this, title, thumb)
    }

    fun updateOverlayLyrics(prev: String, active: String, next: String) {
        if (!isOverlayActive) return
        LyricsOverlayService.updateLyrics(this, prev, active, next)
    }

    fun updateNotificationTitle(title: String) {
        sendBroadcast(Intent(MusicKeepAliveService.ACTION_UPDATE_TITLE).apply {
            putExtra("title", title)
        })
    }

    // ════════════════════════════════════════════
    //  음악 서비스
    // ════════════════════════════════════════════
    private fun startMusicService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(
                    "xware_music", "X-WARE 음악",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                })
        }
        val i = Intent(this, MusicKeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
    }

    private fun stopMusicService() =
        stopService(Intent(this, MusicKeepAliveService::class.java))

    // ════════════════════════════════════════════
    //  권한
    // ════════════════════════════════════════════
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002
            )
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                    }
                }
            } catch (_: Exception) { }
        }
    }

    @Deprecated("Deprecated")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                Settings.canDrawOverlays(this)) {
                isOverlayActive = true
                startLyricsOverlay()
            } else {
                Toast.makeText(this, "권한 거부됨", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
