package com.dawnmoon.music_download.download

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dawnmoon.music_download.ui.MainActivity
import java.util.Locale

/**
 * 下载汇总通知的发布器：负责渠道创建、通知构造、发布与取消。
 *
 * 单条通知概括全部会话进度，点击打开 [MainActivity]，action 通过
 * [DownloadServiceActions] 常量发往前台服务。
 *
 * @param context 用于获取通知服务与构造 PendingIntent 的上下文。
 */
class DownloadNotifications(private val context: Context) : DownloadNotifier {

    /** 创建或更新下载渠道；重复调用幂等。 */
    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = CHANNEL_DESCRIPTION
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** 构造汇总通知但不发布。 */
    fun build(snapshot: EngineSnapshot, paused: Boolean): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载 ${snapshot.activeCount} 首")
            .setContentText(bodyText(snapshot))
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val total = snapshot.bytesTotal
        if (total != null && total > 0L) {
            builder.setProgress(PROGRESS_MAX, progressOf(snapshot.bytesDone, total), false)
        } else {
            builder.setProgress(0, 0, true)
        }

        builder.addAction(
            0,
            if (paused) ACTION_RESUME else ACTION_PAUSE,
            servicePendingIntent(
                if (paused) DownloadServiceActions.ACTION_RESUME_ALL
                else DownloadServiceActions.ACTION_PAUSE_ALL,
                if (paused) REQUEST_RESUME_ALL else REQUEST_PAUSE_ALL,
            ),
        )

        return builder.build()
    }

    @SuppressLint("MissingPermission")
    override fun update(snapshot: EngineSnapshot, paused: Boolean) {
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(snapshot, paused))
    }

    override fun clear() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun bodyText(snapshot: EngineSnapshot): String {
        val progress = "已完成 ${snapshot.completedInSession}/${snapshot.totalInSession}"
        return "$progress · ${formatSpeed(snapshot.speedBytesPerSecond)}"
    }

    /** 将字节进度映射到 0..[PROGRESS_MAX]，避免大文件超出 Int 上限。 */
    private fun progressOf(bytesDone: Long, bytesTotal: Long): Int {
        val scaled = bytesDone.toDouble() / bytesTotal.toDouble() * PROGRESS_MAX
        return scaled.toInt().coerceIn(0, PROGRESS_MAX)
    }

    private fun formatSpeed(bytesPerSecond: Long): String = when {
        bytesPerSecond >= BYTES_PER_MB ->
            String.format(Locale.US, "%.1f MB/s", bytesPerSecond.toDouble() / BYTES_PER_MB)
        bytesPerSecond >= BYTES_PER_KB ->
            String.format(Locale.US, "%.1f KB/s", bytesPerSecond.toDouble() / BYTES_PER_KB)
        else -> "$bytesPerSecond B/s"
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 构造发往下载服务的显式 Intent。
     *
     * 必须用 [Intent.setClassName] 指定组件：服务只以 exported=false 声明、没有
     * intent-filter，隐式 Intent 无法解析，PendingIntent 发送时会静默丢弃。
     *
     * @param action [DownloadServiceActions] 中定义的控制 action。
     */
    internal fun serviceIntent(action: String): Intent = Intent(action)
        .setClassName(context.packageName, DOWNLOAD_SERVICE_CLASS_NAME)

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            context,
            requestCode,
            serviceIntent(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val CHANNEL_ID = "downloads"
        const val CHANNEL_NAME = "下载"
        const val CHANNEL_DESCRIPTION = "后台下载进度"

        const val NOTIFICATION_ID = 1

        const val PROGRESS_MAX = 1_000
        const val BYTES_PER_KB = 1_024.0
        const val BYTES_PER_MB = 1_024.0 * 1_024.0

        const val ACTION_PAUSE = "全部暂停"
        const val ACTION_RESUME = "全部继续"

        const val REQUEST_OPEN_APP = 1
        const val REQUEST_PAUSE_ALL = 2
        const val REQUEST_RESUME_ALL = 3

        /**
         * 后续 Task 才会创建该服务；此处以类名引用，避免本 Task 提前依赖其实现。
         * 包名必须与 DownloadService.kt 的实际位置保持一致。
         */
        const val DOWNLOAD_SERVICE_CLASS_NAME =
            "com.dawnmoon.music_download.download.DownloadService"
    }
}
