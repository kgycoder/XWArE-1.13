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

/**
 * ★ 백그라운드 재생 핵심:
 *   Android 시스템이 앱을 백그라운드로 보낼 때
 *   WebView.onWindowVisibilityChanged(GONE) 을 자동 호출 → 렌더러 스로틀링
 *   → YouTube IFrame 타이머/애니메이션 중단 → 영상 멈춤
 *
 *   BackgroundWebView: onPause() + onWindowVisibilityChanged() 완전 차단
 */
@SuppressLint("ViewConstructor")
class BackgroundWebView(context: android.content.Context) : WebView(context) {

    override fun onPause() {
        // ★ 완전 차단: super.onPause() 호출 안 함
        // 시스템이 직접 호출해도 렌더러에 pause 신호 전달되지 않음
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        // ★ 항상 VISIBLE로 강제
        // Android가 GONE을 보내도 WebView는 항상 화면에 있다고 인식
        super.onWindowVisibilityChanged(View.VISIBLE)
    }

    override fun onDetachedFromWindow() {
        // ★ 윈도우 분리 시에도 pause 막음
        // super.onDetachedFromWindow() 는 호출해야 메모리 누수 방지
        super.onDetachedFromWindow()
    }
}

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "XWare"
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val ASSET_URL  = "https://$ASSET_HOST/assets/index.html"
    }

    private lateinit var webView:     BackgroundWebView
    private lateinit var bridge:      AndroidBridge
    private lateinit var assetLoader: WebViewAssetLoader

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val OVERLAY_PERMISSION_REQUEST = 1001
    private var isOverlayActive = false

    // ★ resumeTimers 주기적 호출 (100ms 간격)
    // 시스템이 pauseTimers()를 호출해도 즉시 재개
    private val resumeHandler = Handler(Looper.getMainLooper())
    private val resumeRunnable = object : Runnable {
        override fun run() {
            try { webView.resumeTimers() } catch (_: Exception) {}
            resumeHandler.postDelayed(this, 100)
        }
    }

    private val overlayControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val js = intent?.getStringExtra("js") ?: return
            webView.evaluateJavascript(js, null)
        }
    }

    // ════════════════════════════════════════════
    //  생명주기
    // ════════════════════════════════════════════
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupCrashReporter()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupFullscreen()

        // ★ BackgroundWebView 사용
        val root = FrameLayout(this)
        webView  = BackgroundWebView(this)
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

        // ★ resumeTimers 루프 시작
        resumeHandler.post(resumeRunnable)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        // ★ 아무것도 하지 않음
        // BackgroundWebView.onPause() 가 이미 차단하지만
        // 시스템이 Activity.onPause() 이후 WebView에 직접 접근하는 경우 대비
    }

    override fun onStop() {
        super.onStop()
        // ★ 아무것도 하지 않음
        // resumeRunnable 이 계속 resumeTimers() 호출 중
    }

    override fun onDestroy() {
        resumeHandler.removeCallbacks(resumeRunnable)
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

        webView.setLayerType(View.LAYER_TYPE_NONE, null)

        // ★ 렌더러 우선순위: 백그라운드에서도 IMPORTANT 유지
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(
                WebView.RENDERER_PRIORITY_IMPORTANT,
                false   // 백그라운드에서도 우선순위 낮추지 않음
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
                dlog("렌더러 종료 (crashed=${detail?.didCrash()})", "W")
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
```

---

## 핵심 수정 요약
```
기존 문제:
  앱 백그라운드 →
  Android 시스템이 WebView.onWindowVisibilityChanged(GONE) 자동 호출 →
  WebView 렌더러 스로틀링 →
  YouTube IFrame requestAnimationFrame/타이머 중단 →
  영상 멈춤

해결:
  BackgroundWebView.onWindowVisibilityChanged() → 항상 View.VISIBLE 전달
  BackgroundWebView.onPause() → 완전 차단
  resumeHandler → 100ms 마다 resumeTimers() 강제 호출
