package com.dawnmoon.music_download.download

/** 引用计数式 WakeLock 控制器；引擎按活动任务数增减引用。 */
interface WakeLockController {
    /** 增加一次引用，首次引用时获取锁。 */
    fun acquire()

    /** 释放一次引用，最后一次引用释放时归还锁。 */
    fun release()

    /** 无条件释放全部引用与锁，供服务 detach 收尾使用。 */
    fun releaseAll()
}
