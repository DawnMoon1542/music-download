package com.dawnmoon.music_download.ui

import kotlinx.coroutines.flow.map
import com.dawnmoon.music_download.domain.model.ApiCredentials
import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dawnmoon.music_download.core.AppClock
import com.dawnmoon.music_download.data.repository.AppSettings
import com.dawnmoon.music_download.data.repository.EnqueueOutcome
import com.dawnmoon.music_download.data.repository.QuotaStore
import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.model.BaseFileName
import com.dawnmoon.music_download.domain.model.DownloadTask
import com.dawnmoon.music_download.domain.model.FailureKind
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.Quota
import com.dawnmoon.music_download.domain.model.ResolvedTrack
import com.dawnmoon.music_download.domain.model.SongId
import com.dawnmoon.music_download.domain.model.SongLink
import com.dawnmoon.music_download.domain.model.TaskStatus
import com.dawnmoon.music_download.domain.model.ThemeMode
import com.dawnmoon.music_download.download.CreatedFile
import com.dawnmoon.music_download.download.DownloadController
import com.dawnmoon.music_download.download.EngineSnapshot
import com.dawnmoon.music_download.download.TaskFileStore
import com.dawnmoon.music_download.ui.theme.MusicDownloadTheme
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchUiState
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchViewModel
import com.dawnmoon.music_download.ui.workbench.WorkbenchCallbacks
import com.dawnmoon.music_download.ui.workbench.WorkbenchScreen
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_API_BASE_URL = "https://api.test"

/**
 * 错误路径的界面文案。
 *
 * 402/403/429 不依赖真实 API 是否在测试时触发：Fake [TaskRepository] 直接写入带
 * [FailureKind] 的失败任务，由真实的 [WorkbenchViewModel] 映射为本地化文案后再渲染。
 */
@RunWith(AndroidJUnit4::class)
class ErrorStateScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val repo = FakeTaskRepository()
    private val settings = FakeAppSettings()
    private val engine = FakeDownloadController()
    private val fileStore = FakeTaskFileStore()
    private val quotaStore = FakeQuotaStore()
    private val appContext: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun quotaExhaustedShowsTheDesignedMessage() {
        showFailure(FailureKind.QuotaExhausted, "free quota exhausted")

        composeRule.onNodeWithText(
            "今日额度已用尽，可等待次日重置或兑换额度",
        ).assertIsDisplayed()
    }

    @Test
    fun forbiddenShowsTheDesignedMessageAndRawReason() {
        showFailure(FailureKind.Forbidden, "账号已被封禁")

        composeRule.onNodeWithText("访问被拒绝", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("账号已被封禁", substring = true).assertIsDisplayed()
    }

    @Test
    fun rateLimitedShowsTheDesignedMessage() {
        showFailure(FailureKind.RateLimited, "too many requests")

        composeRule.onNodeWithText("请求过于频繁，请稍后再试").assertIsDisplayed()
    }

    private fun showFailure(kind: FailureKind, message: String) {
        repo.inProgress.value = listOf(failedTask(kind, message))
        val viewModel = WorkbenchViewModel(
            repo = repo,
            settings = settings,
            engine = engine,
            fileStore = fileStore,
            quotaStore = quotaStore,
            appContext = appContext,
            clock = FakeClock(),
        )
        composeRule.setContent {
            val state: WorkbenchUiState by viewModel.state.collectAsState()
            MusicDownloadTheme(themeMode = ThemeMode.Light) {
                WorkbenchScreen(state = state, callbacks = WorkbenchCallbacks())
            }
        }
        composeRule.waitForIdle()
    }

    private fun failedTask(kind: FailureKind, message: String): DownloadTask = DownloadTask(
        id = 1L,
        songId = SongId(2L),
        sourceUrl = "https://music.163.com/song?id=2",
        requestedQuality = MusicQuality.Exhigh,
        actualQuality = null,
        title = "夏霞",
        artist = "あたらよ",
        album = null,
        artworkUrl = null,
        format = null,
        sizeBytes = null,
        md5 = null,
        streamUrl = null,
        etag = null,
        bytesDownloaded = 0L,
        status = TaskStatus.Failed,
        mediaStoreId = null,
        fileName = null,
        failureKind = kind,
        failureMessage = message,
        createdAt = 1L,
        updatedAt = 1L,
        completedAt = null,
    )

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

        override suspend fun deleteRecord(id: Long) = error("unused")

        override suspend fun deleteRecords(ids: List<Long>) = error("unused")

        override suspend fun clearCompleted() = error("unused")

        override suspend fun pauseAll() = error("unused")

        override suspend fun resumeAll() = error("unused")

        override suspend fun pendingIds(): List<Long> = emptyList()

        override suspend fun cancelAll() = error("unused")

        override suspend fun retryAllFailed() = error("unused")

        override suspend fun allocateFileName(id: Long, base: BaseFileName): String =
            base.value

        override suspend fun releaseFileName(id: Long) = Unit
    }

    private class FakeAppSettings : AppSettings {
        override val apiCredentials: Flow<ApiCredentials?> =
            MutableStateFlow(null).map { key -> key?.let { ApiCredentials(TEST_API_BASE_URL, it) } }
        override val defaultQuality: Flow<MusicQuality> = MutableStateFlow(MusicQuality.Exhigh)
        override val maxConcurrentDownloads: Flow<Int> = MutableStateFlow(3)
        override val themeMode: Flow<ThemeMode> = MutableStateFlow(ThemeMode.System)
        override val notificationAsked: Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setApiCredentials(value: ApiCredentials) = Unit

        override suspend fun setDefaultQuality(value: MusicQuality) = Unit

        override suspend fun setMaxConcurrentDownloads(value: Int) = Unit

        override suspend fun setThemeMode(value: ThemeMode) = Unit

        override suspend fun markNotificationAsked() = Unit
    }

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

        override suspend fun attach(scope: CoroutineScope) = Unit

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

    private class FakeTaskFileStore : TaskFileStore {
        override suspend fun createPending(displayName: String, mimeType: String): CreatedFile =
            CreatedFile(mediaStoreId = 1L, displayName = displayName)

        override suspend fun openAppend(file: CreatedFile): OutputStream = ByteArrayOutputStream()

        override suspend fun openTruncate(file: CreatedFile): OutputStream = ByteArrayOutputStream()

        override suspend fun complete(file: CreatedFile) = Unit

        override suspend fun delete(file: CreatedFile) = Unit

        override suspend fun sizeOf(file: CreatedFile): Long = 0L

        override suspend fun md5Of(file: CreatedFile): String = ""

        override suspend fun deleteById(mediaStoreId: Long): Boolean = true
    }

    private class FakeQuotaStore : QuotaStore {
        override val quota: Flow<Quota?> = MutableStateFlow(null)

        override suspend fun record(quota: Quota) = Unit
    }

    private class FakeClock : AppClock {
        override fun nowMillis(): Long = 1L

        override fun elapsedRealtime(): Long = 0L
    }
}
