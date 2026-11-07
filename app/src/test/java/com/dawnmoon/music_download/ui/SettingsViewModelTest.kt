package com.dawnmoon.music_download.ui

import android.content.Context
import android.content.ContextWrapper
import app.cash.turbine.test
import com.dawnmoon.music_download.data.repository.AppSettings
import com.dawnmoon.music_download.data.repository.EnqueueOutcome
import com.dawnmoon.music_download.data.repository.KeyValidator
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
import com.dawnmoon.music_download.ui.history.HistoryActions
import com.dawnmoon.music_download.ui.viewmodel.KeyStatus
import com.dawnmoon.music_download.ui.viewmodel.SettingsEvent
import com.dawnmoon.music_download.ui.viewmodel.SettingsViewModel
import java.io.OutputStream
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [SettingsViewModel] 的 Key 校验、设置持久化与历史清空编排。
 *
 * Key 校验器、设置、额度、仓库与文件存储全部使用行为化 Fake：保存 Key 会真实改变
 * 设置的 Flow，额度写入会真实改变额度 Flow，删除文件失败会真实保留仓库记录。
 * 断言只针对 ViewModel 暴露的状态、一次性事件和这些用户可见结果。
 *
 * 清空历史通过注入的 [HistoryActions] 完成，与工作台历史 Tab 共用同一实现。
 * `Context` 与下载引擎只在组合根装配，[SettingsViewModel] 自身不直接依赖它们；
 * 因此本单元测试可以用 `ContextWrapper(null)` 构造真实的 [HistoryActions]。
 */
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var settings: FakeAppSettings
    private lateinit var validator: FakeKeyValidator
    private lateinit var quotaStore: FakeQuotaStore
    private lateinit var repo: FakeTaskRepository
    private lateinit var fileStore: FakeTaskFileStore

    private val appContext: Context = ContextWrapper(null)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        settings = FakeAppSettings()
        quotaStore = FakeQuotaStore()
        validator = FakeKeyValidator(quotaStore)
        repo = FakeTaskRepository()
        fileStore = FakeTaskFileStore()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region API Key 校验

    @Test
    fun `a locally malformed key is rejected without saving`() = runTest(dispatcher.scheduler) {
        // 校验器会返回带额度的成功结果：若本地检查被跳过，这次成功会写入额度快照。
        validator.result = KeyValidator.Result.Valid(
            Quota(freeRemaining = 37, paidRemaining = 120, limitPerMinute = 30),
        )
        val viewModel = createViewModel()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.onKeyChange("not-a-chksz-key")
        viewModel.onSaveKey()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.state.value.keyStatus is KeyStatus.Invalid)
        assertNull(settings.apiKeyFlow.value)
        // 本地格式非法不消耗额度的校验请求。
        assertNull(viewModel.state.value.quota)
    }

    @Test
    fun `a valid key is saved and the validated quota is displayed`() =
        runTest(dispatcher.scheduler) {
            val quota = Quota(freeRemaining = 37, paidRemaining = 120, limitPerMinute = 30)
            validator.result = KeyValidator.Result.Valid(quota)
            val viewModel = createViewModel()
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.onKeyChange("chksz_valid_key")
            viewModel.onSaveKey()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals("chksz_valid_key", settings.apiKeyFlow.value)
            assertEquals("", viewModel.state.value.apiKeyDraft)
            assertEquals(KeyStatus.Valid, viewModel.state.value.keyStatus)
            assertEquals(quota, viewModel.state.value.quota)
        }

    @Test
    fun `an invalid key is not saved and the server message is shown`() =
        runTest(dispatcher.scheduler) {
            validator.result = KeyValidator.Result.InvalidKey("API Key 已失效")
            val viewModel = createViewModel()
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.onKeyChange("chksz_revoked_key")
            viewModel.onSaveKey()
            dispatcher.scheduler.advanceUntilIdle()

            assertNull(settings.apiKeyFlow.value)
            assertEquals(
                KeyStatus.Invalid("API Key 已失效"),
                viewModel.state.value.keyStatus,
            )
        }

    @Test
    fun `an indeterminate result still saves the key and shows a warning`() =
        runTest(dispatcher.scheduler) {
            validator.result = KeyValidator.Result.Indeterminate("网络暂时不可用")
            val viewModel = createViewModel()
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.onKeyChange("chksz_maybe_valid")
            viewModel.onSaveKey()
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals("chksz_maybe_valid", settings.apiKeyFlow.value)
            // 无法判定时保存 Key，并进入警告状态；具体提示文案由界面层展示。
            assertTrue(viewModel.state.value.keyStatus is KeyStatus.NetworkError)
        }

    @Test
    fun `a draft edited while validating is not overwritten by the late result`() =
        runTest(dispatcher.scheduler) {
            validator.gateEnabled = true
            validator.result = KeyValidator.Result.Valid(quota = null)
            val viewModel = createViewModel()
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.onKeyChange("chksz_old_key")
            viewModel.onSaveKey()
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(KeyStatus.Validating, viewModel.state.value.keyStatus)

            // 模拟校验等待期间输入发生变化：迟到的结果不得保存旧 Key，也不得清空新草稿。
            viewModel.onKeyChange("chksz_new_key")
            validator.gate.complete(Unit)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals("chksz_new_key", viewModel.state.value.apiKeyDraft)
            assertNull(settings.apiKeyFlow.value)
            assertEquals(KeyStatus.Idle, viewModel.state.value.keyStatus)
        }

    @Test
    fun `an already saved key is masked and never backfilled into the draft`() =
        runTest(dispatcher.scheduler) {
            val savedKey = "chksz_saved_secret_1234"
            settings.apiKeyFlow.value = savedKey
            val viewModel = createViewModel()
            dispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertNotNull(state.savedKeyMasked)
            assertNotEquals(savedKey, state.savedKeyMasked)
            val masked = requireNotNull(state.savedKeyMasked)
            // 掩码必须隐藏完整 Key；仅"不等于原值"无法阻止实现返回 Key 加后缀。
            assertFalse(
                "saved key must not be recoverable from the mask: $masked",
                masked.contains(savedKey),
            )
            assertTrue("saved key mask must not be blank", masked.isNotBlank())
            assertEquals("", state.apiKeyDraft)
        }

    // endregion

    // region 下载与外观设置

    @Test
    fun `initial state comes from the persisted settings rather than defaults`() =
        runTest(dispatcher.scheduler) {
            settings.defaultQualityFlow.value = MusicQuality.Lossless
            settings.maxConcurrentDownloadsFlow.value = 5
            settings.themeModeFlow.value = ThemeMode.Dark
            val viewModel = createViewModel()
            dispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(MusicQuality.Lossless, state.defaultQuality)
            assertEquals(5, state.concurrency)
            assertEquals(ThemeMode.Dark, state.themeMode)
        }

    @Test
    fun `concurrency below one is clamped to one and persisted`() =
        runTest(dispatcher.scheduler) {
            val viewModel = createViewModel()
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.onConcurrencyChange(0)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, viewModel.state.value.concurrency)
            assertEquals(1, settings.maxConcurrentDownloadsFlow.value)
        }

    @Test
    fun `concurrency above five is clamped to five and persisted`() =
        runTest(dispatcher.scheduler) {
            val viewModel = createViewModel()
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.onConcurrencyChange(9)
            dispatcher.scheduler.advanceUntilIdle()

            assertEquals(5, viewModel.state.value.concurrency)
            assertEquals(5, settings.maxConcurrentDownloadsFlow.value)
        }

    @Test
    fun `the selected theme mode is persisted`() = runTest(dispatcher.scheduler) {
        val viewModel = createViewModel()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.onThemeModeChange(ThemeMode.Dark)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ThemeMode.Dark, viewModel.state.value.themeMode)
        assertEquals(ThemeMode.Dark, settings.themeModeFlow.value)
    }

    // endregion

    // region 清空历史

    @Test
    fun `clearing history from settings keeps retained records and reports their count`() =
        runTest(dispatcher.scheduler) {
            repo.completed.value = listOf(
                completedTask(id = 1L, mediaStoreId = 11L),
                completedTask(id = 2L, mediaStoreId = 12L),
            )
            fileStore.failingDeleteIds += 12L
            val viewModel = createViewModel()
            dispatcher.scheduler.advanceUntilIdle()

            viewModel.events.test {
                viewModel.onClearHistory(deleteFiles = true)
                dispatcher.scheduler.advanceUntilIdle()
                // 失败的条目保留；事件携带保留数量，界面据此提示用户。
                val event = awaitItem() as SettingsEvent.HistoryClearFailed
                assertEquals(1, event.retainedCount)
                cancelAndIgnoreRemainingEvents()
            }

            // 与工作台历史共用 HistoryActions：只有文件删除成功的记录被移除。
            assertEquals(listOf(2L), repo.completed.value.map { it.id })
            assertEquals(setOf(11L), fileStore.deletedIds)
        }

    // endregion

    private fun createViewModel(): SettingsViewModel =
        SettingsViewModel(
            settings = settings,
            keyValidator = validator,
            quotaStore = quotaStore,
            tasks = repo,
            historyActions = HistoryActions(repo, fileStore, FakeDownloadController(), appContext),
        )

    private fun completedTask(id: Long, mediaStoreId: Long?): DownloadTask =
        DownloadTask(
            id = id,
            songId = null,
            sourceUrl = "https://music.163.com/song?id=$id",
            requestedQuality = MusicQuality.Exhigh,
            actualQuality = null,
            title = "曲目 $id",
            artist = null,
            album = null,
            artworkUrl = null,
            format = null,
            sizeBytes = null,
            md5 = null,
            streamUrl = null,
            etag = null,
            bytesDownloaded = 1_000L,
            status = TaskStatus.Completed,
            mediaStoreId = mediaStoreId,
            fileName = "track_$id.mp3",
            failureKind = null,
            failureMessage = null,
            createdAt = id,
            updatedAt = id,
            completedAt = id,
        )

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

        override suspend fun setApiKey(value: String) {
            apiKeyFlow.value = value
        }

        override suspend fun setDefaultQuality(value: MusicQuality) {
            defaultQualityFlow.value = value
        }

        override suspend fun setMaxConcurrentDownloads(value: Int) {
            maxConcurrentDownloadsFlow.value = value
        }

        override suspend fun setThemeMode(value: ThemeMode) {
            themeModeFlow.value = value
        }

        override suspend fun markNotificationAsked() {
            notificationAskedFlow.value = true
        }
    }

    /**
     * 模拟真实 [KeyValidator] 经 ApiCaller 写额度快照的副作用；这样 ViewModel 只需
     * 观察额度 Flow 就能在验证成功后显示额度。
     */
    private class FakeKeyValidator(
        private val quotaStore: QuotaStore,
    ) : KeyValidator {

        val gate = CompletableDeferred<Unit>()

        var gateEnabled: Boolean = false

        var result: KeyValidator.Result = KeyValidator.Result.Valid(null)

        override suspend fun validate(apiKey: String): KeyValidator.Result {
            if (gateEnabled) gate.await()
            (result as? KeyValidator.Result.Valid)?.quota?.let { quotaStore.record(it) }
            return result
        }
    }

    private class FakeQuotaStore : QuotaStore {

        override val quota = MutableStateFlow<Quota?>(null)

        override suspend fun record(quota: Quota) {
            this.quota.value = quota
        }
    }

    private class FakeTaskRepository : TaskRepository {

        val inProgress = MutableStateFlow<List<DownloadTask>>(emptyList())
        val completed = MutableStateFlow<List<DownloadTask>>(emptyList())

        override fun observeInProgress(): Flow<List<DownloadTask>> = inProgress

        override fun observeCompleted(): Flow<List<DownloadTask>> = completed

        override suspend fun byId(id: Long): DownloadTask? =
            (inProgress.value + completed.value).firstOrNull { it.id == id }

        override suspend fun enqueue(text: String, quality: MusicQuality): EnqueueOutcome =
            error("unused")

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

        override suspend fun deleteRecord(id: Long) {
            completed.value = completed.value.filterNot { it.id == id }
        }

        override suspend fun deleteRecords(ids: List<Long>) {
            val removed = ids.toSet()
            completed.value = completed.value.filterNot { it.id in removed }
        }

        override suspend fun clearCompleted() {
            completed.value = emptyList()
        }

        override suspend fun pauseAll() = error("unused")

        override suspend fun resumeAll() = error("unused")

        override suspend fun pendingIds(): List<Long> = error("unused")

        override suspend fun cancelAll() = error("unused")

        override suspend fun retryAllFailed() = error("unused")

        override suspend fun allocateFileName(id: Long, base: BaseFileName): String =
            error("unused")

        override suspend fun releaseFileName(id: Long) = error("unused")
    }

    private class FakeTaskFileStore : TaskFileStore {

        val failingDeleteIds = mutableSetOf<Long>()

        val deletedIds = mutableSetOf<Long>()

        override suspend fun createPending(displayName: String, mimeType: String): CreatedFile =
            error("unused")

        override suspend fun openAppend(file: CreatedFile): OutputStream = error("unused")

        override suspend fun openTruncate(file: CreatedFile): OutputStream = error("unused")

        override suspend fun complete(file: CreatedFile) = error("unused")

        override suspend fun delete(file: CreatedFile) = error("unused")

        override suspend fun sizeOf(file: CreatedFile): Long = error("unused")

        override suspend fun md5Of(file: CreatedFile): String = error("unused")

        override suspend fun deleteById(mediaStoreId: Long): Boolean {
            if (mediaStoreId in failingDeleteIds) return false
            deletedIds += mediaStoreId
            return true
        }
    }

    /** [HistoryActions] 的构造依赖下载引擎；清空历史不会调用它，因此只需空实现。 */
    private class FakeDownloadController : DownloadController {

        override val snapshot: StateFlow<EngineSnapshot> = MutableStateFlow(
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

        override suspend fun attach(scope: kotlinx.coroutines.CoroutineScope) = Unit

        override fun detach() = Unit

        override fun start(context: Context) = Unit

        override fun requestDownload(ids: List<Long>) = Unit

        override fun pause(id: Long) = Unit

        override fun resume(id: Long) = Unit

        override fun cancel(id: Long) = Unit

        override fun retry(id: Long) = Unit

        override fun pauseAll() = Unit

        override fun resumeAll() = Unit

        override fun cancelAll() = Unit

        override fun retryAllFailed() = Unit
    }
}
