package com.dawnmoon.music_download.download

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.dawnmoon.music_download.MusicDownloadApp
import kotlinx.coroutines.launch

/**
 * 下载前台服务：承载 [DownloadController] 的生命周期，并以 `dataSync` 类型保持进程存活。
 *
 * 服务只负责前台状态与 action 分发，实际调度都在引擎内完成：创建时 `attach` 引擎开始消费
 * 队列，销毁时 `detach` 取消作业并释放 WakeLock。空闲时由引擎回调 [stop] 自行退出。
 *
 * 依赖只使用 [DownloadController] 契约，不关心其背后是 [DownloadEngine] 还是测试替身。
 */
class DownloadService : LifecycleService() {

    private lateinit var notifications: DownloadNotifications
    private lateinit var engine: DownloadController

    override fun onCreate() {
        super.onCreate()
        notifications = DownloadNotifications(this)
        notifications.ensureChannel()
        engine = (application as MusicDownloadApp).dependencies.downloadController

        // 必须先在 onCreate 内进入前台：引擎 attach 之后才调用会触发 ANR/崩溃。
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            foregroundNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        lifecycleScope.launch { engine.attach(this) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            DownloadServiceActions.ACTION_PAUSE_ALL -> engine.pauseAll()
            DownloadServiceActions.ACTION_RESUME_ALL -> engine.resumeAll()
        }
        // 进程被回收后不自动重建：任务恢复为 Paused，等待用户显式继续。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine.detach()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notifications.clear()
        super.onDestroy()
    }

    private fun foregroundNotification(): Notification {
        val snapshot = engine.snapshot.value
        return notifications.build(snapshot, snapshot.isPaused)
    }

    companion object {
        /**
         * 与 [DownloadNotifications] 的汇总通知使用同一 ID。
         *
         * 前台通知和引擎的进度更新必须落在同一条通知上，否则测试与用户会看到两条通知。
         */
        private const val NOTIFICATION_ID = 1

        /** 幂等启动前台服务；已在运行时仅触发 [onStartCommand]。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java),
            )
        }

        /** 停止前台服务；未运行时为空操作。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }
    }
}
