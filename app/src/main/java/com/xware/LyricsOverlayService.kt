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

    private var bubbleRoot: FrameLayout? = null
    private var playIconView: android.widget.ImageView? = null
    private var panelView: LinearLayout? = null
    private var panelTitleTv: TextView? = null

    private var isPlaying  = false
    private var trackTitle = ""
    private var isExpanded = false

    private val BUBBLE_DP  = 52
    private val PANEL_W_DP = 200

    // 드래그
    private var initX = 0; private var initY = 0
    private var initTouchX = 0f; private var initTouchY = 0f
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
                updatePlayIcon()
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
    //  버블 생성 — 심플 프리미엄 디자인
    // ══════════════════════════════════════════════
    private fun createBubble() {
        val bubPx    = dp(BUBBLE_DP)
        val screenW  = resources.displayMetrics.widthPixels
        val screenH  = resources.displayMetrics.heightPixels

        // ── 버블 배경: 반투명 다크 원형 ──
        val bubble = FrameLayout(this)
        bubbleRoot = FrameLayout(this) // 루트는 bubble + panel 포함

        val bgDrawable = GradientDrawable().apply {
            shape        = GradientDrawable.OVAL
            setColor(Color.argb(220, 10, 10, 20))
            setStroke(dp(1), Color.argb(80, 250, 45, 90))
        }
        bubble.background = bgDrawable
        bubble.elevation  = dp(6).toFloat()

        // ── 재생 아이콘 (SVG → Canvas 드로잉) ──
        val iconView = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        playIconView = iconView
        setPlayIcon(iconView, isPlaying)
        bubble.addView(iconView, FrameLayout.LayoutParams(-1, -1))

        // ── 컨트롤 패널 ──
        val panel = buildPanel()
        panelView  = panel
        panel.visibility = View.GONE

        val root = bubbleRoot!!
        root.addView(bubble, FrameLayout.LayoutParams(bubPx, bubPx))
        root.addView(panel, FrameLayout.LayoutParams(dp(PANEL_W_DP), -2).apply {
            marginStart = bubPx + dp(6)
        })

        // ── WindowManager 파라미터 ──
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            bubPx + dp(PANEL_W_DP) + dp(6),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)           // ★ 왼쪽 고정
            y = screenH / 3
        }

        // ── 터치: 드래그 + 탭 ──
        bubble.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val lp = (root.layoutParams as? WindowManager.LayoutParams) ?: params
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initX = lp.x; initY = lp.y
                        initTouchX = e.rawX; initTouchY = e.rawY
                        dragging = false
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
                            lp.x = initX + dx
                            lp.y = initY + dy
                            try { wm?.updateViewLayout(root, lp) } catch (_: Exception) {}
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragging) togglePanel()
                        else snapToEdge(lp)
                    }
                }
                return true
            }
        })

        wm?.addView(root, params)
    }

    // ── SVG 아이콘 그리기 (Canvas 기반) ──────────
    private fun setPlayIcon(iv: android.widget.ImageView, playing: Boolean) {
        val size = dp(24)
        val bmp  = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c    = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        if (playing) {
            // ■■ 일시정지 아이콘 (두 개의 직사각형)
            val barW = size * 0.22f
            val barH = size * 0.62f
            val top  = (size - barH) / 2f
            val gap  = size * 0.16f
            val left1 = (size - barW * 2 - gap) / 2f
            val left2 = left1 + barW + gap
            c.drawRoundRect(left1, top, left1 + barW, top + barH, dp(1).toFloat(), dp(1).toFloat(), paint)
            c.drawRoundRect(left2, top, left2 + barW, top + barH, dp(1).toFloat(), dp(1).toFloat(), paint)
        } else {
            // ▶ 재생 아이콘 (삼각형)
            val path = Path()
            val cx   = size * 0.52f
            val h    = size * 0.62f
            val top  = (size - h) / 2f
            path.moveTo(cx - size * 0.22f, top)
            path.lineTo(cx + size * 0.32f, size / 2f)
            path.lineTo(cx - size * 0.22f, top + h)
            path.close()
            c.drawPath(path, paint)
        }

        iv.setImageBitmap(bmp)
    }

    // ── 컨트롤 패널 빌드 ──────────────────────────
    private fun buildPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            elevation   = dp(10).toFloat()
        }

        // 배경: 모서리 둥근 반투명 다크
        val bg = GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(Color.argb(230, 8, 8, 18))
            setStroke(dp(1), Color.argb(50, 255, 255, 255))
        }
        panel.background = bg

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
        row.addView(makeSvgBtn(SvgIcon.PREV)  { sendCmd("prevT()") })
        row.addView(makeSvgBtn(SvgIcon.PLAY)  { sendCmd("togglePlay()") })
        row.addView(makeSvgBtn(SvgIcon.NEXT)  { sendCmd("nextT()") })
        panel.addView(row, LinearLayout.LayoutParams(-1, -2))

        // 구분선
        val divider = View(this).apply {
            setBackgroundColor(Color.argb(40, 255, 255, 255))
        }
        panel.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin    = dp(8)
            bottomMargin = dp(6)
        })

        // 종료 버튼
        val closeBtn = TextView(this).apply {
            text      = "오버레이 종료"
            textSize  = 10f
            setTextColor(Color.argb(140, 255, 255, 255))
            gravity   = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        }
        closeBtn.setOnClickListener {
            sendCmd("if(typeof toggleOverlay==='function')toggleOverlay()")
        }
        panel.addView(closeBtn, LinearLayout.LayoutParams(-1, -2))

        return panel
    }

    // ── SVG 아이콘 버튼 ──────────────────────────
    enum class SvgIcon { PREV, PLAY, NEXT }

    private fun makeSvgBtn(icon: SvgIcon, onClick: () -> Unit): FrameLayout {
        val size = dp(40)
        val fl   = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, size, 1f)
        }
        val iv   = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        // Canvas로 각 아이콘 그리기
        val bmpSize = dp(24)
        val bmp     = Bitmap.createBitmap(bmpSize, bmpSize, Bitmap.Config.ARGB_8888)
        val c       = Canvas(bmp)
        val paint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255)
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color     = Color.argb(220, 255, 255, 255)
            style     = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = dp(2).toFloat()
        }

        when (icon) {
            SvgIcon.PREV -> {
                // ◀ + | 이전 트랙
                val path = Path()
                val cx   = bmpSize * 0.58f
                val h    = bmpSize * 0.55f
                val top  = (bmpSize - h) / 2f
                path.moveTo(cx + bmpSize * 0.22f, top)
                path.lineTo(cx - bmpSize * 0.22f, bmpSize / 2f)
                path.lineTo(cx + bmpSize * 0.22f, top + h)
                path.close()
                c.drawPath(path, paint)
                // 왼쪽 수직선
                val barX = bmpSize * 0.22f
                c.drawLine(barX, top, barX, top + h, strokePaint)
            }
            SvgIcon.PLAY -> {
                // ▶ 재생 (기본값)
                val path = Path()
                val cx   = bmpSize * 0.52f
                val h    = bmpSize * 0.60f
                val top  = (bmpSize - h) / 2f
                path.moveTo(cx - bmpSize * 0.20f, top)
                path.lineTo(cx + bmpSize * 0.30f, bmpSize / 2f)
                path.lineTo(cx - bmpSize * 0.20f, top + h)
                path.close()
                c.drawPath(path, paint)
            }
            SvgIcon.NEXT -> {
                // ▶ + | 다음 트랙
                val path = Path()
                val cx   = bmpSize * 0.42f
                val h    = bmpSize * 0.55f
                val top  = (bmpSize - h) / 2f
                path.moveTo(cx - bmpSize * 0.22f, top)
                path.lineTo(cx + bmpSize * 0.22f, bmpSize / 2f)
                path.lineTo(cx - bmpSize * 0.22f, top + h)
                path.close()
                c.drawPath(path, paint)
                // 오른쪽 수직선
                val barX = bmpSize * 0.78f
                c.drawLine(barX, top, barX, top + h, strokePaint)
            }
        }

        iv.setImageBitmap(bmp)
        fl.addView(iv, FrameLayout.LayoutParams(-1, -1))
        fl.setOnClickListener { onClick() }
        return fl
    }

    // ── 패널 토글 ─────────────────────────────────
    private fun togglePanel() {
        if (isExpanded) collapsePanel() else expandPanel()
    }

    private fun expandPanel() {
        isExpanded = true
        panelView?.apply {
            visibility = View.VISIBLE
            alpha = 0f; translationX = -dp(10).toFloat()
            animate().alpha(1f).translationX(0f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun collapsePanel() {
        isExpanded = false
        panelView?.animate()
            ?.alpha(0f)?.translationX(-dp(10).toFloat())
            ?.setDuration(160)
            ?.withEndAction { panelView?.visibility = View.GONE }
            ?.start()
    }

    // ── 화면 끝으로 스냅 ──────────────────────────
    private fun snapToEdge(lp: WindowManager.LayoutParams) {
        val root    = bubbleRoot ?: return
        val screenW = resources.displayMetrics.widthPixels
        val bubPx   = dp(BUBBLE_DP)
        val target  = if (lp.x + bubPx / 2 < screenW / 2) dp(16)
                      else screenW - bubPx - dp(16)
        val anim    = android.animation.ValueAnimator.ofInt(lp.x, target)
        anim.duration = 220
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener { a ->
            lp.x = a.animatedValue as Int
            try { wm?.updateViewLayout(root, lp) } catch (_: Exception) {}
        }
        anim.start()
    }

    // ── 상태 업데이트 ─────────────────────────────
    private fun updatePlayIcon() {
        playIconView?.post {
            playIconView?.let { setPlayIcon(it, isPlaying) }
        }
    }

    private fun updatePanelTitle() {
        panelTitleTv?.post {
            panelTitleTv?.text = if (trackTitle.isNotBlank()) trackTitle else "X-WARE"
        }
    }

    // ── JS 명령 전송 ──────────────────────────────
    private fun sendCmd(js: String) {
        sendBroadcast(Intent("com.xware.OVERLAY_CONTROL").apply {
            putExtra("js", js)
            setPackage(packageName)
        })
    }

    // ── 버블 제거 ─────────────────────────────────
    private fun removeBubble() {
        try { bubbleRoot?.let { wm?.removeView(it) } } catch (_: Exception) {}
        bubbleRoot = null
    }

    // ── 알림 채널 ─────────────────────────────────
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
