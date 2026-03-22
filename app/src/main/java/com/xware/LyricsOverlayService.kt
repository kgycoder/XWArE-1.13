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

    private var bubbleRoot:     FrameLayout?                  = null
    private var bubbleIconView: android.widget.ImageView?     = null
    private var panelPlayIcon:  android.widget.ImageView?     = null
    private var panelTitleTv:   TextView?                     = null
    private var panelView:      LinearLayout?                  = null

    // ★ 패널 방향 관리를 위한 레이아웃 파라미터 참조
    private var panelLp:        FrameLayout.LayoutParams?     = null

    private var isPlaying  = false
    private var trackTitle = ""
    private var isExpanded = false

    private val BUBBLE_DP  = 52
    private val PANEL_W_DP = 200

    private var initX = 0;   private var initY = 0
    private var initTX = 0f; private var initTY = 0f
    private var dragging = false
    private val DRAG_THRESHOLD = 8

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotifChannel()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createBubble()
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
        removeBubble()
        super.onDestroy()
    }

    // ══════════════════════════════════════════════
    //  버블 생성
    // ══════════════════════════════════════════════
    private fun createBubble() {
        val bubPx   = dp(BUBBLE_DP)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        // 버블 배경
        val bubble = FrameLayout(this)
        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(220, 10, 10, 20))
            setStroke(dp(1), Color.argb(80, 250, 45, 90))
        }
        bubble.elevation = dp(6).toFloat()

        // 버블 중앙 아이콘
        val iconView = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        bubbleIconView = iconView
        renderPlayIcon(iconView, isPlaying)
        bubble.addView(iconView, FrameLayout.LayoutParams(-1, -1))

        // 컨트롤 패널
        val panel = buildPanel()
        panelView = panel
        panel.visibility = View.GONE

        // ★ 패널 LayoutParams 저장 (방향 전환에 사용)
        val pLp = FrameLayout.LayoutParams(dp(PANEL_W_DP), -2).apply {
            marginStart = bubPx + dp(6)  // 초기: 버블 오른쪽
        }
        panelLp = pLp

        // 루트
        val root = FrameLayout(this)
        bubbleRoot = root
        root.addView(bubble, FrameLayout.LayoutParams(bubPx, bubPx))
        root.addView(panel, pLp)

        // WindowManager 파라미터
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            screenW,   // ★ 전체 폭 확보: 패널이 어느 방향이든 잘리지 않음
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = screenH / 3
        }

        // 터치 이벤트
        bubble.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val lp = (root.layoutParams as? WindowManager.LayoutParams) ?: params
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = lp.x; initY = lp.y
                        initTX = e.rawX; initTY = e.rawY
                        dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - initTX).toInt()
                        val dy = (e.rawY - initTY).toInt()
                        if (!dragging &&
                            (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD)) {
                            dragging = true
                            if (isExpanded) collapsePanel()
                        }
                        if (dragging) {
                            lp.x = initX + dx; lp.y = initY + dy
                            try { wm?.updateViewLayout(root, lp) } catch (_: Exception) {}
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragging) togglePanel(lp)
                        else snapToEdge(lp)
                    }
                }
                return true
            }
        })

        // 초기 위치: 왼쪽
        params.x = dp(16)
        wm?.addView(root, params)
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

        // 컨트롤 버튼 행
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
            weightSum   = 3f
        }
        row.addView(makeSvgBtn(BtnType.PREV) { sendCmd("prevT()") })

        // 재생/정지 버튼
        val playContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
        }
        val playIv = android.widget.ImageView(this).apply {
            scaleType    = android.widget.ImageView.ScaleType.CENTER_INSIDE
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
            text    = "오버레이 종료"
            textSize = 10f
            setTextColor(Color.argb(140, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        }
        closeBtn.setOnClickListener {
            sendCmd("if(typeof toggleOverlay==='function')toggleOverlay()")
        }
        panel.addView(closeBtn, LinearLayout.LayoutParams(-1, -2))

        return panel
    }

    // ══════════════════════════════════════════════
    //  패널 토글 — ★ 버블 위치에 따라 패널 방향 결정
    // ══════════════════════════════════════════════
    private fun togglePanel(lp: WindowManager.LayoutParams) {
        if (isExpanded) { collapsePanel(); return }

        val screenW  = resources.displayMetrics.widthPixels
        val bubPx    = dp(BUBBLE_DP)
        val panelW   = dp(PANEL_W_DP)
        val bubCenterX = lp.x + bubPx / 2

        // 버블이 화면 오른쪽 절반에 있으면 패널을 왼쪽에 표시
        val onRight  = bubCenterX > screenW / 2
        val pLp      = panelLp ?: return

        if (onRight) {
            // 패널을 버블 왼쪽에 배치
            pLp.marginStart = 0
            pLp.marginEnd   = 0
            // 버블의 루트-상대 x 계산: 버블은 lp.x 위치에 있고, root는 x=0에서 시작
            // 버블 중심에서 패널 폭만큼 왼쪽으로
            pLp.leftMargin  = lp.x - panelW - dp(6)
            pLp.gravity     = Gravity.NO_GRAVITY
        } else {
            // 패널을 버블 오른쪽에 배치 (기본)
            pLp.leftMargin  = lp.x + bubPx + dp(6)
            pLp.gravity     = Gravity.NO_GRAVITY
        }

        panelView?.layoutParams = pLp
        expandPanel()
    }

    private fun expandPanel() {
        isExpanded = true
        panelView?.apply {
            visibility   = View.VISIBLE
            alpha        = 0f
            translationX = 0f
            scaleX       = 0.9f; scaleY = 0.9f
            animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun collapsePanel() {
        isExpanded = false
        panelView?.animate()
            ?.alpha(0f)?.scaleX(0.9f)?.scaleY(0.9f)
            ?.setDuration(160)
            ?.withEndAction { panelView?.visibility = View.GONE }
            ?.start()
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
            val l1 = (size - bw * 2 - gap) / 2f
            val r1 = dp(1).toFloat()
            c.drawRoundRect(l1, top, l1 + bw, top + bh, r1, r1, p)
            c.drawRoundRect(l1 + bw + gap, top, l1 + bw * 2 + gap, top + bh, r1, r1, p)
        } else {
            val path = Path(); val h = size * 0.60f; val top = (size - h) / 2f
            val lx = size * 0.30f
            path.moveTo(lx, top); path.lineTo(lx + size * 0.44f, size / 2f)
            path.lineTo(lx, top + h); path.close()
            c.drawPath(path, p)
        }
        iv.setImageBitmap(bmp)
    }

    private fun makeSvgBtn(type: BtnType, onClick: () -> Unit): FrameLayout {
        val size    = dp(40)
        val fl      = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, size, 1f)
        }
        val iv      = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val bmpSize = dp(24)
        val bmp     = Bitmap.createBitmap(bmpSize, bmpSize, Bitmap.Config.ARGB_8888)
        val c       = Canvas(bmp)
        val fill    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255); style = Paint.Style.FILL
        }
        val stroke  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255); style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeWidth = dp(2).toFloat()
        }
        when (type) {
            BtnType.PREV -> {
                val path = Path(); val h = bmpSize * 0.54f; val top = (bmpSize - h) / 2f
                val rx = bmpSize * 0.62f
                path.moveTo(rx, top); path.lineTo(rx - bmpSize * 0.34f, bmpSize / 2f)
                path.lineTo(rx, top + h); path.close(); c.drawPath(path, fill)
                val bx = bmpSize * 0.22f; c.drawLine(bx, top, bx, top + h, stroke)
            }
            BtnType.NEXT -> {
                val path = Path(); val h = bmpSize * 0.54f; val top = (bmpSize - h) / 2f
                val lx = bmpSize * 0.24f
                path.moveTo(lx, top); path.lineTo(lx + bmpSize * 0.34f, bmpSize / 2f)
                path.lineTo(lx, top + h); path.close(); c.drawPath(path, fill)
                val bx = bmpSize * 0.78f; c.drawLine(bx, top, bx, top + h, stroke)
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

    // ══════════════════════════════════════════════
    //  스냅
    // ══════════════════════════════════════════════
    private fun snapToEdge(lp: WindowManager.LayoutParams) {
        val root    = bubbleRoot ?: return
        val screenW = resources.displayMetrics.widthPixels
        val bubPx   = dp(BUBBLE_DP)
        val target  = if (lp.x + bubPx / 2 < screenW / 2) dp(16)
                      else screenW - bubPx - dp(16)
        val anim    = android.animation.ValueAnimator.ofInt(lp.x, target)
        anim.duration = 220; anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener { a ->
            lp.x = a.animatedValue as Int
            try { wm?.updateViewLayout(root, lp) } catch (_: Exception) {}
        }
        anim.start()
    }

    private fun sendCmd(js: String) {
        sendBroadcast(Intent("com.xware.OVERLAY_CONTROL").apply {
            putExtra("js", js); setPackage(packageName)
        })
    }

    private fun removeBubble() {
        try { bubbleRoot?.let { wm?.removeView(it) } } catch (_: Exception) {}
        bubbleRoot = null
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
