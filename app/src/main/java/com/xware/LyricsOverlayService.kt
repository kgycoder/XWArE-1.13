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
    private var bubbleBtn: FrameLayout?  = null
    private var playIcon: TextView?      = null
    private var panelView: LinearLayout? = null

    private var isPlaying  = false
    private var trackTitle = ""
    private var isExpanded = false

    private val BUBBLE_SIZE_DP = 60
    private val PANEL_W_DP    = 220

    private var initialX      = 0; private var initialY      = 0
    private var initialTouchX = 0f; private var initialTouchY = 0f
    private var isDragging    = false
    private val DRAG_THRESHOLD = 10

    // ── 생명주기 ───────────────────────────────────
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // ★ startForeground() 완전 제거
        //   Android 14 + targetSdk 34: foregroundServiceType 없이 호출 시 즉시 크래시
        //   MusicKeepAliveService 가 이미 프로세스를 살려두므로 불필요
        createNotifChannel()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createBubble()
        Log.i(TAG, "오버레이 버블 생성")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE_LYRICS -> { /* 버블에서는 가사 미표시 */ }
            ACTION_CLEAR_LYRICS  -> { }
            ACTION_UPDATE_STATE  -> {
                isPlaying = intent.getBooleanExtra("playing", false)
                updatePlayIcon()
            }
            ACTION_UPDATE_TRACK  -> {
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

    // ── 버블 생성 ─────────────────────────────────
    private fun createBubble() {
        val bubSizePx = dp(BUBBLE_SIZE_DP)
        val screenW   = resources.displayMetrics.widthPixels
        val screenH   = resources.displayMetrics.heightPixels

        val bubble = FrameLayout(this)
        bubbleBtn  = bubble

        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#E8060610"))
            setStroke(dp(2), Color.parseColor("#fa2d5a"))
        }
        bubble.background = bgDrawable
        bubble.elevation  = dp(8).toFloat()

        val icon = TextView(this).apply {
            text      = "▶"
            textSize  = 20f
            setTextColor(Color.WHITE)
            gravity   = Gravity.CENTER
            typeface  = Typeface.DEFAULT_BOLD
        }
        playIcon = icon
        bubble.addView(icon, FrameLayout.LayoutParams(-1, -1))

        val panel = buildPanel()
        panelView = panel
        panel.visibility = View.GONE

        val root = FrameLayout(this)
        bubbleRoot = root
        root.addView(bubble, FrameLayout.LayoutParams(bubSizePx, bubSizePx))
        root.addView(panel, FrameLayout.LayoutParams(dp(PANEL_W_DP), -2).apply {
            marginStart = bubSizePx + dp(8)
        })

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            bubSizePx + dp(PANEL_W_DP) + dp(8),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)          // ★ 우측(screenW - bubSz - dp(16)) → 좌측(dp(16))
            y = screenH / 3
        }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val lp = (root.layoutParams as? WindowManager.LayoutParams) ?: params
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = lp.x; initialY = lp.y
                        initialTouchX = e.rawX; initialTouchY = e.rawY
                        isDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - initialTouchX).toInt()
                        val dy = (e.rawY - initialTouchY).toInt()
                        if (!isDragging &&
                            (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD)) {
                            isDragging = true
                            if (isExpanded) collapsePanel()
                        }
                        if (isDragging) {
                            lp.x = initialX + dx
                            lp.y = initialY + dy
                            try { wm?.updateViewLayout(root, lp) } catch (_: Exception) {}
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) togglePanel(lp)
                        else snapToEdge(lp)
                    }
                }
                return true
            }
        })

        wm?.addView(root, params)
    }

    // ── 컨트롤 패널 ───────────────────────────────
    private fun buildPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            elevation = dp(12).toFloat()
        }
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#F0060610"))
            setStroke(dp(1), Color.parseColor("#33ffffff"))
        }
        panel.background = bg

        val titleTv = TextView(this).apply {
            text      = "X-WARE"
            textSize  = 12f
            setTextColor(Color.parseColor("#ccffffff"))
            typeface  = Typeface.DEFAULT_BOLD
            maxLines  = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity   = Gravity.CENTER
            tag       = "trackTitle"
        }
        panel.tag = titleTv
        panel.addView(titleTv, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(10)
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
        }
        row.addView(makeCtrlBtn("⏮") { sendOverlayCmd("prevT()") })
        val ppBtn = makeCtrlBtn("⏯") { sendOverlayCmd("togglePlay()") }
        ppBtn.tag = "playBtn"
        row.addView(ppBtn)
        row.addView(makeCtrlBtn("⏭") { sendOverlayCmd("nextT()") })
        panel.addView(row, LinearLayout.LayoutParams(-1, -2))

        val closeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
        }
        val closeBtn = TextView(this).apply {
            text      = "오버레이 종료"
            textSize  = 10f
            setTextColor(Color.parseColor("#88ffffff"))
            gravity   = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        closeBtn.setOnClickListener {
            sendOverlayCmd("if(typeof toggleOverlay==='function')toggleOverlay()")
        }
        closeRow.addView(closeBtn)
        panel.addView(closeRow, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(6)
        })

        return panel
    }

    private fun makeCtrlBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text      = label
            textSize  = 22f
            setTextColor(Color.WHITE)
            gravity   = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { onClick() }
        }
    }

    // ── 패널 토글 ─────────────────────────────────
    private fun togglePanel(lp: WindowManager.LayoutParams) {
        if (isExpanded) collapsePanel() else expandPanel()
    }

    private fun expandPanel() {
        isExpanded = true
        panelView?.apply {
            visibility = View.VISIBLE
            alpha = 0f; scaleX = 0.85f; scaleY = 0.85f
            animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun collapsePanel() {
        isExpanded = false
        panelView?.animate()?.alpha(0f)?.scaleX(0.85f)?.scaleY(0.85f)
            ?.setDuration(140)
            ?.withEndAction { panelView?.visibility = View.GONE }
            ?.start()
    }

    // ── 화면 끝으로 스냅 ──────────────────────────
    private fun snapToEdge(lp: WindowManager.LayoutParams) {
        val root    = bubbleRoot ?: return
        val screenW = resources.displayMetrics.widthPixels
        val bubSz   = dp(BUBBLE_SIZE_DP)
        val targetX = if (lp.x + bubSz / 2 < screenW / 2) dp(8)
                      else screenW - bubSz - dp(8)
        val animator = android.animation.ValueAnimator.ofInt(lp.x, targetX)
        animator.duration = 200
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { anim ->
            lp.x = anim.animatedValue as Int
            try { wm?.updateViewLayout(root, lp) } catch (_: Exception) {}
        }
        animator.start()
    }

    // ── 상태 업데이트 ─────────────────────────────
    private fun updatePlayIcon() {
        playIcon?.post { playIcon?.text = if (isPlaying) "⏸" else "▶" }
    }

    private fun updatePanelTitle() {
        val tv = panelView?.tag as? TextView ?: return
        tv.post { tv.text = if (trackTitle.isNotBlank()) trackTitle else "X-WARE" }
    }

    // ── JS 명령 전송 ──────────────────────────────
    private fun sendOverlayCmd(jsCode: String) {
        sendBroadcast(Intent("com.xware.OVERLAY_CONTROL").apply {
            putExtra("js", jsCode)
            setPackage(packageName)
        })
    }

    // ── 버블 제거 ─────────────────────────────────
    private fun removeBubble() {
        try { bubbleRoot?.let { wm?.removeView(it) } } catch (_: Exception) {}
        bubbleRoot = null
    }

    // ── 알림 채널 (표시용 아님, startForeground 없이도 필요시 사용) ──
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
