package com.dawnmoon.music_download.ui

import kotlinx.coroutines.flow.map
import com.dawnmoon.music_download.domain.model.ApiCredentials
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.dawnmoon.music_download.domain.model.ThemeMode
import com.dawnmoon.music_download.download.CreatedFile
import com.dawnmoon.music_download.download.DownloadController
import com.dawnmoon.music_download.download.EngineSnapshot
import com.dawnmoon.music_download.download.TaskFileStore
import com.dawnmoon.music_download.ui.history.HistoryActions
import com.dawnmoon.music_download.ui.settings.SettingsCallbacks
import com.dawnmoon.music_download.ui.settings.SettingsScreen
import com.dawnmoon.music_download.ui.theme.MusicDownloadTheme
import com.dawnmoon.music_download.ui.viewmodel.KeyStatus
import com.dawnmoon.music_download.ui.viewmodel.SettingsViewModel
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_API_BASE_URL = "https://api.test"

/**
 * 设置页的交互行为。
 *
 * 页面由真实的 [SettingsViewModel] 驱动，注入的 [KeyValidator] 是 Fake，因此保存凭据
 * 不会访问网络，但“验证中”状态与保存按钮禁用可以真实观察到。节点定位使用测试标签，
 * 不断言具体文案，避免把界面拷贝当成行为契约。
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val settings = FakeAppSettings()
    private val validator = FakeKeyValidator()
    private val quotaStore = FakeQuotaStore()
    private val repo = FakeTaskRepository()
    private val fileStore = FakeTaskFileStore()

    @Test
    fun togglingKeyVisibilitySwitchesThePasswordMask() {
        val viewModel = createViewModel()
        setScreen(viewModel)

        composeRule.onNodeWithTag("api_key_input").performTextReplacement("chksz_draft_key")
        composeRule.onNodeWithTag("api_key_input")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))

        composeRule.onNodeWithTag("toggle_key_visibility").performClick()

        composeRule.onNodeWithTag("api_key_input")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
    }

    @Test
    fun theSiteLinkAppearsOnlyAfterAValidBaseUrlIsEntered() {
        val viewModel = createViewModel()
        setScreen(viewModel)

        composeRule.onNodeWithTag("get_key_link").assertDoesNotExist()

        composeRule.onNodeWithTag("api_url_input").performTextReplacement("https://api.test")

        composeRule.onNodeWithTag("get_key_link").assertIsDisplayed()
    }

    @Test
    fun savingSubmitsTheBaseUrlTogetherWithTheKey() {
        val viewModel = createViewModel()
        setScreen(viewModel)

        composeRule.onNodeWithTag("api_url_input").performTextReplacement("https://api.test/")
        composeRule.onNodeWithTag("api_key_input").performTextReplacement("chksz_saved_key")
        composeRule.onNodeWithTag("save_key").performClick()

        composeRule.waitUntil(5_000) { viewModel.state.value.keyStatus == KeyStatus.Valid }
        composeRule.runOnIdle {
            assertEquals(ApiCredentials("https://api.test", "chksz_saved_key"), validator.lastCredentials)
            assertEquals("chksz_saved_key", settings.apiKeyFlow.value)
        }
    }

    @Test
    fun saveButtonIsDisabledWhileTheKeyIsBeingValidated() {
        validator.gateEnabled = true
        val viewModel = createViewModel()
        setScreen(viewModel)

        composeRule.onNodeWithTag("api_url_input").performTextReplacement("https://api.test")
        composeRule.onNodeWithTag("api_key_input").performTextReplacement("chksz_pending_key")
        composeRule.onNodeWithTag("save_key").performClick()

        composeRule.waitUntil(5_000) {
            viewModel.state.value.keyStatus == KeyStatus.Validating
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("save_key").assertIsNotEnabled()

        // 结束挂起的校验，避免 ViewModel 协程在测试结束后仍然等待。
        validator.gate.complete(Unit)
        composeRule.waitUntil(5_000) {
            viewModel.state.value.keyStatus != KeyStatus.Validating
        }
    }

    @Test
    fun selectingADefaultQualityPersistsIt() {
        val viewModel = createViewModel()
        setScreen(viewModel)

        composeRule.onNodeWithTag("default_quality").performClick()
        composeRule.onNode(
            hasClickAction() and hasText(MusicQuality.Lossless.label),
        ).performClick()

        composeRule.runOnIdle {
            assertEquals(MusicQuality.Lossless, viewModel.state.value.defaultQuality)
            assertEquals(MusicQuality.Lossless, settings.defaultQualityFlow.value)
        }
    }

    @Test
    fun movingTheConcurrencySliderPersistsTheSelectedValue() {
        val viewModel = createViewModel()
        setScreen(viewModel)

        composeRule.onNodeWithTag("concurrency_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }

        composeRule.runOnIdle {
            assertEquals(2, viewModel.state.value.concurrency)
            assertEquals(2, settings.maxConcurrentDownloadsFlow.value)
        }
    }

    @Test
    fun choosingDarkThemePersistsTheSelection() {
        val viewModel = createViewModel()
        setScreen(viewModel)

        composeRule.onNodeWithTag("theme_dark").performClick()

        composeRule.runOnIdle {
            assertEquals(ThemeMode.Dark, viewModel.state.value.themeMode)
            assertEquals(ThemeMode.Dark, settings.themeModeFlow.value)
        }
    }

    private fun createViewModel(): SettingsViewModel =
        SettingsViewModel(
            settings = settings,
            keyValidator = validator,
            quotaStore = quotaStore,
            tasks = repo,
            historyActions = HistoryActions(
                repo,
                fileStore,
                FakeDownloadController(),
                ContextWrapper(null),
            ),
        )

    private fun setScreen(viewModel: SettingsViewModel) {
        composeRule.setContent {
            val state by viewModel.state.collectAsState()
            MusicDownloadTheme(themeMode = state.themeMode) {
                SettingsScreen(
                    state = state,
                    callbacks = SettingsCallbacks(
                        onUrlChange = viewModel::onUrlChange,
                        onKeyChange = viewModel::onKeyChange,
                        onSaveKey = viewModel::onSaveKey,
                        onDefaultQualityChange = viewModel::onDefaultQualityChange,
                        onConcurrencyChange = viewModel::onConcurrencyChange,
                        onThemeModeChange = viewModel::onThemeModeChange,
                        onClearHistory = viewModel::onClearHistory,
                    ),
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private class FakeAppSettings : AppSettings {

        val apiKeyFlow = MutableStateFlow<String?>(null)
        val defaultQualityFlow = MutableStateFlow(MusicQuality.Exhigh)
        val maxConcurrentDownloadsFlow = MutableStateFlow(3)
        val themeModeFlow = MutableStateFlow(ThemeMode.System)
        val notificationAskedFlow = MutableStateFlow(false)

        override val apiCredentials: Flow<ApiCredentials?> =
            apiKeyFlow.map { key -> key?.let { ApiCredentials(TEST_API_BASE_URL, it) } }
        override val defaultQuality: Flow<MusicQuality> = defaultQualityFlow
        override val maxConcurrentDownloads: Flow<Int> = maxConcurrentDownloadsFlow
        override val themeMode: Flow<ThemeMode> = themeModeFlow
        override val notificationAsked: Flow<Boolean> = notificationAskedFlow

        override suspend fun setApiCredentials(value: ApiCredentials) {
            apiKeyFlow.value = value.apiKey
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

    private class FakeKeyValidator : KeyValidator {

        val gate = CompletableDeferred<Unit>()

        var gateEnabled: Boolean = false

        var lastCredentials: ApiCredentials? = null

        override suspend fun validate(credentials: ApiCredentials): KeyValidator.Result {
            lastCredentials = credentials
            if (gateEnabled) gate.await()
            return KeyValidator.Result.Valid(quota = null)
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

        override suspend fun createPending(displayName: String, mimeType: String): CreatedFile =
            error("unused")

        override suspend fun openAppend(file: CreatedFile): OutputStream = error("unused")

        override suspend fun openTruncate(file: CreatedFile): OutputStream = error("unused")

        override suspend fun complete(file: CreatedFile) = error("unused")

        override suspend fun delete(file: CreatedFile) = error("unused")

        override suspend fun sizeOf(file: CreatedFile): Long = error("unused")

        override suspend fun md5Of(file: CreatedFile): String = error("unused")

        override suspend fun deleteById(mediaStoreId: Long): Boolean = true
    }

    /** [HistoryActions] 的构造依赖下载引擎；设置页测试不执行清空历史，只需空实现。 */
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
