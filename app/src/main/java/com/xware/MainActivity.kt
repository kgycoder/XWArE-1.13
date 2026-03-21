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

    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val OVERLAY_PERMISSION_REQUEST = 1001

    // ── 오버레이 컨트롤 브로드캐스트 수신기 ──────────
    // LyricsOverlayService 의 버튼 → JS 실행
    private val overlayControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val js = intent?.getStringExtra("js") ?: return
            dlog("오버레이 명령 수신: $js")
            webView.evaluateJavascript(js, null)
        }
    }

    // ════════════════════════════════════════════
    //  생명주기
    // ════════════════════════════════════════════
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupFullscreen()

        // 레이아웃: WebView + 인앱 디버그 패널
        val root = FrameLayout(this)
        webView  = WebView(this)
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        // 디버그 패널 제거

        // WebViewAssetLoader — https:// origin으로 YouTube 재생 허용
        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(ASSET_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/res/",    WebViewAssetLoader.ResourcesPathHandler(this))
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
            WebView.setWebContentsDebuggingEnabled(true)

        // 오버레이 브로드캐스트 등록
        val filter = IntentFilter("com.xware.OVERLAY_CONTROL")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(overlayControlReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(overlayControlReceiver, filter)

        setupWebView()
        startMusicService()
        requestNotificationPermission()
        requestBatteryOptimizationExemption()

        dlog("앱 시작 → $ASSET_URL")
        webView.loadUrl(ASSET_URL)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    // ★ 백그라운드 재생 핵심: onPause/onStop 에서 webView 절대 멈추지 않음
    // resumeTimers() 로 YouTube JS 타이머 강제 유지 → 음악 계속 재생
    override fun onPause() {
        super.onPause()
        webView.resumeTimers()  // 타이머 유지!
    }

    override fun onStop() {
        super.onStop()
        webView.resumeTimers()  // 백그라운드에서도 유지
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

    // ── 디버그 로그 ─────────────────────────────
    fun dlog(msg: String, level: String = "I") {
        when (level) { "E" -> Log.e(TAG, msg); "W" -> Log.w(TAG, msg); else -> Log.i(TAG, msg) }
    }

    // ════════════════════════════════════════════
    //  WebView 설정
    // ════════════════════════════════════════════
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled  = true
            domStorageEnabled  = true
            // Chrome Mobile UA — YouTube 재생 핵심
            userAgentString    =
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.6099.144 Mobile Safari/537.36"
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort    = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls  = false
            displayZoomControls  = false
            allowFileAccess      = true
            allowContentAccess   = true
            mixedContentMode     = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode            = WebSettings.LOAD_DEFAULT
            databaseEnabled      = true
            // ★ 한국어 깨짐 방지: UTF-8 인코딩 명시
            defaultTextEncodingName = "UTF-8"
        }

        // WebChromeClient
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                if (msg == null) return false
                val lv = when (msg.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR   -> "E"
                    ConsoleMessage.MessageLevel.WARNING -> "W"
                    else -> "I"
                }
                val src  = msg.sourceId()?.substringAfterLast('/') ?: ""
                dlog("[$src:${msg.lineNumber()}] ${msg.message()}", lv)
                return true
            }
            override fun onPermissionRequest(req: PermissionRequest?) { req?.grant(req.resources) }
            override fun onJsAlert(v: WebView?, u: String?, m: String?, r: JsResult?): Boolean {
                Toast.makeText(this@MainActivity, m, Toast.LENGTH_SHORT).show(); r?.confirm(); return true
            }
            private var customView: View? = null
            private var customViewCb: CustomViewCallback? = null
            override fun onShowCustomView(view: View?, cb: CustomViewCallback?) {
                if (customView != null) { cb?.onCustomViewHidden(); return }
                customView = view; customViewCb = cb
                (window.decorView as? FrameLayout)?.addView(view, FrameLayout.LayoutParams(-1,-1))
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
            override fun onHideCustomView() {
                (window.decorView as? FrameLayout)?.takeIf { customView != null }?.removeView(customView)
                customView = null; customViewCb?.onCustomViewHidden(); customViewCb = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
        }

        // WebViewClient
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return null
                return assetLoader.shouldInterceptRequest(uri)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                dlog("로딩완료: $url")
                view?.evaluateJavascript("""
                    (function(){
                        window.onerror = function(m,s,l){ console.error('[ERR] '+m+' @'+s+':'+l); return false; };
                        window.onunhandledrejection = function(e){ console.error('[PROMISE] '+(e.reason||e)); };
                        console.log('[CHECK] origin='+location.origin
                            +' bridge='+(typeof AndroidBridge!=='undefined')
                            +' YT='+(typeof YT!=='undefined'));
                    })();
                """.trimIndent(), null)
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                val url = request?.url?.toString() ?: ""
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (url.contains(ASSET_HOST))
                        dlog("Asset오류[${error?.errorCode}]: $url", "E")
                }
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, err: android.net.http.SslError?) {
                handler?.proceed()
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                val keep = listOf(ASSET_HOST, "youtube.com", "googlevideo.com",
                    "ytimg.com", "lrclib.net", "googleapis.com",
                    "fonts.google", "suggestqueries", "gstatic.com")
                if (keep.any { url.contains(it) }) return false
                return try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); true }
                       catch (_: Exception) { false }
            }
        }

        bridge = AndroidBridge(WeakReference(this), webView, scope)
        webView.addJavascriptInterface(bridge, "AndroidBridge")
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        dlog("WebView 설정 완료")
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
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            window.statusBarColor = Color.TRANSPARENT
    }

    // ════════════════════════════════════════════
    //  오버레이 모드 제어
    // ════════════════════════════════════════════
    fun setOverlayMode(active: Boolean) {
        dlog("오버레이: $active")
        if (active) {
            // Android 6+: SYSTEM_ALERT_WINDOW 권한 필요
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.canDrawOverlays(this)) {
                Toast.makeText(
                    this,
                    "설정 → 앱 → X-WARE → '다른 앱 위에 표시' 허용 후 다시 시도하세요",
                    Toast.LENGTH_LONG
                ).show()
                // 설정 화면으로 이동 (결과 콜백 없이 startActivity 사용)
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")))
                }
                return
            }
            startLyricsOverlay()
        } else {
            stopLyricsOverlay()
        }
    }

    // 앱 복귀 시 권한이 허가됐으면 자동으로 오버레이 시작
    private var _pendingOverlay = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && _pendingOverlay) {
            _pendingOverlay = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                Settings.canDrawOverlays(this)) {
                startLyricsOverlay()
                // JS에 오버레이 켜짐 상태 알림
                webView.evaluateJavascript(
                    "document.getElementById('bt-overlay-btn')?.classList.add('on');" +
                    "document.getElementById('np-overlay-btn')?.classList.add('on');", null)
            }
        }
    }

    private fun startLyricsOverlay() {
        val i = Intent(this, LyricsOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
    }

    private fun stopLyricsOverlay() {
        stopService(Intent(this, LyricsOverlayService::class.java))
    }

    // 재생 상태 → 오버레이 버블 아이콘 동기화
    fun syncPlayStateToOverlay(playing: Boolean) {
        LyricsOverlayService.updatePlayState(this, playing)
    }

    // 트랙 변경 → 오버레이 패널 타이틀 동기화
    fun syncTrackToOverlay(title: String, thumb: String) {
        LyricsOverlayService.updateTrack(this, title, thumb)
    }

    fun updateOverlayLyrics(prev: String, active: String, next: String) {
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
                    "xware_music", "X-WARE 음악", NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
        }
        val i = Intent(this, MusicKeepAliveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
    }
    private fun stopMusicService() = stopService(Intent(this, MusicKeepAliveService::class.java))

    // ════════════════════════════════════════════
    //  알림 권한
    // ════════════════════════════════════════════
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1002)
    }

    // ★ 배터리 최적화 제외 요청 — 백그라운드 재생 유지에 필수
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    dlog("배터리 최적화 제외 요청 실패: $e", "W")
                }
            }
        }
    }

    @Deprecated("Deprecated")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                val i = Intent(this, LyricsOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
                else startService(i)
            } else Toast.makeText(this, "권한 거부됨", Toast.LENGTH_SHORT).show()
        }
    }
}
