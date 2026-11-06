package com.dawnmoon.music_download.core

import android.os.SystemClock

/** 时间来源；测试中可注入固定实现，避免依赖真实时间。 */
interface AppClock {
    /** 墙上时钟时间，用于任务的创建、更新与完成时间。 */
    fun nowMillis(): Long

    /** 单调递增时间，用于限速与超时计算，不受系统时间调整影响。 */
    fun elapsedRealtime(): Long
}

/** 生产环境实现的 [AppClock]。 */
class SystemAppClock : AppClock {
    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
}
