package com.xware

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.*
import android.os.*
import android.util.Log
import android.view.*
import android.view.animation.*
import android.widget.*
import androidx.core.app.NotificationCompat

class LyricsOverlayService : Service() {

    companion object {
        const val ACTION_UPDATE_LYRICS = "com.xware.ACTION_UPDATE_LYRICS"
        const val ACTION_CLEAR_LYRICS  = "com.xware.ACTION_CLEAR_LYRICS"
        const val ACTION_UPDATE_STATE  = "com.xware.ACTION_UPDATE_STATE"
        const val ACTION_UPDATE_TRACK  = "com.xware.ACTION_UPDATE_TRACK"
        const val CHANNEL_ID           = "xware_overlay"
        const val NOTIF_ID             = 1002

        fun updateLyrics(ctx: Context, prev: String, active: String, next: String) {
            ctx.startService(Intent(ctx, LyricsOverlayService::class.java).apply {
                action = ACTION_UPDATE_LYRICS
                putExtra("prev", prev); putExtra("active", active); putExtra("next", next)
            })
        }
        fun updatePlayState(ctx: Context, playing: Boolean) {
            ctx.startService(Intent(ctx, LyricsOverlayService::class.java).apply {
                action = ACTION_UPDATE_STATE
                putExtra("playing", playing)
            })
        }
        fun updateTrack(ctx: Context, title: String, thumb: String) {
            ctx.startService(Intent(ctx, LyricsOverlayService::class.java).apply {
                action = ACTION_UPDATE_TRACK
                putExtra("title", title); putExtra("thumb", thumb)
            })
        }
    }

    private val TAG = "XWareOverlay"
    private var wm: WindowManager? = null

    // ── 뷰 참조 ──────────────────────────────────
    private var rootView:       FrameLayout?               = null
    private var bubbleView:     FrameLayout?               = null
    private var bubbleLp:       FrameLayout.LayoutParams?  = null
    private var bubbleIconView: android.widget.ImageView?  = null
    private var panelView:      LinearLayout?              = null
    private var panelLp:        FrameLayout.LayoutParams?  = null
    private var panelPlayIcon:  android.widget.ImageView?  = null
    private var panelTitleTv:   TextView?                  = null

    // ── 상태 ─────────────────────────────────────
    private var isPlaying  = false
    private var trackTitle = ""
    private var isExpanded = false

    // ── 버블 위치 (스크린 절대좌표) ───────────────
    private var bubLeft = 0    // 버블의 스크린 x 좌표
    private var bubTop  = 0    // 버블의 스크린 y 좌표

    // ── 상수 ─────────────────────────────────────
    private val BUBBLE_DP  = 52
    private val PANEL_W_DP = 200
    private val DRAG_THRESHOLD = 8

    // ── 드래그 추적 ───────────────────────────────
    private var initBubLeft = 0; private var initBubTop = 0
    private var initTouchX  = 0f; private var initTouchY = 0f
    private var dragging    = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotifChannel()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val screenW = resources.displayMetrics.widthPixels
        bubLeft = dp(16)     // 초기 위치: 왼쪽
        bubTop  = resources.displayMetrics.heightPixels / 3
        createOverlay(screenW)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE_STATE -> {
                isPlaying = intent.getBooleanExtra("playing", false)
                updateAllPlayIcons()
            }
            ACTION_UPDATE_TRACK -> {
                trackTitle = intent.getStringExtra("title") ?: ""
                updatePanelTitle()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { rootView?.let { wm?.removeView(it) } } catch (_: Exception) {}
        rootView = null
        super.onDestroy()
    }

    // ══════════════════════════════════════════════
    //  오버레이 생성
    //  ★ root = 전체 화면 크기, lp.x=0 고정
    //    버블은 root 내부에서 leftMargin/topMargin 으로 이동
    //    → 패널 위치가 버블 기준으로 정확하게 계산됨
    // ══════════════════════════════════════════════
    private fun createOverlay(screenW: Int) {
        val bubPx  = dp(BUBBLE_DP)
        val panW   = dp(PANEL_W_DP)

        // ── 버블 ──
        val bubble = FrameLayout(this)
        bubbleView = bubble
        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(220, 10, 10, 20))
            setStroke(dp(1), Color.argb(80, 250, 45, 90))
        }
        bubble.elevation = dp(6).toFloat()

        val iconView = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        bubbleIconView = iconView
        renderPlayIcon(iconView, isPlaying)
        bubble.addView(iconView, FrameLayout.LayoutParams(-1, -1))

        // ★ 버블 LayoutParams: leftMargin/topMargin 으로 위치 제어
        val bLp = FrameLayout.LayoutParams(bubPx, bubPx).apply {
            leftMargin = bubLeft
            topMargin  = 0   // root의 y는 WindowManager로 제어
            gravity    = Gravity.TOP or Gravity.START
        }
        bubbleLp = bLp

        // ── 패널 ──
        val panel = buildPanel()
        panelView = panel
        panel.visibility = View.GONE

        // ★ 패널 LayoutParams: 초기에는 버블 오른쪽
        val pLp = FrameLayout.LayoutParams(panW, -2).apply {
            leftMargin = bubLeft + bubPx + dp(6)
            topMargin  = (bubPx - dp(PANEL_W_DP)) / 2  // 수직 중앙 정렬 (대략)
            gravity    = Gravity.TOP or Gravity.START
        }
        panelLp = pLp

        // ── 루트 ──
        val root = FrameLayout(this)
        rootView = root
        root.addView(bubble, bLp)
        root.addView(panel,  pLp)

        // ── WindowManager 파라미터 ──
        // ★ 핵심: x=0, 전체 화면 폭. 버블이 내부에서 이동함
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val wmLp = WindowManager.LayoutParams(
            screenW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or  // ★ 버블 외 영역 터치 통과
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = bubTop
        }

        // ── 터치 이벤트 ──
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    initBubLeft = bubLeft; initBubTop = bubTop
                    initTouchX  = e.rawX;  initTouchY = e.rawY
                    dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - initTouchX).toInt()
                    val dy = (e.rawY - initTouchY).toInt()
                    if (!dragging &&
                        (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD)) {
                        dragging = true
                        if (isExpanded) collapsePanel()
                    }
                    if (dragging) {
                        bubLeft = (initBubLeft + dx).coerceIn(
                            0, screenW - dp(BUBBLE_DP)
                        )
                        bubTop = initBubTop + dy
                        // 버블 위치 업데이트
                        bLp.leftMargin = bubLeft
                        bubble.layoutParams = bLp
                        // root y 위치 업데이트
                        val lp = root.layoutParams as? WindowManager.LayoutParams ?: wmLp
                        lp.y = bubTop
                        try { wm?.updateViewLayout(root, lp) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) showPanel(screenW)
                    else snapToEdge(screenW)
                    true
                }
                else -> false
            }
        }

        wm?.addView(root, wmLp)
    }

    // ══════════════════════════════════════════════
    //  패널 표시 — ★ 버블 위치 기반 좌/우 결정
    // ══════════════════════════════════════════════
    private fun showPanel(screenW: Int) {
        if (isExpanded) { collapsePanel(); return }

        val bubPx = dp(BUBBLE_DP)
        val panW  = dp(PANEL_W_DP)
        val gap   = dp(8)
        val pLp   = panelLp ?: return

        val onRight = (bubLeft + bubPx / 2) > screenW / 2

        if (onRight) {
            // 버블이 오른쪽 → 패널을 버블 왼쪽에 배치
            val panelLeft = bubLeft - panW - gap
            pLp.leftMargin = panelLeft.coerceAtLeast(dp(4))
        } else {
            // 버블이 왼쪽 → 패널을 버블 오른쪽에 배치
            val panelLeft = bubLeft + bubPx + gap
            pLp.leftMargin = panelLeft.coerceAtMost(screenW - panW - dp(4))
        }

        pLp.topMargin = 0
        pLp.gravity   = Gravity.TOP or Gravity.START
        panelView?.layoutParams = pLp
        expandPanel()
    }

    private fun expandPanel() {
        isExpanded = true
        panelView?.apply {
            visibility = View.VISIBLE
            alpha = 0f; scaleX = 0.92f; scaleY = 0.92f
            animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun collapsePanel() {
        isExpanded = false
        panelView?.animate()
            ?.alpha(0f)?.scaleX(0.92f)?.scaleY(0.92f)
            ?.setDuration(160)
            ?.withEndAction { panelView?.visibility = View.GONE }
            ?.start()
    }

    // ══════════════════════════════════════════════
    //  화면 끝으로 스냅
    // ══════════════════════════════════════════════
    private fun snapToEdge(screenW: Int) {
        val root  = rootView ?: return
        val bubPx = dp(BUBBLE_DP)
        val targetLeft = if (bubLeft + bubPx / 2 < screenW / 2) dp(16)
                         else screenW - bubPx - dp(16)
        val startLeft = bubLeft
        val anim = android.animation.ValueAnimator.ofInt(startLeft, targetLeft)
        anim.duration = 220; anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener { a ->
            bubLeft = a.animatedValue as Int
            val bLp = bubbleLp ?: return@addUpdateListener
            bLp.leftMargin = bubLeft
            bubbleView?.layoutParams = bLp
        }
        anim.start()
    }

    // ══════════════════════════════════════════════
    //  컨트롤 패널 빌드
    // ══════════════════════════════════════════════
    private fun buildPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            elevation   = dp(10).toFloat()
        }
        panel.background = GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.argb(230, 8, 8, 18))
            setStroke(dp(1), Color.argb(50, 255, 255, 255))
        }

        // 트랙 타이틀
        val titleTv = TextView(this).apply {
            text      = "X-WARE"
            textSize  = 11.5f
            setTextColor(Color.argb(200, 255, 255, 255))
            typeface  = Typeface.DEFAULT_BOLD
            maxLines  = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity   = Gravity.CENTER
        }
        panelTitleTv = titleTv
        panel.addView(titleTv, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(10)
        })

        // 버튼 행
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
            weightSum   = 3f
        }
        row.addView(makeSvgBtn(BtnType.PREV) { sendCmd("prevT()") })

        // 재생/정지
        val playContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
        }
        val playIv = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        panelPlayIcon = playIv
        renderPlayIcon(playIv, isPlaying)
        playContainer.addView(playIv)
        playContainer.setOnClickListener { sendCmd("togglePlay()") }
        row.addView(playContainer)

        row.addView(makeSvgBtn(BtnType.NEXT) { sendCmd("nextT()") })
        panel.addView(row, LinearLayout.LayoutParams(-1, -2))

        // 구분선
        panel.addView(View(this).apply {
            setBackgroundColor(Color.argb(40, 255, 255, 255))
        }, LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(8); bottomMargin = dp(6)
        })

        // 종료 버튼
        val closeBtn = TextView(this).apply {
            text     = "오버레이 종료"
            textSize = 10f
            setTextColor(Color.argb(140, 255, 255, 255))
            gravity  = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        }
        closeBtn.setOnClickListener {
            sendCmd("if(typeof toggleOverlay==='function')toggleOverlay()")
        }
        panel.addView(closeBtn, LinearLayout.LayoutParams(-1, -2))
        return panel
    }

    // ══════════════════════════════════════════════
    //  아이콘 렌더링
    // ══════════════════════════════════════════════
    enum class BtnType { PREV, NEXT }

    private fun renderPlayIcon(iv: android.widget.ImageView, playing: Boolean) {
        val size = dp(24)
        val bmp  = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c    = Canvas(bmp)
        val p    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        }
        if (playing) {
            val bw = size * 0.22f; val bh = size * 0.60f
            val top = (size - bh) / 2f; val gap = size * 0.14f
            val l1 = (size - bw * 2 - gap) / 2f; val r = dp(1).toFloat()
            c.drawRoundRect(l1, top, l1 + bw, top + bh, r, r, p)
            c.drawRoundRect(l1 + bw + gap, top, l1 + bw * 2 + gap, top + bh, r, r, p)
        } else {
            val path = Path(); val h = size * 0.60f; val top = (size - h) / 2f
            path.moveTo(size * 0.30f, top)
            path.lineTo(size * 0.74f, size / 2f)
            path.lineTo(size * 0.30f, top + h)
            path.close(); c.drawPath(path, p)
        }
        iv.setImageBitmap(bmp)
    }

    private fun makeSvgBtn(type: BtnType, onClick: () -> Unit): FrameLayout {
        val fl = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
        }
        val iv = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val bs = dp(24)
        val bmp = Bitmap.createBitmap(bs, bs, Bitmap.Config.ARGB_8888)
        val c   = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255); style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255); style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeWidth = dp(2).toFloat()
        }
        when (type) {
            BtnType.PREV -> {
                val h = bs * 0.54f; val top = (bs - h) / 2f; val rx = bs * 0.62f
                val path = Path()
                path.moveTo(rx, top); path.lineTo(rx - bs * 0.34f, bs / 2f)
                path.lineTo(rx, top + h); path.close(); c.drawPath(path, fill)
                val bx = bs * 0.22f; c.drawLine(bx, top, bx, top + h, stroke)
            }
            BtnType.NEXT -> {
                val h = bs * 0.54f; val top = (bs - h) / 2f; val lx = bs * 0.24f
                val path = Path()
                path.moveTo(lx, top); path.lineTo(lx + bs * 0.34f, bs / 2f)
                path.lineTo(lx, top + h); path.close(); c.drawPath(path, fill)
                val bx = bs * 0.78f; c.drawLine(bx, top, bx, top + h, stroke)
            }
        }
        iv.setImageBitmap(bmp)
        fl.addView(iv, FrameLayout.LayoutParams(-1, -1))
        fl.setOnClickListener { onClick() }
        return fl
    }

    // ══════════════════════════════════════════════
    //  상태 업데이트
    // ══════════════════════════════════════════════
    private fun updateAllPlayIcons() {
        bubbleIconView?.post { bubbleIconView?.let { renderPlayIcon(it, isPlaying) } }
        panelPlayIcon?.post  { panelPlayIcon?.let  { renderPlayIcon(it, isPlaying) } }
    }

    private fun updatePanelTitle() {
        panelTitleTv?.post {
            panelTitleTv?.text = if (trackTitle.isNotBlank()) trackTitle else "X-WARE"
        }
    }

    private fun sendCmd(js: String) {
        sendBroadcast(Intent("com.xware.OVERLAY_CONTROL").apply {
            putExtra("js", js); setPackage(packageName)
        })
    }

    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, "X-WARE 오버레이",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) })
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
