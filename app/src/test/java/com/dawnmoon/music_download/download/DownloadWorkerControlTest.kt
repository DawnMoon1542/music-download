package com.dawnmoon.music_download.download

import com.dawnmoon.music_download.core.AppClock
import com.dawnmoon.music_download.data.remote.ApiResponse
import com.dawnmoon.music_download.data.repository.AppSettings
import com.dawnmoon.music_download.data.repository.EnqueueOutcome
import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.TaskTransitions
import com.dawnmoon.music_download.domain.TrackResolver
import com.dawnmoon.music_download.domain.model.AudioFormat
import com.dawnmoon.music_download.domain.model.BaseFileName
import com.dawnmoon.music_download.domain.model.DownloadTask
import com.dawnmoon.music_download.domain.model.FailureKind
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.ResolvedTrack
import com.dawnmoon.music_download.domain.model.SongId
import com.dawnmoon.music_download.domain.model.SongLink
import com.dawnmoon.music_download.domain.model.TaskStatus
import com.dawnmoon.music_download.domain.model.ThemeMode
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val TASK_ID = 42L
private const val SONG_ID = 1_867_150_097L

/** 8 MiB 足以让节流后的响应在测试完成断言前不会自然结束。 */
private const val SLOW_CONTENT_SIZE = 8 * 1024 * 1024

/** 等待下载开始写盘的时限；超过说明测试夹具本身失效，而不是目标行为缺失。 */
private const val START_DEADLINE_MS = 10_000L

/** 取消后允许的收尾时限。 */
private const val CANCEL_DEADLINE_MS = 2_000L

/**
 * [DefaultDownloadWorker] 暂停、取消与本地写入错误的契约测试。
 *
 * 暂停与取消都通过取消执行协程触发，并用 `CancellationSignal` 的两个子类型表达用户意图：
 * 暂停保留 pending 文件并回到 `Paused`，取消删除条目并进入 `Cancelled`。
 * 期望的生产类型是 `CancellationException` 的密封子类，例如
 * `sealed class CancellationSignal : CancellationException() { data object Pause; data object Cancel }`，
 * 由引擎在 `job.cancel(...)` 时传入，下载器在 `finally` 中按它决定收尾方式。
 *
 * 每个用例都通过真实文件替身与 MockWebServer/OkHttp 交互断言可见结果，而不是断言内部调用顺序。
 */
class DownloadWorkerControlTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private var serverClosed = false
    private val result = CompletableDeferred<WorkerResult>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        serverClosed = false
    }

    @After
    fun tearDown() {
        closeServer()
    }

    @Test
    fun `pausing a download keeps the pending file and returns paused`() = runBlocking {
        val content = sampleBytes(SLOW_CONTENT_SIZE)
        server.enqueue(slowAudio(content))
        val delegate = FakeTaskFileStore(tempFolder.newFolder())
        val fileStore = ObservedFileStore(delegate)
        val repo = ControlTaskRepository(task(sizeBytes = content.size.toLong(), md5 = null))
        val job = launch(Dispatchers.Default) {
            runCatching { worker(repo, fileStore).execute(TASK_ID) }
                .onSuccess { result.complete(it) }
        }

        withTimeout(START_DEADLINE_MS) { fileStore.firstWrite.await() }
        job.cancel(CancellationSignal.Pause)
        val finished = awaitCompletion(job)
        assertTrue(
            "the worker must stop and return after a pause request, but it was still reading",
            finished,
        )
        assertTrue("the worker must report a result after a pause request", result.isCompleted)
        assertEquals(WorkerResult.Paused, result.getCompleted())
        assertEquals(
            "a paused task must be persisted as Paused",
            TaskStatus.Paused,
            repo.task.status,
        )
        assertEquals("markPaused must run even though the coroutine was cancelled", 1, repo.pauseCount)
        val mediaStoreId = requireNotNull(repo.attachedMediaStoreId) {
            "the pending entry must exist before the download can be paused"
        }
        assertTrue("a pause must keep the partially written file", delegate.exists(mediaStoreId))
        assertTrue(
            "a pause must keep the bytes already written for a later resume",
            requireNotNull(delegate.contentOf(mediaStoreId)).isNotEmpty(),
        )
        assertEquals("a pause must not delete the pending entry", 0, fileStore.deleteCount)
    }

    @Test
    fun `cancelling a download removes the pending file once and returns cancelled`() = runBlocking {
        val content = sampleBytes(SLOW_CONTENT_SIZE)
        server.enqueue(slowAudio(content))
        val delegate = FakeTaskFileStore(tempFolder.newFolder())
        val fileStore = ObservedFileStore(delegate)
        val repo = ControlTaskRepository(task(sizeBytes = content.size.toLong(), md5 = null))
        val job = launch(Dispatchers.Default) {
            runCatching { worker(repo, fileStore).execute(TASK_ID) }
                .onSuccess { result.complete(it) }
        }

        withTimeout(START_DEADLINE_MS) { fileStore.firstWrite.await() }
        job.cancel(CancellationSignal.Cancel)
        val finished = awaitCompletion(job)
        assertTrue(
            "the worker must stop and return after a cancel request, but it was still reading",
            finished,
        )
        assertTrue("the worker must report a result after a cancel request", result.isCompleted)
        assertEquals(WorkerResult.Cancelled, result.getCompleted())
        assertEquals(
            "a cancelled task must be persisted as Cancelled",
            TaskStatus.Cancelled,
            repo.task.status,
        )
        assertEquals(
            "markCancelled must run exactly once even though the coroutine was cancelled",
            1,
            repo.cancelCount,
        )
        val mediaStoreId = requireNotNull(repo.attachedMediaStoreId) {
            "the pending entry must exist before the download can be cancelled"
        }
        assertFalse("cancelling must delete the pending entry", delegate.exists(mediaStoreId))
        assertEquals("the pending entry must be deleted exactly once", 1, fileStore.deleteCount)
    }

    @Test
    fun `a storage write failure deletes the file and is not retried`() = runBlocking {
        val content = sampleBytes(64 * 1024)
        // 预置三次响应，确保“错误地重试”时服务端不会阻塞等待第四个请求。
        repeat(3) { server.enqueue(audio(content)) }
        val delegate = FakeTaskFileStore(tempFolder.newFolder())
        val fileStore = FailingWriteFileStore(delegate, IOException("ENOSPC"))
        val repo = ControlTaskRepository(task(sizeBytes = content.size.toLong(), md5 = null))

        val outcome = worker(repo, fileStore).execute(TASK_ID)

        assertEquals(WorkerResult.Failed(FailureKind.Storage), outcome)
        assertEquals(
            "a local write failure must be recorded as Storage",
            FailureKind.Storage,
            repo.failureKind,
        )
        assertEquals(TaskStatus.Failed, repo.task.status)
        val mediaStoreId = requireNotNull(repo.attachedMediaStoreId) {
            "the pending entry must be created before the write can fail"
        }
        assertFalse("a storage failure must delete the unusable pending entry", delegate.exists(mediaStoreId))
        assertEquals(
            "a storage failure must not be retried",
            1,
            server.requestCount,
        )
    }

    @Test
    fun `network failures give up after three attempts and keep the pending file`() = runBlocking {
        val attempts = AtomicInteger()
        val failingClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                attempts.incrementAndGet()
                throw IOException("connection reset")
            }
            .build()
        val delegate = FakeTaskFileStore(tempFolder.newFolder())
        val repo = ControlTaskRepository(task(sizeBytes = null, md5 = null))

        val outcome = worker(repo, delegate, failingClient).execute(TASK_ID)

        assertEquals(WorkerResult.Failed(FailureKind.Network), outcome)
        assertEquals(FailureKind.Network, repo.failureKind)
        assertEquals("transient network errors allow two retries after the first attempt", 3, attempts.get())
        val mediaStoreId = requireNotNull(repo.attachedMediaStoreId) {
            "the pending entry must exist before the network can fail"
        }
        assertTrue("a network failure must keep the partial file for resume", delegate.exists(mediaStoreId))
    }

    @Test
    fun `cancelling interrupts a blocked body read within one second`() = runBlocking {
        val content = sampleBytes(SLOW_CONTENT_SIZE)
        server.enqueue(slowAudio(content, bytesPerPeriod = 1024, periodMs = 200))
        val delegate = FakeTaskFileStore(tempFolder.newFolder())
        val fileStore = ObservedFileStore(delegate)
        val repo = ControlTaskRepository(task(sizeBytes = content.size.toLong(), md5 = null))
        val job = launch(Dispatchers.Default) {
            runCatching { worker(repo, fileStore).execute(TASK_ID) }
                .onSuccess { result.complete(it) }
        }

        withTimeout(START_DEADLINE_MS) { fileStore.firstWrite.await() }
        val startedAt = System.nanoTime()
        job.cancel(CancellationSignal.Cancel)
        val finished = withTimeoutOrNull(1_000) { job.join(); true } ?: false
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        if (!finished) {
            // 当前实现没有中断阻塞读时，关闭服务端以释放读线程，避免测试进程挂起。
            closeServer()
            withTimeoutOrNull(5_000) { job.join() }
        }

        assertTrue(
            "cancelling must interrupt the blocked response read within 1s, took ${elapsedMillis}ms",
            finished,
        )
        assertTrue("the worker must report a result after cancelling a blocked read", result.isCompleted)
        assertEquals(WorkerResult.Cancelled, result.getCompleted())
        assertEquals(TaskStatus.Cancelled, repo.task.status)
    }

    @Test
    fun `pausing while the task is being read still persists paused`() = runBlocking {
        val delegate = FakeTaskFileStore(tempFolder.newFolder())
        val repo = ControlTaskRepository(task(sizeBytes = null, md5 = null))
        val readStarted = repo.holdFirstRead()
        val job = launch(Dispatchers.Default) {
            runCatching { worker(repo, delegate).execute(TASK_ID) }
                .onSuccess { result.complete(it) }
        }

        withTimeout(START_DEADLINE_MS) { readStarted.await() }
        job.cancel(CancellationSignal.Pause)
        val finished = awaitCompletion(job)

        assertTrue(
            "the worker must stop and return when paused before the task read completes",
            finished,
        )
        assertTrue("the worker must report a result when paused during the task read", result.isCompleted)
        assertEquals(WorkerResult.Paused, result.getCompleted())
        assertEquals(
            "a task paused before startDownloading must not be left Waiting",
            TaskStatus.Paused,
            repo.task.status,
        )
        assertEquals(1, repo.pauseCount)
    }

    @Test
    fun `cancelling after the task is marked downloading still persists cancelled`() = runBlocking {
        val delegate = FakeTaskFileStore(tempFolder.newFolder())
        val repo = ControlTaskRepository(task(sizeBytes = null, md5 = null))
        val started = repo.holdStartDownloading()
        val job = launch(Dispatchers.Default) {
            runCatching { worker(repo, delegate).execute(TASK_ID) }
                .onSuccess { result.complete(it) }
        }

        withTimeout(START_DEADLINE_MS) { started.await() }
        assertEquals(
            "the fixture must reach Downloading before the cancellation window",
            TaskStatus.Downloading,
            repo.task.status,
        )
        job.cancel(CancellationSignal.Cancel)
        val finished = awaitCompletion(job)

        assertTrue(
            "the worker must stop and return when cancelled after the Downloading commit",
            finished,
        )
        assertTrue("the worker must report a result when cancelled in the start window", result.isCompleted)
        assertEquals(WorkerResult.Cancelled, result.getCompleted())
        assertEquals(
            "a task cancelled after the Downloading commit must not be left Downloading",
            TaskStatus.Cancelled,
            repo.task.status,
        )
        assertEquals(1, repo.cancelCount)
    }

    @Test
    fun `cancelling after the task is completed keeps the published file`() = runBlocking {
        val content = sampleBytes(64 * 1024)
        server.enqueue(audio(content))
        val delegate = FakeTaskFileStore(tempFolder.newFolder())
        val fileStore = ObservedFileStore(delegate)
        val repo = ControlTaskRepository(task(sizeBytes = content.size.toLong(), md5 = null))
        val completed = repo.holdAfterMarkCompleted()
        val job = launch(Dispatchers.Default) {
            runCatching { worker(repo, fileStore).execute(TASK_ID) }
                .onSuccess { result.complete(it) }
        }

        withTimeout(START_DEADLINE_MS) { completed.await() }
        assertEquals(
            "the fixture must commit Completed before the cancellation window",
            TaskStatus.Completed,
            repo.task.status,
        )
        val mediaStoreId = requireNotNull(repo.attachedMediaStoreId) {
            "the completed task must keep the published media entry"
        }
        assertTrue(
            "the fixture must publish the file before the cancellation window",
            delegate.isCompleted(mediaStoreId),
        )

        job.cancel(CancellationSignal.Cancel)
        val finished = awaitCompletion(job)

        assertTrue(
            "the worker must stop and return when cancelled after the Completed commit",
            finished,
        )
        assertTrue("the worker must report a result when cancelled after completion", result.isCompleted)
        assertEquals(WorkerResult.Completed, result.getCompleted())
        assertEquals(
            "a task already committed as Completed must stay Completed",
            TaskStatus.Completed,
            repo.task.status,
        )
        assertTrue(
            "cancellation after completion must not delete the published media file",
            delegate.exists(mediaStoreId),
        )
        assertEquals("cancellation after completion must not delete any file", 0, fileStore.deleteCount)
        assertEquals("cancellation after completion must not mark the task cancelled", 0, repo.cancelCount)
    }

    private suspend fun awaitCompletion(job: Job): Boolean {
        val finished = withTimeoutOrNull(CANCEL_DEADLINE_MS) { job.join(); true } ?: false
        if (!finished) {
            // 未实现中断阻塞读时释放服务端连接，确保失败路径不会留下悬挂的读线程。
            closeServer()
            withTimeoutOrNull(5_000) { job.join() }
        }
        return finished
    }

    private fun closeServer() {
        if (!serverClosed) {
            serverClosed = true
            runCatching { server.close() }
        }
    }

    private fun worker(
        repo: ControlTaskRepository,
        fileStore: TaskFileStore,
        httpClient: OkHttpClient = client,
    ): DefaultDownloadWorker = DefaultDownloadWorker(
        repo = repo,
        resolver = UnusedControlResolver(),
        settings = ControlSettings(),
        fileStore = fileStore,
        namer = FileNamer(repo),
        client = httpClient,
        clock = ControlClock(),
        sleeper = { },
    )

    private fun task(sizeBytes: Long?, md5: String?): DownloadTask = DownloadTask(
        id = TASK_ID,
        songId = SongId(SONG_ID),
        sourceUrl = "https://163cn.tv/AbCd12",
        requestedQuality = MusicQuality.Exhigh,
        actualQuality = MusicQuality.Exhigh,
        title = "测试歌曲",
        artist = "测试歌手",
        album = "测试专辑",
        artworkUrl = null,
        format = AudioFormat.Mp3,
        sizeBytes = sizeBytes,
        md5 = md5,
        streamUrl = server.url("/audio").toString(),
        etag = null,
        bytesDownloaded = 0,
        status = TaskStatus.Waiting,
        mediaStoreId = null,
        fileName = null,
        failureKind = null,
        failureMessage = null,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        completedAt = null,
    )

    private fun audio(content: ByteArray): MockResponse =
        MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "audio/mpeg")
            .body(Buffer().write(content))
            .build()

    private fun slowAudio(
        content: ByteArray,
        bytesPerPeriod: Long = 64 * 1024,
        periodMs: Long = 50,
    ): MockResponse =
        MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "audio/mpeg")
            .body(Buffer().write(content))
            .throttleBody(bytesPerPeriod, periodMs, TimeUnit.MILLISECONDS)
            .build()

    private fun sampleBytes(size: Int): ByteArray = ByteArray(size) { index -> (index * 31 + 7).toByte() }
}

/**
 * 记录首次写入信号与删除次数的 [TaskFileStore] 包装。
 *
 * [delete] 先 [yield]：只有在 `NonCancellable` 中执行的收尾才能越过这个挂起点，
 * 因此“取消后文件被删除”同时验证了收尾不会被协程取消打断。
 */
private class ObservedFileStore(private val delegate: FakeTaskFileStore) : TaskFileStore by delegate {

    val firstWrite = CompletableDeferred<Unit>()

    @Volatile
    var deleteCount: Int = 0
        private set

    override suspend fun openAppend(file: CreatedFile): OutputStream =
        signalFirstWrite(delegate.openAppend(file))

    override suspend fun openTruncate(file: CreatedFile): OutputStream =
        signalFirstWrite(delegate.openTruncate(file))

    override suspend fun delete(file: CreatedFile) {
        yield()
        deleteCount += 1
        delegate.delete(file)
    }

    private fun signalFirstWrite(output: OutputStream): OutputStream =
        object : FilterOutputStream(output) {
            private var signalled = false

            override fun write(b: Int) {
                super.write(b)
                signal()
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                super.write(b, off, len)
                signal()
            }

            private fun signal() {
                if (!signalled) {
                    signalled = true
                    firstWrite.complete(Unit)
                }
            }
        }
}

/** 在写入时抛出本地存储异常的 [TaskFileStore]，用于区分 Storage 与 Network 失败。 */
private class FailingWriteFileStore(
    private val delegate: FakeTaskFileStore,
    private val error: IOException,
) : TaskFileStore by delegate {

    override suspend fun openAppend(file: CreatedFile): OutputStream = throwingOutput()

    override suspend fun openTruncate(file: CreatedFile): OutputStream = throwingOutput()

    private fun throwingOutput(): OutputStream =
        object : OutputStream() {
            override fun write(b: Int) {
                throw error
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                throw error
            }
        }
}

/** 首次下载不应触发换链；若被错误调用，测试以明确错误失败。 */
private class UnusedControlResolver : TrackResolver {
    override suspend fun resolve(
        songId: SongId,
        requested: MusicQuality,
        apiKey: String,
    ): ApiResponse<ResolvedTrack> = error("pause and cancellation must not resolve the track")
}

private class ControlSettings : AppSettings {
    private val keyFlow = MutableStateFlow<String?>(null)

    override val apiKey: Flow<String?> = keyFlow
    override val defaultQuality: Flow<MusicQuality> = MutableStateFlow(MusicQuality.Exhigh)
    override val maxConcurrentDownloads: Flow<Int> = MutableStateFlow(3)
    override val themeMode: Flow<ThemeMode> = MutableStateFlow(ThemeMode.System)
    override val notificationAsked: Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setApiKey(value: String) {
        keyFlow.value = value
    }

    override suspend fun setDefaultQuality(value: MusicQuality) = Unit

    override suspend fun setMaxConcurrentDownloads(value: Int) = Unit

    override suspend fun setThemeMode(value: ThemeMode) = Unit

    override suspend fun markNotificationAsked() = Unit
}

private class ControlClock : AppClock {
    override fun nowMillis(): Long = 10_000L
    override fun elapsedRealtime(): Long = 0L
}

/**
 * 模拟真实仓库的状态机。
 *
 * [markPaused] 与 [markCancelled] 先 [yield]：这两个挂起点在已取消的协程中会立即抛出，
 * 只有下载器在 `NonCancellable` 中收尾时才能完成状态迁移。
 */
private class ControlTaskRepository(initial: DownloadTask) : TaskRepository {

    @Volatile
    var task: DownloadTask = initial
        private set

    @Volatile
    var pauseCount: Int = 0
        private set

    @Volatile
    var cancelCount: Int = 0
        private set

    @Volatile
    var failureKind: FailureKind? = null
        private set

    @Volatile
    var attachedMediaStoreId: Long? = null
        private set

    /** 首次 [byId] 的进入信号与挂起闸门；用于把取消插入读取任务的窗口。 */
    private var firstReadStarted: CompletableDeferred<Unit>? = null
    private var firstReadGate: CompletableDeferred<Unit>? = null

    /** [startDownloading] 完成迁移后的进入信号与挂起闸门；用于把取消插入状态已落库的窗口。 */
    private var startDownloadingStarted: CompletableDeferred<Unit>? = null
    private var startDownloadingGate: CompletableDeferred<Unit>? = null

    /** [markCompleted] 提交终态后的进入信号与挂起闸门；用于把取消插入终态已落库的窗口。 */
    private var markCompletedStarted: CompletableDeferred<Unit>? = null
    private var markCompletedGate: CompletableDeferred<Unit>? = null

    /** 让首次 [byId] 在返回前挂起，并返回它已进入该挂起点的信号。 */
    fun holdFirstRead(): CompletableDeferred<Unit> {
        val started = CompletableDeferred<Unit>()
        firstReadStarted = started
        firstReadGate = CompletableDeferred()
        return started
    }

    /** 让 [startDownloading] 在迁移提交后挂起，并返回迁移已提交的信号。 */
    fun holdStartDownloading(): CompletableDeferred<Unit> {
        val started = CompletableDeferred<Unit>()
        startDownloadingStarted = started
        startDownloadingGate = CompletableDeferred()
        return started
    }

    /** 让 [markCompleted] 在终态提交后挂起，并返回提交已完成的信号。 */
    fun holdAfterMarkCompleted(): CompletableDeferred<Unit> {
        val started = CompletableDeferred<Unit>()
        markCompletedStarted = started
        markCompletedGate = CompletableDeferred()
        return started
    }

    private fun transitionTo(next: TaskStatus) {
        check(TaskTransitions.isAllowed(task.status, next)) {
            "illegal transition ${task.status} -> $next"
        }
        task = task.copy(status = next)
    }

    override fun observeInProgress(): Flow<List<DownloadTask>> =
        MutableStateFlow<List<DownloadTask>>(emptyList())

    override fun observeCompleted(): Flow<List<DownloadTask>> =
        MutableStateFlow<List<DownloadTask>>(emptyList())

    override suspend fun byId(id: Long): DownloadTask? {
        firstReadStarted?.let { started ->
            firstReadStarted = null
            started.complete(Unit)
            firstReadGate?.await()
        }
        return task.takeIf { it.id == id }
    }

    override suspend fun startDownloading(id: Long) {
        transitionTo(TaskStatus.Downloading)
        startDownloadingStarted?.let { started ->
            startDownloadingStarted = null
            started.complete(Unit)
            startDownloadingGate?.await()
        }
    }

    override suspend fun updateProgress(id: Long, bytesDownloaded: Long) {
        check(task.status == TaskStatus.Downloading) {
            "progress must not be written outside Downloading"
        }
        task = task.copy(bytesDownloaded = bytesDownloaded)
    }

    override suspend fun attachFile(
        id: Long,
        fileName: String,
        mediaStoreId: Long,
        bytesDownloaded: Long,
    ) {
        check(task.status == TaskStatus.Downloading) {
            "the created file must be attached while Downloading"
        }
        attachedMediaStoreId = mediaStoreId
        task = task.copy(
            fileName = fileName,
            mediaStoreId = mediaStoreId,
            bytesDownloaded = bytesDownloaded,
        )
    }

    override suspend fun markCompleted(
        id: Long,
        fileName: String,
        mediaStoreId: Long,
        completedAt: Long,
    ) {
        transitionTo(TaskStatus.Completed)
        task = task.copy(
            fileName = fileName,
            mediaStoreId = mediaStoreId,
            completedAt = completedAt,
        )
        // 真实仓库在终态提交后还要拿 mutex 释放文件名；取消可以恰好在这个窗口送达。
        markCompletedStarted?.let { started ->
            markCompletedStarted = null
            started.complete(Unit)
            markCompletedGate?.await()
        }
    }

    override suspend fun markFailed(id: Long, failureKind: FailureKind, message: String) {
        transitionTo(TaskStatus.Failed)
        this.failureKind = failureKind
        task = task.copy(failureKind = failureKind, failureMessage = message)
    }

    override suspend fun markPaused(id: Long) {
        yield()
        transitionTo(TaskStatus.Paused)
        pauseCount += 1
    }

    override suspend fun markCancelled(id: Long) {
        yield()
        transitionTo(TaskStatus.Cancelled)
        cancelCount += 1
    }

    override suspend fun allocateFileName(id: Long, base: BaseFileName): String = base.value

    override suspend fun releaseFileName(id: Long) = Unit

    override suspend fun enqueue(text: String, quality: MusicQuality): EnqueueOutcome =
        error("not used by the download worker")

    override suspend fun enqueueLinks(links: List<SongLink>, quality: MusicQuality): EnqueueOutcome =
        error("not used by the download worker")

    override suspend fun markResolving(id: Long) = error("not used by the download worker")

    override suspend fun completeResolve(id: Long, track: ResolvedTrack) =
        error("not used by the download worker")

    override suspend fun updateStream(id: Long, track: ResolvedTrack) =
        error("not used by the download worker")

    override suspend fun resetForRetry(id: Long) = error("not used by the download worker")

    override suspend fun pause(id: Long) = error("not used by the download worker")

    override suspend fun resume(id: Long) = error("not used by the download worker")

    override suspend fun retry(id: Long) = error("not used by the download worker")

    override suspend fun deleteRecord(id: Long) = error("not used by the download worker")

    override suspend fun deleteRecords(ids: List<Long>) = error("not used by the download worker")

    override suspend fun clearCompleted() = error("not used by the download worker")

    override suspend fun pauseAll() = error("not used by the download worker")

    override suspend fun resumeAll() = error("not used by the download worker")

    override suspend fun pendingIds(): List<Long> = error("not used by the download worker")

    override suspend fun cancelAll() = error("not used by the download worker")

    override suspend fun retryAllFailed() = error("not used by the download worker")
}
