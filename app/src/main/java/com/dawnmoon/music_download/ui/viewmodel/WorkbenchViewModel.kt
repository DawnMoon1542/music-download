package com.dawnmoon.music_download.ui.viewmodel

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dawnmoon.music_download.R
import com.dawnmoon.music_download.core.AppClock
import com.dawnmoon.music_download.data.repository.AppSettings
import com.dawnmoon.music_download.data.repository.EnqueueOutcome
import com.dawnmoon.music_download.data.repository.QuotaStore
import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.model.DownloadTask
import com.dawnmoon.music_download.domain.model.FailureKind
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.Quota
import com.dawnmoon.music_download.domain.model.TaskStatus
import com.dawnmoon.music_download.download.DownloadController
import com.dawnmoon.music_download.download.EngineSnapshot
import com.dawnmoon.music_download.download.TaskFileStore
import com.dawnmoon.music_download.ui.common.FileInfoFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 工作台的状态编排。
 *
 * 任务列表来自仓库 Flow，额度与引擎状态直接取各自的数据源；ViewModel 自身只保存
 * 输入、当前选择与入队中的瞬态。速度不依赖引擎额外接口，而是按 1 秒窗口对同一任务
 * 的字节增量做差分，暂停或进度回退时归零。
 */
class WorkbenchViewModel(
    private val repo: TaskRepository,
    private val settings: AppSettings,
    private val engine: DownloadController,
    @Suppress("unused") private val fileStore: TaskFileStore,
    private val quotaStore: QuotaStore,
    private val appContext: Context,
    private val clock: AppClock,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : ViewModel() {

    private val input = MutableStateFlow("")

    /** 用户在当前页面选中的音质；null 表示跟随设置中的默认值。 */
    private val qualityOverride = MutableStateFlow<MusicQuality?>(null)

    private val selectedTab = MutableStateFlow(WorkbenchTab.InProgress)
    private val historyQuery = MutableStateFlow("")
    private val isEnqueuing = MutableStateFlow(false)

    /** 只包含本轮有效的下载速度；不存在或回退期间任务不会出现在此表。 */
    private val speeds = MutableStateFlow<Map<Long, Long>>(emptyMap())

    private val eventChannel = Channel<WorkbenchEvent>(Channel.BUFFERED)

    /** 一次性事件；界面应在进入组合时开始收集。 */
    val events: Flow<WorkbenchEvent> = eventChannel.receiveAsFlow()

    /** 供速度差分读取的最新进行中任务，由单独的收集器维护。 */
    private var latestInProgress: List<DownloadTask> = emptyList()
    private val previousSamples = mutableMapOf<Long, ByteSample>()

    /** 下一次速度采样的截止时间；只允许一个待执行的采样作业。 */
    private var lastSpeedSampleAt: Long = clock.elapsedRealtime()
    private var speedSampleJob: Job? = null

    private val taskLists = combine(
        repo.observeInProgress(),
        repo.observeCompleted(),
        quotaStore.quota,
        engine.snapshot,
    ) { inProgress, completed, quota, snapshot ->
        TaskLists(inProgress, completed, quota, snapshot)
    }

    private val localState = combine(
        input,
        selectedTab,
        historyQuery,
        isEnqueuing,
        qualityOverride,
    ) { input, tab, query, enqueuing, override ->
        LocalState(input, tab, query, enqueuing, override)
    }

    private val localStateWithQuality = combine(
        localState,
        settings.defaultQuality,
    ) { local, defaultQuality ->
        local.copy(defaultQuality = defaultQuality)
    }

    val state: StateFlow<WorkbenchUiState> = combine(
        taskLists,
        localStateWithQuality,
        speeds,
    ) { tasks, local, speedByTask ->
        val quality = local.qualityOverride ?: local.defaultQuality
        WorkbenchUiState(
            input = local.input,
            selectedQuality = quality,
            quota = tasks.quota,
            inProgress = tasks.inProgress.map { it.toUiModel(speedByTask) },
            completed = tasks.completed
                .filter { it.matchesQuery(local.historyQuery) }
                .map { it.toUiModel(emptyMap()) },
            selectedTab = local.selectedTab,
            historyQuery = local.historyQuery,
            isEnqueuing = local.isEnqueuing,
            engine = tasks.engine,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = WorkbenchUiState(
            input = "",
            selectedQuality = MusicQuality.default,
            quota = null,
            inProgress = emptyList(),
            completed = emptyList(),
            selectedTab = WorkbenchTab.InProgress,
            historyQuery = "",
            isEnqueuing = false,
            engine = null,
        ),
    )

    init {
        viewModelScope.launch {
            repo.observeInProgress().collect { tasks ->
                latestInProgress = tasks
                scheduleSpeedSample()
            }
        }
    }

    fun onInputChange(value: String) {
        input.value = value
    }

    fun onQualityChange(quality: MusicQuality) {
        qualityOverride.value = quality
    }

    fun onTabChange(tab: WorkbenchTab) {
        selectedTab.value = tab
    }

    fun onHistoryQueryChange(query: String) {
        historyQuery.value = query
    }

    /**
     * 提交入队。
     *
     * 是否缺少 Key 完全由仓库判断：界面不读取 Key，也不做本地预判，
     * 避免设置页与工作台对 Key 状态的判断不一致。
     */
    fun onEnqueue() {
        if (isEnqueuing.value) return
        isEnqueuing.value = true
        viewModelScope.launch {
            try {
                val outcome = repo.enqueue(input.value, state.value.selectedQuality)
                when (outcome) {
                    is EnqueueOutcome.MissingApiKey -> {
                        eventChannel.trySend(WorkbenchEvent.OpenSettings(focusKey = true))
                    }

                    is EnqueueOutcome.NoLinks -> {
                        eventChannel.trySend(WorkbenchEvent.Snackbar(text(R.string.enqueue_no_links)))
                    }

                    is EnqueueOutcome.Enqueued -> {
                        handleEnqueued(outcome)
                    }
                }
            } finally {
                isEnqueuing.value = false
            }
        }
    }

    fun onPause(id: Long) {
        engine.pause(id)
    }

    fun onResume(id: Long) {
        engine.resume(id)
    }

    fun onCancel(id: Long) {
        engine.cancel(id)
    }

    fun onRetry(id: Long) {
        engine.retry(id)
    }

    fun onPauseAll() {
        engine.pauseAll()
    }

    fun onResumeAll() {
        engine.resumeAll()
    }

    fun onCancelAll() {
        engine.cancelAll()
    }

    fun onRetryAllFailed() {
        engine.retryAllFailed()
    }

    private suspend fun handleEnqueued(outcome: EnqueueOutcome.Enqueued) {
        if (outcome.created.isNotEmpty()) {
            engine.start(appContext)
            engine.requestDownload(outcome.created)
            input.value = ""
            selectedTab.value = WorkbenchTab.InProgress
            requestNotificationPermissionIfNeeded()
        }

        val parts = buildList {
            if (outcome.created.isNotEmpty()) {
                add(text(R.string.enqueue_success, outcome.created.size))
            }
            if (outcome.duplicates > 0) {
                add(text(R.string.enqueue_skipped, outcome.duplicates))
            }
            if (outcome.failures.isNotEmpty()) {
                add(text(R.string.enqueue_unrecognized, outcome.failures.size))
            }
        }
        val message = parts.ifEmpty { listOf(text(R.string.enqueue_nothing)) }.joinToString("；")
        eventChannel.trySend(WorkbenchEvent.Snackbar(message))
    }

    /**
     * 在下载已经启动后请求一次通知权限。
     *
     * 事件发出时立即写入"已询问"标记：权限弹窗的结果不影响下载，也不等待用户选择，
     * 因此不能把标记推迟到结果回调，否则重复入队会再次弹窗。
     */
    private suspend fun requestNotificationPermissionIfNeeded() {
        if (sdkInt < Build.VERSION_CODES.TIRAMISU) return
        if (settings.notificationAsked.first()) return
        settings.markNotificationAsked()
        eventChannel.trySend(WorkbenchEvent.RequestNotificationPermission)
    }

    /**
     * 安排一次 1 秒窗口的速度采样。
     *
     * 同一时间只保留一个待执行作业，且作业执行后不会自我重排；新的进度到达时才会
     * 触发下一轮。这样既维持约 1 秒的采样窗口，也不会留下永远运行的定时循环。
     */
    private fun scheduleSpeedSample() {
        if (speedSampleJob?.isActive == true) return
        val waitMillis = (lastSpeedSampleAt + SPEED_SAMPLE_INTERVAL_MILLIS -
            clock.elapsedRealtime()).coerceAtLeast(1L)
        speedSampleJob = viewModelScope.launch {
            delay(waitMillis)
            refreshSpeeds()
            lastSpeedSampleAt = clock.elapsedRealtime()
        }
    }

    /**
     * 以 1 秒为窗口刷新速度。
     *
     * 第一次看到的下载任务只记录基线；暂停、完成或从进行中列表消失时删除样本，
     * 进度回退（重连后重新计数）不产生负速度，并重置基线。
     */
    private fun refreshSpeeds() {
        val now = clock.elapsedRealtime()
        val activeIds = latestInProgress.mapTo(mutableSetOf()) { it.id }
        previousSamples.keys.retainAll(activeIds)

        val nextSpeeds = mutableMapOf<Long, Long>()
        latestInProgress.forEach { task ->
            if (task.status != TaskStatus.Downloading) {
                previousSamples.remove(task.id)
                return@forEach
            }

            val previous = previousSamples[task.id]
            if (previous != null && now > previous.elapsedMillis) {
                val delta = task.bytesDownloaded - previous.bytesDownloaded
                if (delta > 0L) {
                    val elapsed = now - previous.elapsedMillis
                    nextSpeeds[task.id] = (delta.toDouble() * MILLIS_PER_SECOND / elapsed).toLong()
                }
            }
            previousSamples[task.id] = ByteSample(now, task.bytesDownloaded)
        }
        speeds.value = nextSpeeds
    }

    private fun DownloadTask.toUiModel(speedByTask: Map<Long, Long>): TaskUiModel {
        val isDownloading = status == TaskStatus.Downloading
        return TaskUiModel(
            id = id,
            songId = songId?.value,
            sourceUrl = sourceUrl,
            mediaStoreId = mediaStoreId,
            title = title,
            artist = artist,
            artworkUrl = artworkUrl,
            qualityLabel = (actualQuality ?: requestedQuality).label,
            statusLabel = statusLabel(),
            progress = if (isDownloading) {
                FileInfoFormatter.formatProgress(bytesDownloaded, sizeBytes)
            } else {
                null
            },
            bytesDownloaded = bytesDownloaded.takeIf { it > 0L },
            totalBytes = sizeBytes,
            speedBytesPerSecond = if (isDownloading) speedByTask[id] else null,
            failureKind = failureKind?.name,
            failureMessage = failureMessage,
            fileName = fileName,
            completedAt = completedAt,
            canPause = status == TaskStatus.Downloading || status == TaskStatus.Resolving,
            canResume = status == TaskStatus.Paused,
            canCancel = status == TaskStatus.Queued ||
                status == TaskStatus.Resolving ||
                status == TaskStatus.Waiting ||
                status == TaskStatus.Downloading ||
                status == TaskStatus.Paused ||
                status == TaskStatus.Failed,
            canRetry = status == TaskStatus.Failed,
            canDelete = status == TaskStatus.Cancelled,
        )
    }

    private fun DownloadTask.statusLabel(): String = when (status) {
        TaskStatus.Queued -> text(R.string.status_queued)
        TaskStatus.Resolving -> text(R.string.status_resolving)
        TaskStatus.Waiting -> text(R.string.status_waiting)
        TaskStatus.Downloading -> text(R.string.status_downloading)
        TaskStatus.Paused -> text(R.string.status_paused)
        TaskStatus.Completed -> text(R.string.status_completed)
        TaskStatus.Cancelled -> text(R.string.status_cancelled)
        TaskStatus.Failed -> failureLabel(failureKind, failureMessage)
    }

    /**
     * 失败文案：固定文案来自资源；需要展示服务端原文的类别追加 `：{msg}`。
     */
    private fun failureLabel(kind: FailureKind?, message: String?): String {
        val baseRes = when (kind) {
            FailureKind.Network -> R.string.failure_network
            FailureKind.InvalidKey -> R.string.failure_invalid_key
            FailureKind.QuotaExhausted -> R.string.failure_quota_exhausted
            FailureKind.Forbidden -> R.string.failure_forbidden
            FailureKind.RateLimited -> R.string.failure_rate_limited
            FailureKind.InvalidRequest -> R.string.failure_invalid_request
            FailureKind.QualityUnavailable -> R.string.failure_quality_unavailable
            FailureKind.NotFound -> R.string.failure_not_found
            FailureKind.ServiceUnavailable -> R.string.failure_service_unavailable
            FailureKind.Malformed -> R.string.failure_malformed
            FailureKind.StreamExpired -> R.string.failure_stream_expired
            FailureKind.Integrity -> R.string.failure_integrity
            FailureKind.Storage -> R.string.failure_storage
            FailureKind.Unknown -> R.string.failure_unknown
            null -> R.string.failure_unknown
        }
        val base = text(baseRes)
        val needsMessage = kind != null && kind in FAILURE_WITH_MESSAGE && !message.isNullOrBlank()
        return if (needsMessage) {
            text(R.string.failure_with_message, base, message)
        } else {
            base
        }
    }

    private fun DownloadTask.matchesQuery(query: String): Boolean {
        if (query.isBlank()) return true
        return listOfNotNull(title, artist, fileName)
            .any { it.contains(query, ignoreCase = true) }
    }

    private fun text(resId: Int, vararg args: Any): String =
        appContext.resources.getString(resId, *args)

    private data class TaskLists(
        val inProgress: List<DownloadTask>,
        val completed: List<DownloadTask>,
        val quota: Quota?,
        val engine: EngineSnapshot,
    )

    private data class LocalState(
        val input: String,
        val selectedTab: WorkbenchTab,
        val historyQuery: String,
        val isEnqueuing: Boolean,
        val qualityOverride: MusicQuality?,
        val defaultQuality: MusicQuality = MusicQuality.default,
    )

    private data class ByteSample(val elapsedMillis: Long, val bytesDownloaded: Long)

    private companion object {
        const val SPEED_SAMPLE_INTERVAL_MILLIS = 1_000L
        const val MILLIS_PER_SECOND = 1_000.0

        val FAILURE_WITH_MESSAGE: Set<FailureKind> = setOf(
            FailureKind.Forbidden,
            FailureKind.InvalidRequest,
            FailureKind.NotFound,
            FailureKind.Unknown,
        )
    }
}
