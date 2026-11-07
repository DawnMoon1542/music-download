package com.dawnmoon.music_download.download

import android.content.Context
import com.dawnmoon.music_download.core.AppClock
import com.dawnmoon.music_download.data.repository.AppSettings
import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.TrackResolver
import com.dawnmoon.music_download.domain.model.DownloadTask
import com.dawnmoon.music_download.domain.model.FailureKind
import com.dawnmoon.music_download.domain.model.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * 下载调度引擎：解析队列串行推进任务，下载池按可调并发执行下载作业。
 *
 * 解析串行、下载并发；所有状态写入都委托 [TaskRepository]，引擎只负责作业生命周期、
 * 批量操作、快照聚合与通知/唤醒锁等副作用。前台服务持有本实例，`attach` 时开始消费队列，
 * `detach` 时取消作业并释放 WakeLock。
 *
 * 服务尚未 `attach` 时发起的控制操作会进入缓冲队列，`attach` 后按调用顺序重放，避免冷启动
 * 阶段 `start` 与 `requestDownload` 之间的异步窗口丢失任务。
 */
class DownloadEngine(
    private val repo: TaskRepository,
    private val resolver: TrackResolver,
    private val settings: AppSettings,
    private val fileStore: TaskFileStore,
    private val downloader: TaskDownloader,
    private val notifications: DownloadNotifier,
    private val wakeLock: WakeLockController,
    private val clock: AppClock,
    private val serviceStopper: ServiceStopper,
    private val serviceStarter: DownloadServiceStarter,
) : DownloadController {

    private val parseQueue = Channel<Long>(Channel.UNLIMITED)
    private val downloadQueue = Channel<Long>(Channel.UNLIMITED)
    private val limiter = AdaptiveLimiter(DEFAULT_CONCURRENCY)

    /** 正在执行的下载作业；解析作业由 [ParseWorker] 自己持有，避免交接被误判为重复投递。 */
    private val downloadJobs = mutableMapOf<Long, Job>()

    private var parseWorker: ParseWorker? = null

    private val _snapshot = MutableStateFlow(EMPTY_SNAPSHOT)
    override val snapshot: StateFlow<EngineSnapshot> = _snapshot.asStateFlow()

    /** 引擎作用域；未 attach 时二者都为 null。 */
    @Volatile
    private var engineScope: CoroutineScope? = null

    private var engineJob: Job? = null

    @Volatile
    private var attached = false

    @Volatile
    private var serviceStarted = false

    @Volatile
    private var parsePaused = false

    @Volatile
    private var poolPaused = false

    @Volatile
    private var latestTasks: List<DownloadTask> = emptyList()

    /** 本次会话提交过的任务数；空闲后清空。 */
    private val sessionIds = mutableSetOf<Long>()
    private val completedIds = mutableSetOf<Long>()

    /**
     * 会话累积字节，与 [latestTasks] 解耦。
     *
     * `observeInProgress` 不包含 Completed，若只按当前列表求和，任务完成时进度会回退。
     * 因此在任务完成时把最终字节记入 [sessionBytes]，并单独记录每个任务的大小，
     * 任一任务大小未知时让 [EngineSnapshot.bytesTotal] 保持 null（indeterminate）。
     */
    private val sessionBytes = mutableMapOf<Long, Long>()
    private val sessionTotals = mutableMapOf<Long, Long?>()

    private val operationLock = Any()
    private val pendingOperations = mutableListOf<suspend CoroutineScope.() -> Unit>()

    private var lastActiveCount = 0
    private var lastSampleTime = 0L
    private var lastSampleBytes = 0L
    private var speedBytesPerSecond = 0L
    private var idle = true

    private var lastPublished: EngineSnapshot? = null
    private var hasPublished = false

    private var idleStopJob: Job? = null
    private var stopScheduled = false

    /** 幂等启动前台服务；`detach` 后允许再次启动。 */
    override fun start(context: Context) {
        ensureServiceStarted()
    }

    /**
     * 幂等地拉起前台服务。
     *
     * [DownloadController.start] 与未 attach 时的控制操作共用该路径：多个控制操作同时到达时
     * 只有第一次触发启动，避免重复投递 `startForegroundService`。
     */
    private fun ensureServiceStarted() {
        if (serviceStarted) return
        serviceStarter.start()
        serviceStarted = true
    }

    /**
     * 启动引擎：收集并发设置与仓库状态、启动解析/下载消费循环和通知节流。
     *
     * 重复调用会被忽略；作用域由宿主（前台服务或测试夹具）提供。
     * `attach` 完成后重放此前缓冲的控制操作。
     */
    override suspend fun attach(scope: CoroutineScope) {
        if (attached) return
        attached = true
        lastSampleTime = clock.elapsedRealtime()

        val supervisor = SupervisorJob(scope.coroutineContext[Job])
        val engine = CoroutineScope(scope.coroutineContext + supervisor)
        engineJob = supervisor
        synchronized(operationLock) { engineScope = engine }

        val worker = ParseWorker(repo, resolver, settings, notifications).apply {
            isPaused = { parsePaused }
            onKeyLevelFailure = { parsePaused = true }
            onResolved = { taskId -> downloadQueue.send(taskId) }
            onTaskFinished = { refreshSnapshot() }
        }
        parseWorker = worker

        engine.launch {
            settings.maxConcurrentDownloads.collect { limiter.setPermits(it) }
        }
        engine.launch {
            repo.observeInProgress().collect { tasks ->
                latestTasks = tasks
                refreshSnapshot()
            }
        }
        engine.launch { worker.run(parseQueue) }
        engine.launch { runDownloadLoop(engine) }
        engine.launch { notificationLoop() }
        refreshSnapshot()

        val replay = synchronized(operationLock) {
            pendingOperations.toList().also { pendingOperations.clear() }
        }
        replay.forEach { operation -> engine.launch(block = operation) }
    }

    /**
     * 停止引擎：取消所有作业并释放 WakeLock。
     *
     * 对运行中的任务发送暂停信号，让下载器或解析器完成收尾；随后取消整个引擎作用域。
     */
    override fun detach() {
        if (!attached) return
        attached = false
        parseWorker?.cancelActiveNow(CancellationSignal.Pause)
        downloadJobs.values.toList().forEach { it.cancel(CancellationSignal.Pause) }
        downloadJobs.clear()
        engineJob?.cancel()
        engineJob = null
        synchronized(operationLock) { engineScope = null }
        parseWorker = null
        idleStopJob = null
        stopScheduled = false
        serviceStarted = false
        wakeLock.releaseAll()
    }

    override fun requestDownload(ids: List<Long>) = launchOperation {
        val wasParsePaused = parsePaused
        parsePaused = false
        val seen = mutableSetOf<Long>()
        for (id in ids) {
            val task = repo.byId(id) ?: continue
            sessionIds += id
            seen += id
            enqueueByStatus(task)
        }
        if (wasParsePaused) {
            // Key 恢复后把仍停留在队列里的任务重新投递，避免它们被暂停时吞掉。
            redispatchParsing(seen)
        }
        refreshSnapshot()
    }

    override fun pause(id: Long) = launchOperation {
        val worker = parseWorker
        if (worker?.currentTaskId == id) {
            worker.cancelActive(CancellationSignal.Pause)
        } else {
            cancelDownloadJob(id, CancellationSignal.Pause)
        }
        val task = repo.byId(id) ?: return@launchOperation
        if (task.status.isActive) repo.pause(id)
        refreshSnapshot()
    }

    override fun resume(id: Long) = launchOperation {
        parsePaused = false
        poolPaused = false
        val task = repo.byId(id)
        if (task != null && task.status == TaskStatus.Paused) {
            repo.resume(id)
            sessionIds += id
            routeResumed(id)
        }
        // 暂停期间被解析/下载循环丢掉的其它任务同样需要重新投递。
        redispatchPending(exclude = setOf(id))
        refreshSnapshot()
    }

    override fun cancel(id: Long) = launchOperation {
        val worker = parseWorker
        if (worker?.currentTaskId == id) {
            worker.cancelActive(CancellationSignal.Cancel)
        } else {
            cancelDownloadJob(id, CancellationSignal.Cancel)
        }
        val task = repo.byId(id)
        if (task == null || task.status.isTerminal) {
            refreshSnapshot()
            return@launchOperation
        }
        if (!deletePendingFile(task)) {
            // 删除失败时保留记录，避免 mediaStoreId 丢失后无法再次清理。
            refreshSnapshot()
            return@launchOperation
        }
        repo.markCancelled(id)
        refreshSnapshot()
    }

    override fun retry(id: Long) = launchOperation {
        val task = repo.byId(id) ?: return@launchOperation
        if (task.status != TaskStatus.Failed) return@launchOperation
        if (!deletePendingFile(task)) return@launchOperation
        repo.resetForRetry(id)
        sessionIds += id
        parsePaused = false
        poolPaused = false
        parseQueue.send(id)
        redispatchPending(exclude = setOf(id))
        refreshSnapshot()
    }

    override fun pauseAll() = launchOperation {
        poolPaused = true
        parsePaused = true
        parseWorker?.cancelActive(CancellationSignal.Pause)
        downloadJobs.values.toList().forEach { job ->
            job.cancel(CancellationSignal.Pause)
            job.join()
        }
        repo.pauseAll()
        refreshSnapshot()
    }

    override fun resumeAll() = launchOperation {
        poolPaused = false
        parsePaused = false
        repo.resumeAll()
        redispatchPending()
        refreshSnapshot()
    }

    override fun cancelAll() = launchOperation {
        // 先阻止解析循环继续取新任务，再取消当前任务，避免取消过程中又有任务进入 Resolving。
        poolPaused = true
        parsePaused = true
        parseWorker?.cancelActive(CancellationSignal.Cancel)

        val pending = repo.pendingIds()
        val preserved = mutableSetOf<Long>()
        for (id in pending) {
            cancelDownloadJob(id, CancellationSignal.Cancel)
            val task = repo.byId(id) ?: continue
            if (task.status.isTerminal) continue
            if (!deletePendingFile(task)) {
                preserved += id
                continue
            }
            repo.markCancelled(id)
        }
        // 覆盖取消期间新进入队列的任务；删除失败的任务保留原状态与文件引用。
        repo.pendingIds()
            .filter { it !in preserved }
            .forEach { repo.markCancelled(it) }

        poolPaused = false
        parsePaused = false
        refreshSnapshot()
    }

    override fun retryAllFailed() = launchOperation {
        val failed = repo.pendingIds()
            .mapNotNull { repo.byId(it) }
            .filter { it.status == TaskStatus.Failed }
        val requeued = mutableSetOf<Long>()
        for (task in failed) {
            // 删除失败时跳过该任务，保留文件字段以便后续仍能定位 pending 条目。
            if (!deletePendingFile(task)) continue
            repo.resetForRetry(task.id)
            sessionIds += task.id
            requeued += task.id
        }
        parsePaused = false
        poolPaused = false
        requeued.forEach { parseQueue.send(it) }
        redispatchPending(exclude = requeued)
        refreshSnapshot()
    }

    /**
     * 下载池：每个任务一个独立作业，作业内部取得许可后执行。
     *
     * 许可在作业内获取，取消尚未启动或仍在排队的作业不会泄漏许可
     * （[AdaptiveLimiter.acquire] 会移除等待者）；作业使用惰性启动，保证进入 [downloadJobs]
     * 后才开始执行。解析作业不在此表中，解析成功后可直接交接给下载队列。
     */
    private suspend fun runDownloadLoop(scope: CoroutineScope) {
        for (taskId in downloadQueue) {
            if (downloadJobs.containsKey(taskId)) continue
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val self = currentCoroutineContext().job
                var acquired = false
                try {
                    limiter.acquire()
                    acquired = true
                    if (!attached || poolPaused) return@launch
                    val task = repo.byId(taskId)
                    if (task == null || task.status != TaskStatus.Waiting) return@launch
                    when (val result = downloader.execute(taskId)) {
                        WorkerResult.Completed -> {
                            completedIds += taskId
                            recordCompleted(taskId)
                        }

                        is WorkerResult.Failed -> {
                            if (result.kind == FailureKind.Storage) poolPaused = true
                        }

                        WorkerResult.Paused, WorkerResult.Cancelled -> Unit
                    }
                } finally {
                    if (acquired) limiter.release()
                    if (downloadJobs[taskId] === self) downloadJobs.remove(taskId)
                    refreshSnapshot()
                }
            }
            downloadJobs[taskId] = job
            job.start()
        }
    }

    /** 每 500ms 发布一次变化过的快照，保证同一窗口内的进度变化合并为一条通知。 */
    private suspend fun notificationLoop() {
        while (currentCoroutineContext().isActive) {
            delay(NOTIFICATION_INTERVAL_MILLIS)
            publishIfChanged()
        }
    }

    private fun publishIfChanged() {
        if (!attached) return
        if (idle) {
            if (hasPublished) {
                notifications.clear()
                hasPublished = false
                lastPublished = null
            }
            return
        }
        val current = _snapshot.value
        if (current != lastPublished) {
            notifications.update(current, current.isPaused)
            lastPublished = current
            hasPublished = true
        }
    }

    /**
     * 由仓库状态与作业集合重新计算快照。
     *
     * 会话计数只在引擎彻底空闲时归零；只要还有 Queued/Resolving/Waiting/Downloading 任务或
     * 运行中下载作业，完成数就继续保留，便于界面展示“已完成 x / 共 y”。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun refreshSnapshot() {
        if (!attached) return
        // 惰性启动的作业可能在执行体运行前就被取消，其 finally 不会执行；这里清理其残留记录。
        downloadJobs.filterValues { it.isCompleted }.keys.forEach { downloadJobs.remove(it) }
        val tasks = latestTasks
        val activeCount = tasks.count {
            it.status == TaskStatus.Resolving || it.status == TaskStatus.Downloading
        }
        val queuedWork = !parseQueue.isEmpty || !downloadQueue.isEmpty
        val anyPending = queuedWork || tasks.any { it.status.isActive } || downloadJobs.isNotEmpty()
        idle = !anyPending

        if (idle) {
            sessionIds.clear()
            completedIds.clear()
            sessionBytes.clear()
            sessionTotals.clear()
            speedBytesPerSecond = 0L
            lastSampleBytes = 0L
            lastSampleTime = clock.elapsedRealtime()
        }

        if (lastActiveCount == 0 && activeCount > 0) {
            wakeLock.acquire()
        } else if (lastActiveCount > 0 && activeCount == 0) {
            wakeLock.release()
        }
        lastActiveCount = activeCount

        val tasksById = tasks.associateBy { it.id }
        for (id in sessionIds) {
            val task = tasksById[id] ?: continue
            sessionBytes[id] = task.bytesDownloaded
            sessionTotals[id] = task.sizeBytes
        }
        val bytesDone = sessionIds.sumOf { sessionBytes[it] ?: 0L }
        val bytesTotal: Long? = when {
            sessionIds.isEmpty() -> 0L
            sessionIds.any { sessionTotals[it] == null } -> null
            else -> sessionIds.sumOf { sessionTotals[it] ?: 0L }
        }
        if (!idle) speedBytesPerSecond = sampleSpeed(bytesDone)

        _snapshot.value = EngineSnapshot(
            activeCount = activeCount,
            completedInSession = completedIds.size,
            totalInSession = sessionIds.size,
            bytesDone = bytesDone,
            bytesTotal = bytesTotal,
            speedBytesPerSecond = speedBytesPerSecond,
            isPaused = poolPaused,
        )

        if (idle) scheduleIdleStop() else cancelIdleStop()
    }

    /** 把完成任务的最终字节固化为会话累积值，避免它从 `observeInProgress` 消失时进度回退。 */
    private suspend fun recordCompleted(taskId: Long) {
        val task = repo.byId(taskId) ?: return
        sessionBytes[taskId] = task.bytesDownloaded
        sessionTotals[taskId] = task.sizeBytes
    }

    /** 依据注入时钟的单调时间计算聚合速度；时钟未前进时沿用上一次结果。 */
    private fun sampleSpeed(bytesDone: Long): Long {
        val now = clock.elapsedRealtime()
        val elapsed = now - lastSampleTime
        if (elapsed <= 0L) return speedBytesPerSecond
        val delta = (bytesDone - lastSampleBytes).coerceAtLeast(0L)
        lastSampleTime = now
        lastSampleBytes = bytesDone
        return delta * MILLIS_PER_SECOND / elapsed
    }

    private fun scheduleIdleStop() {
        if (stopScheduled) return
        val scope = engineScope ?: return
        stopScheduled = true
        idleStopJob = scope.launch {
            delay(IDLE_STOP_MILLIS)
            serviceStopper.stop()
        }
    }

    private fun cancelIdleStop() {
        if (!stopScheduled) return
        stopScheduled = false
        idleStopJob?.cancel()
        idleStopJob = null
    }

    /** 按任务当前状态投递到对应队列；只处理引擎可推进的状态。 */
    private suspend fun enqueueByStatus(task: DownloadTask) {
        when (task.status) {
            TaskStatus.Queued -> parseQueue.send(task.id)
            TaskStatus.Waiting -> downloadQueue.send(task.id)
            TaskStatus.Paused -> {
                repo.resume(task.id)
                routeResumed(task.id)
            }

            else -> Unit
        }
    }

    private suspend fun routeResumed(id: Long) {
        when (repo.byId(id)?.status) {
            TaskStatus.Queued -> parseQueue.send(id)
            TaskStatus.Waiting -> downloadQueue.send(id)
            else -> Unit
        }
    }

    /** 重新投递仍处于 Queued 的任务，供解析暂停恢复后使用。 */
    private suspend fun redispatchParsing(exclude: Set<Long> = emptySet()) {
        repo.pendingIds()
            .mapNotNull { repo.byId(it) }
            .filter { it.status == TaskStatus.Queued && it.id !in exclude }
            .forEach { parseQueue.send(it.id) }
    }

    /** 重新投递所有可推进的任务；暂停标志清除后用于恢复被循环丢弃的队列项。 */
    private suspend fun redispatchPending(exclude: Set<Long> = emptySet()) {
        val tasks = repo.pendingIds().mapNotNull { repo.byId(it) }
        tasks.filter { it.status == TaskStatus.Queued && it.id !in exclude }
            .forEach { parseQueue.send(it.id) }
        tasks.filter { it.status == TaskStatus.Waiting && it.id !in exclude }
            .forEach { downloadQueue.send(it.id) }
    }

    private suspend fun cancelDownloadJob(id: Long, signal: CancellationSignal) {
        val job = downloadJobs[id] ?: return
        job.cancel(signal)
        job.join()
        if (downloadJobs[id] === job) downloadJobs.remove(id)
    }

    /** 删除任务的 pending 文件；没有文件视为成功。false 表示删除失败，调用方必须保留记录。 */
    private suspend fun deletePendingFile(task: DownloadTask): Boolean {
        val mediaStoreId = task.mediaStoreId ?: return true
        return fileStore.deleteById(mediaStoreId)
    }

    /**
     * 把非挂起操作投递到引擎作用域；未 attach 时进入缓冲，attach 后重放。
     *
     * 引擎未 attach 说明前台服务没有运行（冷启动或空闲自停），此时必须同时拉起服务，
     * 否则缓冲的操作永远不会被重放。`ensureServiceStarted` 在锁外调用，避免与 attach 的
     * 同步块互相阻塞。
     */
    private fun launchOperation(block: suspend CoroutineScope.() -> Unit) {
        val scope = synchronized(operationLock) {
            engineScope ?: run {
                pendingOperations += block
                null
            }
        }
        if (scope == null) {
            ensureServiceStarted()
            return
        }
        scope.launch(block = block)
    }

    private companion object {
        const val DEFAULT_CONCURRENCY = 3
        const val NOTIFICATION_INTERVAL_MILLIS = 500L
        const val IDLE_STOP_MILLIS = 2_000L
        const val MILLIS_PER_SECOND = 1_000L
        val EMPTY_SNAPSHOT = EngineSnapshot(
            activeCount = 0,
            completedInSession = 0,
            totalInSession = 0,
            bytesDone = 0L,
            bytesTotal = 0L,
            speedBytesPerSecond = 0L,
            isPaused = false,
        )
    }
}
