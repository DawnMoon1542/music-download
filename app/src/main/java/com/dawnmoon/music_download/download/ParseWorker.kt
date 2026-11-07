package com.dawnmoon.music_download.download

import com.dawnmoon.music_download.data.remote.ApiFailure
import com.dawnmoon.music_download.data.remote.ApiResponse
import com.dawnmoon.music_download.data.repository.AppSettings
import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.TrackResolver
import com.dawnmoon.music_download.domain.model.FailureKind
import com.dawnmoon.music_download.domain.model.TaskStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 单个任务的解析结局；引擎据此决定是否投递下载或暂停整个解析队列。 */
enum class ParseOutcome {
    /** 任务不存在或已不处于 Queued，未产生任何写入。 */
    Ignored,

    /** 解析成功，任务已迁移到 Waiting。 */
    Resolved,

    /** 该任务自身失败，解析队列继续处理后续任务。 */
    Failed,

    /** Key/额度/权限级失败，解析队列必须暂停等待用户处理。 */
    KeyLevelFailure,
}

/**
 * 消费解析队列，把 Queued 任务串行解析为 Waiting。
 *
 * 解析器与 API Key 都由外部注入；[run] 从 [ReceiveChannel] 取任务，串行执行并等待单个解析作业
 * 结束。解析作业由本类持有而不是引擎的下载作业表，避免“解析作业仍在表中”导致下载池把交接
 * 误判为重复投递。
 *
 * 引擎通过 [isPaused] / [onKeyLevelFailure] 控制队列暂停，通过 [onResolved] 接收交接信号，
 * 通过 [onTaskFinished] 在每个任务结束后重新评估空闲。取消（暂停或取消任务）时在
 * [NonCancellable] 中把任务落库，避免收尾中的挂起点再次抛出取消。
 */
class ParseWorker(
    private val repo: TaskRepository,
    private val resolver: TrackResolver,
    private val settings: AppSettings,
    @Suppress("UNUSED_PARAMETER") notifications: DownloadNotifier,
) {

    /** 队列是否已被 Key/额度级失败暂停；引擎在用户恢复操作时清除。 */
    var isPaused: () -> Boolean = { false }

    /** Key/额度/权限级失败通知；引擎据此置位全局暂停标志。 */
    var onKeyLevelFailure: () -> Unit = {}

    /** 解析成功后的交接动作；引擎注入下载队列。 */
    var onResolved: suspend (Long) -> Unit = {}

    /** 每个任务结束（成功、失败或忽略）后调用，供引擎重新评估空闲与快照。 */
    var onTaskFinished: () -> Unit = {}

    private val lock = Any()
    private var activeJob: Job? = null
    private var activeTaskId: Long? = null

    /** 当前正在解析的任务；没有解析作业时为 null。 */
    val currentTaskId: Long?
        get() = synchronized(lock) { activeTaskId }

    /** 消费解析队列，直到通道关闭或所在作用域被取消。 */
    suspend fun run(queue: ReceiveChannel<Long>) {
        for (taskId in queue) {
            if (isPaused()) {
                // 暂停期间丢弃的队列项也要触发空闲检查，否则服务可能永远不进入空闲。
                onTaskFinished()
                continue
            }
            coroutineScope {
                val job = launch { parseTask(taskId) }
                synchronized(lock) {
                    activeTaskId = taskId
                    activeJob = job
                }
                job.join()
                synchronized(lock) {
                    if (activeJob === job) {
                        activeJob = null
                        activeTaskId = null
                    }
                }
            }
            onTaskFinished()
        }
    }

    /**
     * 取消当前解析任务并等待收尾；返回是否命中了正在解析的任务。
     *
     * 作业尚未真正开始时取消也不会让任务卡在 Queued：调用方（引擎）会依据任务状态补写
     * Paused/Cancelled。
     */
    suspend fun cancelActive(signal: CancellationSignal): Boolean {
        val job = synchronized(lock) { activeJob } ?: return false
        job.cancel(signal)
        job.join()
        return true
    }

    /** 只发出取消、不等待；供 `detach` 在释放作用域前同步调用。 */
    fun cancelActiveNow(signal: CancellationSignal) {
        synchronized(lock) { activeJob }?.cancel(signal)
    }

    private suspend fun parseTask(taskId: Long) {
        when (parse(taskId)) {
            ParseOutcome.Resolved -> onResolved(taskId)
            ParseOutcome.KeyLevelFailure -> onKeyLevelFailure()
            ParseOutcome.Failed, ParseOutcome.Ignored -> Unit
        }
    }

    private suspend fun parse(taskId: Long): ParseOutcome {
        val task = repo.byId(taskId) ?: return ParseOutcome.Ignored
        if (task.status != TaskStatus.Queued) return ParseOutcome.Ignored

        val songId = task.songId
        if (songId == null) {
            repo.markFailed(taskId, FailureKind.Malformed, "任务缺少歌曲 ID")
            return ParseOutcome.Failed
        }

        try {
            val apiKey = settings.apiKey.first()
            if (apiKey.isNullOrBlank()) {
                repo.markFailed(taskId, FailureKind.InvalidKey, "缺少 API Key")
                return ParseOutcome.KeyLevelFailure
            }

            repo.markResolving(taskId)
            return when (val response = resolver.resolve(songId, task.requestedQuality, apiKey)) {
                is ApiResponse.Success -> {
                    repo.completeResolve(taskId, response.value)
                    ParseOutcome.Resolved
                }

                is ApiResponse.Failure -> {
                    repo.markFailed(taskId, response.failure.toFailureKind(), response.failure.message)
                    if (response.failure.isKeyLevel()) {
                        ParseOutcome.KeyLevelFailure
                    } else {
                        ParseOutcome.Failed
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                when (cancelled as? CancellationSignal) {
                    CancellationSignal.Cancel -> repo.markCancelled(taskId)
                    else -> repo.markPaused(taskId)
                }
            }
            throw cancelled
        }
    }

    private fun ApiFailure.isKeyLevel(): Boolean =
        this is ApiFailure.InvalidKey ||
            this is ApiFailure.QuotaExhausted ||
            this is ApiFailure.Forbidden

    private fun ApiFailure.toFailureKind(): FailureKind = when (this) {
        is ApiFailure.InvalidKey -> FailureKind.InvalidKey
        is ApiFailure.QuotaExhausted -> FailureKind.QuotaExhausted
        is ApiFailure.Forbidden -> FailureKind.Forbidden
        is ApiFailure.RateLimited -> FailureKind.RateLimited
        is ApiFailure.InvalidRequest -> FailureKind.InvalidRequest
        is ApiFailure.QualityUnavailable -> FailureKind.QualityUnavailable
        is ApiFailure.NotFound -> FailureKind.NotFound
        is ApiFailure.ServiceUnavailable -> FailureKind.ServiceUnavailable
        is ApiFailure.Network -> FailureKind.Network
        is ApiFailure.Malformed -> FailureKind.Malformed
        is ApiFailure.LinkUnsupported -> FailureKind.Unknown
    }
}
