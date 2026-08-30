package kr.neptune.linksaver.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kr.neptune.linksaver.MainActivity

object Notifications {

    const val CHANNEL_PROGRESS = "download_progress"
    const val CHANNEL_RESULT = "download_result"

    const val FOREGROUND_ID = 1001
    private const val RESULT_ID_BASE = 2000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                "다운로드 진행",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "다운로드 진행 상황을 표시합니다"
                setShowBadge(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                "다운로드 결과",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "다운로드 완료 / 실패 알림"
            }
        )
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(context, 0, intent, flags)
    }

    fun progress(
        context: Context,
        title: String,
        text: String,
        percent: Int,
        indeterminate: Boolean
    ): Notification =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
            .setContentIntent(contentIntent(context))
            .build()

    fun showResult(context: Context, key: Int, title: String, text: String, success: Boolean) {
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(RESULT_ID_BASE + (key and 0xFFF), notification)
        }
    }
}
