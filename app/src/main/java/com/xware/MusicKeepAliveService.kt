package com.xware

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * MusicKeepAliveService
 * ──────────────────────────────────────────────────────────
 * 백그라운드에서 음악이 계속 재생되도록 앱 프로세스를 유지하는
 * Foreground Service 입니다.
 *
 * Android 는 백그라운드 앱을 종료할 수 있기 때문에
 * Foreground Service 없이는 음악이 끊길 수 있습니다.
 * 이 서비스는 상태바에 최소한의 알림을 표시하여 앱이
 * 백그라운드에서도 계속 실행되게 합니다.
 */
class MusicKeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID          = "xware_music"
        const val NOTIF_ID            = 1001
        const val ACTION_UPDATE_TITLE = "com.xware.ACTION_UPDATE_TITLE"
    }

    private var currentTitle = "재생 중..."

    private val titleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_TITLE) {
                currentTitle = intent.getStringExtra("title") ?: "재생 중..."
                updateNotification(currentTitle)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("X-WARE", "음악 재생 중"))

        val filter = IntentFilter(ACTION_UPDATE_TITLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(titleReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(titleReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(titleReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun updateNotification(title: String) {
        val notifManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifManager.notify(NOTIF_ID, buildNotification(title, "재생 중 — X-WARE"))
    }

    private fun buildNotification(title: String, subtitle: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "X-WARE 음악 재생",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                description = "백그라운드 음악 재생 알림"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
