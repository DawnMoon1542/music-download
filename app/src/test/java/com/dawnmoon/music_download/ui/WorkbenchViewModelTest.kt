package com.dawnmoon.music_download.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.dawnmoon.music_download.R
import com.dawnmoon.music_download.core.AppClock
import com.dawnmoon.music_download.data.remote.ApiFailure
import com.dawnmoon.music_download.data.repository.AppSettings
import com.dawnmoon.music_download.data.repository.EnqueueFailure
import com.dawnmoon.music_download.data.repository.EnqueueOutcome
import com.dawnmoon.music_download.data.repository.QuotaStore
import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.model.BaseFileName
import com.dawnmoon.music_download.domain.model.DownloadTask
import com.dawnmoon.music_download.domain.model.FailureKind
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.Quota
import com.dawnmoon.music_download.domain.model.ResolvedTrack
import com.dawnmoon.music_download.domain.model.SongLink
import com.dawnmoon.music_download.domain.model.TaskStatus
import com.dawnmoon.music_download.domain.model.ThemeMode
import com.dawnmoon.music_download.download.CreatedFile
import com.dawnmoon.music_download.download.DownloadController
import com.dawnmoon.music_download.download.EngineSnapshot
import com.dawnmoon.music_download.download.TaskFileStore
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchEvent
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchTab
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchViewModel
import java.io.File
import java.io.OutputStream
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.w3c.dom.Element

/**
 * [WorkbenchViewModel] 的工作台状态与入队结果测试。
 *
 * 仓库、引擎、文件、额度与设置都用 Fake 提供固定输入；断言只针对 ViewModel 对外暴露的
 * 状态或事件，不检查 Fake 的内部调用顺序。
 */
class WorkbenchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var repo: FakeTaskRepository
    private lateinit var settings: FakeAppSettings
    private lateinit var engine: FakeDownloadController
    private lateinit var fileStore: FakeTaskFileStore
    private lateinit var quotaStore: FakeQuotaStore
    private lateinit var appContext: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeTaskRepository()
        settings = FakeAppSettings()
        engine = FakeDownloadController()
        fileStore = FakeTaskFileStore()
        quotaStore = FakeQuotaStore()
        // 用能解析 strings.xml 的 Context 替换空壳，保证 ViewModel 通过
        // context.resources.getString(...) 取文案时不会走到未实现的 Android 方法。
        appContext = ResourceBackedContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region 初始状态

    @Test
    fun `starts with the default quality from settings and the in progress tab`() =
        runTest(dispatcher.scheduler) {
            settings.defaultQualityFlow.value = MusicQuality.Lossless

            val viewModel = createViewModel()
            dispatcher.scheduler.runCurrent()

            assertEquals(MusicQuality.Lossless, viewModel.state.value.selectedQuality)
            assertEquals(WorkbenchTab.InProgress, viewModel.state.value.selectedTab)
        }

    // endregion

    // region 进行中列表

    @Test
    fun `cancelled tasks remain in the in progress list`() =
        runTest(dispatcher.scheduler) {
            repo.inProgress.value = listOf(
                task(id = 1L, status = TaskStatus.Cancelled),
            )
            val viewModel = createViewModel()
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf(1L), viewModel.state.value.inProgress.map { it.id })
        }

    // endregion

    // region 引擎控制

    @Test
    fun `single task controls are forwarded to the download engine`() =
        runTest(dispatcher.scheduler) {
            val viewModel = createViewModel()
            dispatcher.scheduler.runCurrent()

            viewModel.onPause(11L)
            viewModel.onResume(12L)
            viewModel.onCancel(13L)
            viewModel.onRetry(14L)

            assertEquals(listOf(11L), engine.pausedIds)
            assertEquals(listOf(12L), engine.resumedIds)
            assertEquals(listOf(13L), engine.cancelledIds)
            assertEquals(listOf(14L), engine.retriedIds)
        }

    @Test
    fun `batch controls are forwarded to the download engine`() =
        runTest(dispatcher.scheduler) {
            val viewModel = createViewModel()
            dispatcher.scheduler.runCurrent()

            viewModel.onPauseAll()
            viewModel.onResumeAll()
            viewModel.onCancelAll()
            viewModel.onRetryAllFailed()

            assertEquals(1, engine.pauseAllCount)
            assertEquals(1, engine.resumeAllCount)
            assertEquals(1, engine.cancelAllCount)
            assertEquals(1, engine.retryAllFailedCount)
        }

    // endregion

    // region 入队结果

    @Test
    fun `enqueue starts the engine and reports the created task count`() =
        runTest(dispatcher.scheduler) {
            repo.enqueueOutcome = enqueued(created = listOf(1L))
            val viewModel = createViewModel()
            viewModel.onInputChange("https://music.163.com/song?id=1")
            dispatcher.scheduler.runCurrent()

            // 先切走，才能验证入队成功后实现确实把用户带回"进行中"，而不是碰巧停在初始 Tab。
            viewModel.onTabChange(WorkbenchTab.Completed)
            dispatcher.scheduler.runCurrent()
            assertEquals(WorkbenchTab.Completed, viewModel.state.value.selectedTab)

            viewModel.events.test {
                viewModel.onEnqueue()
                dispatcher.scheduler.runCurrent()
                assertEquals(
                    WorkbenchEvent.Snackbar("已加入 1 个任务"),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(1, engine.startCount)
            assertEquals(listOf(1L), engine.requestedIds)
            assertEquals("", viewModel.state.value.input)
            assertEquals(WorkbenchTab.InProgress, viewModel.state.value.selectedTab)
        }

    @Test
    fun `enqueue reports duplicates when nothing new is created`() =
        runTest(dispatcher.scheduler) {
            repo.enqueueOutcome = enqueued(duplicates = 2)
            val viewModel = createViewModel()
            viewModel.onInputChange("https://music.163.com/song?id=1")
            dispatcher.scheduler.runCurrent()

            viewModel.events.test {
                viewModel.onEnqueue()
                dispatcher.scheduler.runCurrent()
                val message = awaitSnackbar()
                assertTrue(message.contains("2"))
                assertTrue(message.contains("重复") || message.contains("已在队列中"))
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(0, engine.startCount)
            assertTrue(engine.requestedIds.isEmpty())
        }

    @Test
    fun `enqueue reports unrecognized links when nothing new is created`() =
        runTest(dispatcher.scheduler) {
            repo.enqueueOutcome = enqueued(failures = listOf(linkFailure()))
            val viewModel = createViewModel()
            viewModel.onInputChange("https://163cn.tv/unknown")
            dispatcher.scheduler.runCurrent()

            viewModel.events.test {
                viewModel.onEnqueue()
                dispatcher.scheduler.runCurrent()
                val message = awaitSnackbar()
                assertTrue(message.contains("1"))
                assertTrue(message.contains("无法识别"))
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(0, engine.startCount)
            assertTrue(engine.requestedIds.isEmpty())
        }

    @Test
    fun `enqueue reports duplicates and unrecognized links together`() =
        runTest(dispatcher.scheduler) {
            repo.enqueueOutcome = enqueued(
                duplicates = 2,
                failures = listOf(linkFailure()),
            )
            val viewModel = createViewModel()
            viewModel.onInputChange("https://163cn.tv/mixed")
            dispatcher.scheduler.runCurrent()

            viewModel.events.test {
                viewModel.onEnqueue()
                dispatcher.scheduler.runCurrent()
                val message = awaitSnackbar()
                assertTrue(message.contains("2"))
                assertTrue(message.contains("1"))
                assertTrue(message.contains("无法识别"))
                assertTrue(message.contains("重复") || message.contains("已在队列中"))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `enqueue appends skipped and unrecognized counts to the created summary`() =
        runTest(dispatcher.scheduler) {
            repo.enqueueOutcome = enqueued(
                created = listOf(1L, 2L),
                duplicates = 2,
                failures = listOf(linkFailure()),
            )
            val viewModel = createViewModel()
            viewModel.onInputChange("https://music.163.com/song?id=1 https://163cn.tv/mixed")
            dispatcher.scheduler.runCurrent()

            viewModel.events.test {
                viewModel.onEnqueue()
                dispatcher.scheduler.runCurrent()
                val message = awaitSnackbar()
                assertTrue(message.contains("已加入 2 个任务"))
                assertTrue(message.contains("跳过 2 个已在队列中的歌曲"))
                assertTrue(message.contains("1 个链接无法识别"))
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(1, engine.startCount)
            assertEquals(listOf(1L, 2L), engine.requestedIds)
        }

    @Test
    fun `enqueue without an api key opens settings focused on the key field`() =
        runTest(dispatcher.scheduler) {
            repo.enqueueOutcome = EnqueueOutcome.MissingApiKey(extractedCount = 1)
            val viewModel = createViewModel()
            viewModel.onInputChange("https://music.163.com/song?id=1")
            dispatcher.scheduler.runCurrent()

            viewModel.events.test {
                viewModel.onEnqueue()
                dispatcher.scheduler.runCurrent()
                assertEquals(WorkbenchEvent.OpenSettings(focusKey = true), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(1, repo.enqueueCalls)
            assertEquals(0, engine.startCount)
        }

    @Test
    fun `enqueue without recognizable links always delegates to the repository`() =
        runTest(dispatcher.scheduler) {
            repo.enqueueOutcome = EnqueueOutcome.NoLinks(ignoredUrlCount = 0)
            val viewModel = createViewModel()
            viewModel.onInputChange("没有链接的文本")
            dispatcher.scheduler.runCurrent()

            viewModel.events.test {
                viewModel.onEnqueue()
                dispatcher.scheduler.runCurrent()
                assertTrue(awaitSnackbar().contains("未找到网易云歌曲链接"))
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(1, repo.enqueueCalls)
            assertEquals("没有链接的文本", repo.lastEnqueueText)
            assertEquals(MusicQuality.Exhigh, repo.lastEnqueueQuality)
            assertEquals(0, engine.startCount)
        }

    @Test
    fun `enqueue marks itself busy until the repository returns`() =
        runTest(dispatcher.scheduler) {
            val gate = CompletableDeferred<Unit>()
            repo.enqueueGate = gate
            repo.enqueueOutcome = enqueued(created = listOf(1L))
            val viewModel = createViewModel()
            viewModel.onInputChange("https://music.163.com/song?id=1")
            dispatcher.scheduler.runCurrent()

            viewModel.onEnqueue()
            dispatcher.scheduler.runCurrent()
            assertTrue(viewModel.state.value.isEnqueuing)

            gate.complete(Unit)
            dispatcher.scheduler.runCurrent()
            assertFalse(viewModel.state.value.isEnqueuing)
        }

    // endregion

    // region 历史搜索

    @Test
    fun `history filter matches title case insensitively`() =
        runTest(dispatcher.scheduler) {
            repo.completed.value = historyTasks()
            val viewModel = createViewModel()
            dispatcher.scheduler.runCurrent()

            viewModel.onHistoryQueryChange("night")
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf(1L), viewModel.state.value.completed.map { it.id })
        }

    @Test
    fun `history filter matches artist case insensitively`() =
        runTest(dispatcher.scheduler) {
            repo.completed.value = historyTasks()
            val viewModel = createViewModel()
            dispatcher.scheduler.runCurrent()

            viewModel.onHistoryQueryChange("AURORA")
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf(2L), viewModel.state.value.completed.map { it.id })
        }

    @Test
    fun `history filter matches file name case insensitively`() =
        runTest(dispatcher.scheduler) {
            repo.completed.value = historyTasks()
            val viewModel = createViewModel()
            dispatcher.scheduler.runCurrent()

            viewModel.onHistoryQueryChange("TRACK_01")
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf(3L), viewModel.state.value.completed.map { it.id })
        }

    // endregion

    // region 失败文案

    @Test
    fun `failed tasks expose the designed localized label and keep the raw message`() =
        runTest(dispatcher.scheduler) {
            repo.inProgress.value = FailureKind.entries.mapIndexed { index, kind ->
                task(
                    id = index + 1L,
                    status = TaskStatus.Failed,
                    failureKind = kind,
                    failureMessage = "raw-${kind.name}",
                )
            }
            val viewModel = createViewModel()
            dispatcher.scheduler.runCurrent()

            val models = viewModel.state.value.inProgress
            assertEquals(FailureKind.entries.size, models.size)

            val kindsById = FailureKind.entries
                .mapIndexed { index, kind -> (index + 1L) to kind }
                .toMap()
            models.forEach { model ->
                val kind = kindsById.getValue(model.id)
                val expected = failureLabel(kind)
                if (kind in FAILURE_LABELS_WITH_MESSAGE) {
                    assertTrue(
                        "${kind.name} label was '${model.statusLabel}'",
                        model.statusLabel.startsWith(expected),
                    )
                } else {
                    assertEquals("${kind.name} label", expected, model.statusLabel)
                }
                assertEquals("raw-${kind.name}", model.failureMessage)
            }
        }

    // endregion

    // region 速度

    @Test
    fun `speed reflects the byte delta within one second`() =
        runTest(dispatcher.scheduler) {
            repo.inProgress.value = listOf(
                task(id = 1L, status = TaskStatus.Downloading, bytesDownloaded = 0),
            )
            val clock = FakeClock()
            val viewModel = createViewModel(clock)
            dispatcher.scheduler.runCurrent()

            clock.elapsedMillis = 1_000
            dispatcher.scheduler.advanceTimeBy(1_000)
            dispatcher.scheduler.runCurrent()

            repo.inProgress.value = listOf(
                task(id = 1L, status = TaskStatus.Downloading, bytesDownloaded = TWO_MIB),
            )
            clock.elapsedMillis = 2_000
            dispatcher.scheduler.advanceTimeBy(1_000)
            dispatcher.scheduler.runCurrent()

            assertEquals(
                TWO_MIB,
                requireNotNull(viewModel.state.value.inProgress.single().speedBytesPerSecond),
            )
        }

    @Test
    fun `speed is null after a task is paused`() =
        runTest(dispatcher.scheduler) {
            repo.inProgress.value = listOf(
                task(id = 1L, status = TaskStatus.Downloading, bytesDownloaded = 0),
            )
            val clock = FakeClock()
            val viewModel = createViewModel(clock)
            dispatcher.scheduler.runCurrent()

            clock.elapsedMillis = 1_000
            dispatcher.scheduler.advanceTimeBy(1_000)
            dispatcher.scheduler.runCurrent()

            repo.inProgress.value = listOf(
                task(id = 1L, status = TaskStatus.Paused, bytesDownloaded = TWO_MIB),
            )
            clock.elapsedMillis = 2_000
            dispatcher.scheduler.advanceTimeBy(1_000)
            dispatcher.scheduler.runCurrent()

            assertNull(viewModel.state.value.inProgress.single().speedBytesPerSecond)
        }

    @Test
    fun `speed resets when progress moves backwards after a reconnect`() =
        runTest(dispatcher.scheduler) {
            repo.inProgress.value = listOf(
                task(id = 1L, status = TaskStatus.Downloading, bytesDownloaded = 0),
            )
            val clock = FakeClock()
            val viewModel = createViewModel(clock)
            dispatcher.scheduler.runCurrent()

            clock.elapsedMillis = 1_000
            dispatcher.scheduler.advanceTimeBy(1_000)
            dispatcher.scheduler.runCurrent()

            repo.inProgress.value = listOf(
                task(id = 1L, status = TaskStatus.Downloading, bytesDownloaded = TWO_MIB),
            )
            clock.elapsedMillis = 2_000
            dispatcher.scheduler.advanceTimeBy(1_000)
            dispatcher.scheduler.runCurrent()
            assertEquals(
                TWO_MIB,
                requireNotNull(viewModel.state.value.inProgress.single().speedBytesPerSecond),
            )

            // 重连后进度回退：差值不再是有效速度，不能显示负值或沿用旧值。
            repo.inProgress.value = listOf(
                task(id = 1L, status = TaskStatus.Downloading, bytesDownloaded = TWO_MIB / 4),
            )
            clock.elapsedMillis = 3_000
            dispatcher.scheduler.advanceTimeBy(1_000)
            dispatcher.scheduler.runCurrent()

            assertNull(viewModel.state.value.inProgress.single().speedBytesPerSecond)
        }

    @Test
    fun `speed is null for a completed task`() =
        runTest(dispatcher.scheduler) {
            repo.completed.value = listOf(
                task(
                    id = 1L,
                    status = TaskStatus.Completed,
                    bytesDownloaded = TWO_MIB,
                    completedAt = 1_000,
                ),
            )
            val viewModel = createViewModel()
            dispatcher.scheduler.runCurrent()

            assertNull(viewModel.state.value.completed.single().speedBytesPerSecond)
        }

    // endregion

    private fun createViewModel(clock: AppClock = FakeClock()): WorkbenchViewModel =
        WorkbenchViewModel(
            repo = repo,
            settings = settings,
            engine = engine,
            fileStore = fileStore,
            quotaStore = quotaStore,
            appContext = appContext,
            clock = clock,
        )

    private fun enqueued(
        created: List<Long> = emptyList(),
        duplicates: Int = 0,
        failures: List<EnqueueFailure> = emptyList(),
    ): EnqueueOutcome.Enqueued =
        EnqueueOutcome.Enqueued(
            created = created,
            duplicates = duplicates,
            ignoredUrlCount = 0,
            failures = failures,
        )

    private fun linkFailure(): EnqueueFailure =
        EnqueueFailure(
            sourceUrl = "https://163cn.tv/unknown",
            failure = ApiFailure.LinkUnsupported("unsupported"),
        )

    private fun historyTasks(): List<DownloadTask> = listOf(
        task(
            id = 1L,
            status = TaskStatus.Completed,
            title = "Night Drive",
            artist = "Kara",
            fileName = "night_drive.mp3",
            completedAt = 1_000,
        ),
        task(
            id = 2L,
            status = TaskStatus.Completed,
            title = "Ordinary Song",
            artist = "Aurora",
            fileName = "ordinary_song.mp3",
            completedAt = 2_000,
        ),
        task(
            id = 3L,
            status = TaskStatus.Completed,
            title = "Another One",
            artist = "Mira",
            fileName = "track_01.mp3",
            completedAt = 3_000,
        ),
    )

    private fun task(
        id: Long,
        status: TaskStatus,
        title: String? = null,
        artist: String? = null,
        fileName: String? = null,
        bytesDownloaded: Long = 0,
        failureKind: FailureKind? = null,
        failureMessage: String? = null,
        completedAt: Long? = null,
    ): DownloadTask =
        DownloadTask(
            id = id,
            songId = null,
            sourceUrl = "https://music.163.com/song?id=$id",
            requestedQuality = MusicQuality.Exhigh,
            actualQuality = null,
            title = title,
            artist = artist,
            album = null,
            artworkUrl = null,
            format = null,
            sizeBytes = null,
            md5 = null,
            streamUrl = null,
            etag = null,
            bytesDownloaded = bytesDownloaded,
            status = status,
            mediaStoreId = null,
            fileName = fileName,
            failureKind = failureKind,
            failureMessage = failureMessage,
            createdAt = id,
            updatedAt = id,
            completedAt = completedAt,
        )

    private suspend fun ReceiveTurbine<WorkbenchEvent>.awaitSnackbar(): String =
        (awaitItem() as WorkbenchEvent.Snackbar).message

    private class FakeTaskRepository : TaskRepository {

        val inProgress = MutableStateFlow<List<DownloadTask>>(emptyList())
        val completed = MutableStateFlow<List<DownloadTask>>(emptyList())

        var enqueueOutcome: EnqueueOutcome = EnqueueOutcome.Enqueued(
            created = emptyList(),
            duplicates = 0,
            ignoredUrlCount = 0,
            failures = emptyList(),
        )
        var enqueueGate: CompletableDeferred<Unit>? = null
        var enqueueCalls: Int = 0
        var lastEnqueueText: String? = null
        var lastEnqueueQuality: MusicQuality? = null

        override fun observeInProgress(): Flow<List<DownloadTask>> = inProgress

        override fun observeCompleted(): Flow<List<DownloadTask>> = completed

        override suspend fun byId(id: Long): DownloadTask? =
            (inProgress.value + completed.value).firstOrNull { it.id == id }

        override suspend fun enqueue(text: String, quality: MusicQuality): EnqueueOutcome {
            enqueueCalls += 1
            lastEnqueueText = text
            lastEnqueueQuality = quality
            enqueueGate?.await()
            return enqueueOutcome
        }

        override suspend fun enqueueLinks(
            links: List<SongLink>,
            quality: MusicQuality,
        ): EnqueueOutcome = error("unused")

        override suspend fun markResolving(id: Long) = error("unused")

        override suspend fun completeResolve(id: Long, track: ResolvedTrack) = error("unused")

        override suspend fun startDownloading(id: Long) = error("unused")

        override suspend fun updateProgress(id: Long, bytesDownloaded: Long) = error("unused")

        override suspend fun updateStream(id: Long, track: ResolvedTrack) = error("unused")

        override suspend fun markCompleted(
            id: Long,
            fileName: String,
            mediaStoreId: Long,
            completedAt: Long,
        ) = error("unused")

        override suspend fun markFailed(
            id: Long,
            failureKind: FailureKind,
            message: String,
        ) = error("unused")

        override suspend fun markPaused(id: Long) = error("unused")

        override suspend fun markCancelled(id: Long) = error("unused")

        override suspend fun resetForRetry(id: Long) = error("unused")

        override suspend fun attachFile(
            id: Long,
            fileName: String,
            mediaStoreId: Long,
            bytesDownloaded: Long,
        ) = error("unused")

        override suspend fun pause(id: Long) = error("unused")

        override suspend fun resume(id: Long) = error("unused")

        override suspend fun retry(id: Long) = error("unused")

        override suspend fun deleteRecord(id: Long) = error("unused")

        override suspend fun deleteRecords(ids: List<Long>) = error("unused")

        override suspend fun clearCompleted() = error("unused")

        override suspend fun pauseAll() = error("unused")

        override suspend fun resumeAll() = error("unused")

        override suspend fun pendingIds(): List<Long> = error("unused")

        override suspend fun cancelAll() = error("unused")

        override suspend fun retryAllFailed() = error("unused")

        override suspend fun allocateFileName(id: Long, base: BaseFileName): String =
            error("unused")

        override suspend fun releaseFileName(id: Long) = error("unused")
    }

    private class FakeDownloadController : DownloadController {

        override val snapshot: StateFlow<EngineSnapshot> =
            MutableStateFlow(
                EngineSnapshot(
                    activeCount = 0,
                    completedInSession = 0,
                    totalInSession = 0,
                    bytesDone = 0,
                    bytesTotal = null,
                    speedBytesPerSecond = 0,
                    isPaused = false,
                ),
            )

        var startCount: Int = 0
        val requestedIds = mutableListOf<Long>()
        val pausedIds = mutableListOf<Long>()
        val resumedIds = mutableListOf<Long>()
        val cancelledIds = mutableListOf<Long>()
        val retriedIds = mutableListOf<Long>()
        var pauseAllCount: Int = 0
        var resumeAllCount: Int = 0
        var cancelAllCount: Int = 0
        var retryAllFailedCount: Int = 0

        override suspend fun attach(scope: kotlinx.coroutines.CoroutineScope) = Unit

        override fun detach() = Unit

        override fun start(context: Context) {
            startCount += 1
        }

        override fun requestDownload(ids: List<Long>) {
            requestedIds += ids
        }

        override fun pause(id: Long) {
            pausedIds += id
        }

        override fun resume(id: Long) {
            resumedIds += id
        }

        override fun cancel(id: Long) {
            cancelledIds += id
        }

        override fun retry(id: Long) {
            retriedIds += id
        }

        override fun pauseAll() {
            pauseAllCount += 1
        }

        override fun resumeAll() {
            resumeAllCount += 1
        }

        override fun cancelAll() {
            cancelAllCount += 1
        }

        override fun retryAllFailed() {
            retryAllFailedCount += 1
        }
    }

    private class FakeTaskFileStore : TaskFileStore {

        override suspend fun createPending(displayName: String, mimeType: String): CreatedFile =
            error("unused")

        override suspend fun openAppend(file: CreatedFile): OutputStream = error("unused")

        override suspend fun openTruncate(file: CreatedFile): OutputStream = error("unused")

        override suspend fun complete(file: CreatedFile) = error("unused")

        override suspend fun delete(file: CreatedFile) = error("unused")

        override suspend fun sizeOf(file: CreatedFile): Long = error("unused")

        override suspend fun md5Of(file: CreatedFile): String = error("unused")

        override suspend fun deleteById(mediaStoreId: Long): Boolean = error("unused")
    }

    private class FakeQuotaStore : QuotaStore {

        override val quota = MutableStateFlow<Quota?>(null)

        override suspend fun record(quota: Quota) = error("unused")
    }

    private class FakeAppSettings : AppSettings {

        val apiKeyFlow = MutableStateFlow<String?>(null)
        val defaultQualityFlow = MutableStateFlow(MusicQuality.Exhigh)
        val maxConcurrentDownloadsFlow = MutableStateFlow(3)
        val themeModeFlow = MutableStateFlow(ThemeMode.System)
        val notificationAskedFlow = MutableStateFlow(false)

        override val apiKey: Flow<String?> = apiKeyFlow
        override val defaultQuality: Flow<MusicQuality> = defaultQualityFlow
        override val maxConcurrentDownloads: Flow<Int> = maxConcurrentDownloadsFlow
        override val themeMode: Flow<ThemeMode> = themeModeFlow
        override val notificationAsked: Flow<Boolean> = notificationAskedFlow

        override suspend fun setApiKey(value: String) = error("unused")

        override suspend fun setDefaultQuality(value: MusicQuality) {
            defaultQualityFlow.value = value
        }

        override suspend fun setMaxConcurrentDownloads(value: Int) = error("unused")

        override suspend fun setThemeMode(value: ThemeMode) = error("unused")

        override suspend fun markNotificationAsked() = error("unused")
    }

    private class FakeClock(
        var elapsedMillis: Long = 0,
        var nowMillis: Long = 0,
    ) : AppClock {

        override fun nowMillis(): Long = nowMillis

        override fun elapsedRealtime(): Long = elapsedMillis
    }

    private companion object {
        const val TWO_MIB: Long = 2L * 1024 * 1024

        /** 设计中的失败文案；带 {msg} 的条目只校验固定前缀，原文由 failureMessage 单独展示。 */
        val FAILURE_LABELS: Map<FailureKind, String> = mapOf(
            FailureKind.Network to "网络错误，请检查网络后重试",
            FailureKind.InvalidKey to "API Key 无效，请到设置中检查",
            FailureKind.QuotaExhausted to "今日额度已用尽，可等待次日重置或兑换额度",
            FailureKind.Forbidden to "访问被拒绝",
            FailureKind.RateLimited to "请求过于频繁，请稍后再试",
            FailureKind.InvalidRequest to "请求参数异常",
            FailureKind.QualityUnavailable to "该歌曲在所选及更低音质下均不可用",
            FailureKind.NotFound to "资源不存在",
            FailureKind.ServiceUnavailable to "服务暂时不可用，请稍后重试",
            FailureKind.Malformed to "接口返回异常，请稍后重试",
            FailureKind.StreamExpired to "下载链接已失效，重试将重新解析",
            FailureKind.Integrity to "文件校验失败，已删除不完整文件",
            FailureKind.Storage to "存储空间不足",
            FailureKind.Unknown to "下载失败",
        )

        val FAILURE_LABELS_WITH_MESSAGE: Set<FailureKind> = setOf(
            FailureKind.Forbidden,
            FailureKind.InvalidRequest,
            FailureKind.NotFound,
            FailureKind.Unknown,
        )

        fun failureLabel(kind: FailureKind): String = FAILURE_LABELS.getValue(kind)
    }
}

/**
 * JVM 单元测试运行在 mockable android.jar 上：`Context.getString` 是 final 且方法体未实现，
 * 调用会抛 "not mocked"。这里覆写 [getResources]，让 ViewModel 通过
 * `context.resources.getString(...)` 读取 strings.xml 的文案，避免为了测试硬编码中文。
 */
private class ResourceBackedContext : ContextWrapper(null) {

    private val localizedResources = LocalizedResources()

    override fun getApplicationContext(): Context = this

    override fun getResources(): Resources = localizedResources
}

/**
 * 只实现文案查询的 [Resources]。mockable android.jar 中 AssetManager 的构造器是包级私有，
 * 只能反射创建；文案值直接读取 strings.xml，因此后续新增文案键无需改动测试。
 */
@Suppress("DEPRECATION")
private class LocalizedResources : Resources(
    newAssetManager(),
    DisplayMetrics(),
    Configuration(),
) {

    override fun getString(id: Int): String = valueOf(id)

    override fun getString(id: Int, vararg formatArgs: Any?): String =
        String.format(Locale.ROOT, valueOf(id), *formatArgs)

    override fun getText(id: Int): CharSequence = valueOf(id)

    private fun valueOf(id: Int): String {
        val name = NAME_BY_ID[id] ?: error("Unknown string resource id: $id")
        return STRINGS[name] ?: error("Missing string resource: $name")
    }

    private companion object {

        val NAME_BY_ID: Map<Int, String> = R.string::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType }
            .associate { it.getInt(null) to it.name }

        val STRINGS: Map<String, String> = readStringsXml()
    }
}

private fun newAssetManager(): AssetManager =
    AssetManager::class.java.getDeclaredConstructor()
        .apply { isAccessible = true }
        .newInstance()

private fun readStringsXml(): Map<String, String> {
    val xml = findStringsXml()
    val nodes = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(xml)
        .getElementsByTagName("string")
    return (0 until nodes.length)
        .map { nodes.item(it) as Element }
        .associate { it.getAttribute("name") to it.textContent.trim() }
}

/** 兼容从仓库根目录或 app 模块目录启动测试两种工作目录。 */
private fun findStringsXml(): File {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
        File(dir, "src/main/res/values/strings.xml").takeIf { it.isFile }?.let { return it }
        File(dir, "app/src/main/res/values/strings.xml").takeIf { it.isFile }?.let { return it }
        dir = dir.parentFile
    }
    error("strings.xml not found from ${File("").absolutePath}")
}
