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

/**
 * LyricsOverlayService — 플로팅 버블 오버레이
 * ────────────────────────────────────────────────────────────
 * 작고 둥근 버블 아이콘이 화면 위에 떠 있습니다.
 *
 * 동작:
 *  - 버블 드래그 → 화면 어디서나 이동
 *  - 버블 탭 → 컨트롤 패널 펼치기/접기
 *  - 컨트롤: ⏮ ⏯ ⏭ + 오버레이 종료(✕)
 *  - 브로드캐스트 → MainActivity → WebView JS 실행
 */
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

    // 버블 뷰
    private var bubbleRoot: FrameLayout? = null
    private var bubbleBtn: FrameLayout?  = null
    private var playIcon: TextView?      = null
    private var panelView: LinearLayout? = null

    private var isPlaying = false
    private var trackTitle = ""
    private var isExpanded = false

    private val BUBBLE_SIZE_DP = 60
    private val CORNER_RADIUS_DP = 30  // 원형
    private val PANEL_W_DP = 220

    // ── 드래그 상태 ────────────────────────────────
    private var initialX = 0; private var initialY = 0
    private var initialTouchX = 0f; private var initialTouchY = 0f
    private var isDragging = false
    private val DRAG_THRESHOLD = 10

    // ── 생명주기 ───────────────────────────────────
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotifChannel()
        startForeground(NOTIF_ID, buildNotif())
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

    // ══════════════════════════════════════════════
    //  버블 생성
    // ══════════════════════════════════════════════
    private fun createBubble() {
        val bubSizePx  = dp(BUBBLE_SIZE_DP)
        val cornerPx   = dp(CORNER_RADIUS_DP).toFloat()
        val screenW    = resources.displayMetrics.widthPixels
        val screenH    = resources.displayMetrics.heightPixels

        // ── 버블 버튼 뷰 ──
        val bubble = FrameLayout(this)
        bubbleBtn  = bubble

        // 배경: 둥근 원, 반투명 다크 + 액센트 테두리
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#E8060610"))
            setStroke(dp(2), Color.parseColor("#fa2d5a"))
        }
        bubble.background = bgDrawable
        bubble.elevation  = dp(8).toFloat()

        // 재생/정지 아이콘 (텍스트)
        val icon = TextView(this).apply {
            text      = "▶"
            textSize  = 20f
            setTextColor(Color.WHITE)
            gravity   = Gravity.CENTER
            typeface  = Typeface.DEFAULT_BOLD
        }
        playIcon = icon
        bubble.addView(icon, FrameLayout.LayoutParams(-1, -1))

        // ── 컨트롤 패널 (버블 옆에 표시) ──
        val panel = buildPanel()
        panelView = panel
        panel.visibility = View.GONE

        // ── 루트 컨테이너 ──
        val root = FrameLayout(this)
        bubbleRoot = root
        root.addView(bubble, FrameLayout.LayoutParams(bubSizePx, bubSizePx))
        root.addView(panel,  FrameLayout.LayoutParams(dp(PANEL_W_DP), -2).apply {
            marginStart = bubSizePx + dp(8)
        })

        // ── WindowManager 파라미터 ──
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            bubSizePx + dp(PANEL_W_DP) + dp(8),  // 패널까지 포함한 폭
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - bubSizePx - dp(16)   // 우측 끝
            y = screenH / 3
        }

        // ── 터치 이벤트: 드래그 + 탭 ──
        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var lastParams: WindowManager.LayoutParams? = null

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val lp = wm?.let {
                    (root.layoutParams as? WindowManager.LayoutParams) ?: params
                } ?: params

                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX      = lp.x;    initialY = lp.y
                        initialTouchX = e.rawX;  initialTouchY = e.rawY
                        isDragging    = false
                        lastParams    = lp
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - initialTouchX).toInt()
                        val dy = (e.rawY - initialTouchY).toInt()
                        if (!isDragging && (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD)) {
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
                        if (!isDragging) {
                            togglePanel(lp)
                        } else {
                            snapToEdge(lp)
                        }
                    }
                }
                return true
            }
        })

        wm?.addView(root, params)
    }

    // ── 컨트롤 패널 빌드 ──────────────────────────
    private fun buildPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            elevation = dp(12).toFloat()
        }

        // 배경: 둥근 사각형
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#F0060610"))
            setStroke(dp(1), Color.parseColor("#33ffffff"))
        }
        panel.background = bg

        // 트랙 타이틀
        val titleTv = TextView(this).apply {
            text      = "X-WARE"
            textSize  = 12f
            setTextColor(Color.parseColor("#ccffffff"))
            typeface  = Typeface.DEFAULT_BOLD
            maxLines  = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity   = Gravity.CENTER
        }
        panel.addView(titleTv, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(10)
        })
        // 나중에 참조용 태그 저장
        titleTv.tag = "trackTitle"
        panel.tag = titleTv  // panel.tag 에 titleTv 저장

        // 컨트롤 버튼 행
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER
        }

        // ⏮ 이전곡
        row.addView(makeCtrlBtn("⏮") {
            sendOverlayCmd("prevT()")
        })
        // ⏯ 재생/정지
        val ppBtn = makeCtrlBtn("⏯") { sendOverlayCmd("togglePlay()") }
        ppBtn.tag = "playBtn"
        row.addView(ppBtn)
        // ⏭ 다음곡
        row.addView(makeCtrlBtn("⏭") {
            sendOverlayCmd("nextT()")
        })

        panel.addView(row, LinearLayout.LayoutParams(-1, -2))

        // ✕ 오버레이 종료 버튼
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
            // ripple 효과
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                foreground = resources.getDrawable(
                    android.R.drawable.list_selector_background, null)
            }
        }
    }

    // ── 패널 펼치기/접기 ─────────────────────────
    private fun togglePanel(lp: WindowManager.LayoutParams) {
        if (isExpanded) collapsePanel() else expandPanel(lp)
    }

    private fun expandPanel(lp: WindowManager.LayoutParams) {
        isExpanded = true
        panelView?.apply {
            visibility = View.VISIBLE
            alpha = 0f; scaleX = 0.85f; scaleY = 0.85f
            animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        }
        // 패널이 화면 왼쪽에 충분한 공간 없으면 버블 반대편으로
        val screenW = resources.displayMetrics.widthPixels
        val bubSz   = dp(BUBBLE_SIZE_DP)
        val panW    = dp(PANEL_W_DP) + dp(8)
        if (lp.x + bubSz + panW > screenW) {
            // 버블이 오른쪽에 있음 → 패널을 버블 왼쪽에 표시
            val root = bubbleRoot ?: return
            val rlp  = root.layoutParams as? WindowManager.LayoutParams ?: return
            rlp.x = maxOf(0, lp.x - panW)
            panelView?.layoutParams = (panelView?.layoutParams as? LinearLayout.LayoutParams)?.apply {
                marginStart = 0
                marginEnd   = bubSz + dp(8)
            }
        }
    }

    private fun collapsePanel() {
        isExpanded = false
        panelView?.animate()?.alpha(0f)?.scaleX(0.85f)?.scaleY(0.85f)
            ?.setDuration(140)?.withEndAction { panelView?.visibility = View.GONE }?.start()
    }

    // ── 화면 끝으로 스냅 ────────────────────────
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

    // ── 아이콘/타이틀 업데이트 ─────────────────
    private fun updatePlayIcon() {
        playIcon?.post {
            playIcon?.text = if (isPlaying) "⏸" else "▶"
        }
    }

    private fun updatePanelTitle() {
        val panel = panelView ?: return
        val tv    = panel.tag as? TextView ?: return
        tv.post { tv.text = if (trackTitle.isNotBlank()) trackTitle else "X-WARE" }
    }

    // ── MainActivity 에 JS 실행 명령 전송 ───────
    private fun sendOverlayCmd(jsCode: String) {
        Log.d(TAG, "오버레이 명령: $jsCode")
        sendBroadcast(Intent("com.xware.OVERLAY_CONTROL").apply {
            putExtra("js", jsCode)
            setPackage(packageName)
        })
    }

    // ── 버블 제거 ────────────────────────────────
    private fun removeBubble() {
        try { bubbleRoot?.let { wm?.removeView(it) } } catch (_: Exception) {}
        bubbleRoot = null
    }

    // ── 알림 채널 ────────────────────────────────
    private fun createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, "X-WARE 오버레이",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) })
        }
    }

    private fun buildNotif(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("X-WARE 오버레이")
            .setContentText("버블을 탭해 컨트롤")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
