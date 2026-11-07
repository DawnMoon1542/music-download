package com.dawnmoon.music_download.download

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * 启动前台服务的副作用，从引擎中解耦出来。
 *
 * 真实实现由 Task 3-3 注入（`DownloadService.start`）；测试替换为 Fake，避免引擎直接依赖
 * Android 组件。引擎通过 [DownloadController.start] 幂等地触发它。
 */
fun interface DownloadServiceStarter {
    fun start(context: Context)
}

/**
 * 下载引擎对界面暴露的控制入口。
 *
 * 除 [attach] 外所有操作都是非挂起的：引擎在 `attach` 的作用域内串行化状态写入，调用方只需
 * 触发意图。`attach` 之前发起的操作会被缓冲，服务就绪后按顺序重放，避免冷启动时任务停留在
 * Queued。
 */
interface DownloadController {

    /** 当前下载会话的可见状态。 */
    val snapshot: StateFlow<EngineSnapshot>

    /**
     * 在给定作用域内启动队列消费；重复调用是幂等的。
     *
     * 前台服务在 `onCreate` 中调用，使引擎作业与服务的生命周期绑定。该方法是契约的一部分，
     * 服务只能通过本接口操作引擎，不得依赖具体实现类型。
     */
    suspend fun attach(scope: CoroutineScope)

    /** 取消全部引擎作业并释放 WakeLock；未 attach 时为空操作。 */
    fun detach()

    /** 幂等启动承接引擎的前台服务。 */
    fun start(context: Context)

    /** 把任务投递到解析或下载队列；未知任务 ID 会被忽略。 */
    fun requestDownload(ids: List<Long>)

    /** 暂停单个任务；任务正在执行时取消其 Job 并等待收尾。 */
    fun pause(id: Long)

    /** 继续一个已暂停的任务，按是否已解析回到解析或下载队列。 */
    fun resume(id: Long)

    /** 取消单个任务；没有运行中 Job 的任务由引擎删除 pending 文件。 */
    fun cancel(id: Long)

    /** 重新解析并下载一个失败任务。 */
    fun retry(id: Long)

    /** 暂停全部未完成任务并阻止新下载启动。 */
    fun pauseAll()

    /** 清除暂停标志并继续全部已暂停任务。 */
    fun resumeAll()

    /** 取消全部未完成任务并清理 pending 文件。 */
    fun cancelAll()

    /** 重新排队全部失败任务。 */
    fun retryAllFailed()
}

/** 空闲自停回调；前台服务注入真实实现，测试替换为 Fake。 */
fun interface ServiceStopper {
    fun stop()
}
