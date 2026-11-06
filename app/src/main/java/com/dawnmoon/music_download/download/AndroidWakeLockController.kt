package com.dawnmoon.music_download.download

import android.content.Context
import android.os.PowerManager

/**
 * 基于 [PowerManager.PARTIAL_WAKE_LOCK] 的引用计数 WakeLock 控制器。
 *
 * 引擎在活动下载数从 0 变为 1 时 [acquire]，回到 0 或 detach 时 [releaseAll]；
 * 引用计数保证并发任务增减不会提前释放锁。
 *
 * @param context 用于获取系统服务的上下文。
 */
class AndroidWakeLockController(context: Context) : WakeLockController {

    private val wakeLock: PowerManager.WakeLock =
        context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)

    /** 当前引用计数；internal 供仪器测试观察。 */
    internal var refCount: Int = 0
        private set

    /** 底层锁当前是否被持有；internal 供仪器测试观察。 */
    internal val isHeld: Boolean
        get() = wakeLock.isHeld

    @Synchronized
    override fun acquire() {
        refCount++
        if (refCount == 1) {
            wakeLock.acquire()
        }
    }

    @Synchronized
    override fun release() {
        if (refCount == 0) return
        refCount--
        if (refCount == 0) {
            releaseLock()
        }
    }

    @Synchronized
    override fun releaseAll() {
        refCount = 0
        releaseLock()
    }

    private fun releaseLock() {
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    private companion object {
        const val WAKE_LOCK_TAG = "music_download:download"
    }
}
