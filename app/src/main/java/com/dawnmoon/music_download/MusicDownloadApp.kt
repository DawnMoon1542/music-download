package com.dawnmoon.music_download

import android.app.Application
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口：持有进程级依赖容器，并在启动时把未完成任务恢复为暂停状态。
 *
 * 恢复只依赖数据库，不启动下载服务；用户可通过“全部继续”或新任务重新开始。
 */
class MusicDownloadApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 供仪器测试读取或替换依赖；生产路径由 [onCreate] 注入 [AppContainer]。 */
    @VisibleForTesting
    internal lateinit var dependencies: AppDependencies

    override fun onCreate() {
        super.onCreate()
        val container = AppContainer(this)
        dependencies = container
        // Room 首次访问会打开数据库，恢复动作不能占用主线程。
        applicationScope.launch { container.markUnfinishedAsPaused() }
    }
}
