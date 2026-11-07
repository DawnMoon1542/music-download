package com.dawnmoon.music_download.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.Quota
import com.dawnmoon.music_download.domain.model.ThemeMode
import com.dawnmoon.music_download.ui.theme.MusicDownloadTheme
import com.dawnmoon.music_download.ui.viewmodel.TaskUiModel
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchUiState
import com.dawnmoon.music_download.ui.workbench.WorkbenchCallbacks
import com.dawnmoon.music_download.ui.workbench.WorkbenchScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * 工作台的纯展示行为：直接注入 [WorkbenchUiState] 与 [WorkbenchCallbacks]，
 * 不依赖 ViewModel，也不连接仓库、引擎或网络。
 *
 * 期望的生产接口为 `WorkbenchScreen(state, callbacks, modifier)`；
 * [WorkbenchCallbacks] 的每个回调都有空实现的默认值，便于只覆盖被测分支。
 */
@RunWith(AndroidJUnit4::class)
class WorkbenchScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyTaskListShowsEmptyState() {
        setWorkbench(uiState())

        composeRule.onNodeWithTag("task_search").assertDoesNotExist()
        composeRule.onNodeWithText("暂无下载任务", substring = true).assertIsDisplayed()
    }

    @Test
    fun buttonEnablesForMultiLineInputAndEnqueuesOnce() {
        var state by mutableStateOf(uiState())
        var enqueueCalls = 0
        composeRule.setContent {
            MusicDownloadTheme(themeMode = ThemeMode.Light) {
                WorkbenchScreen(
                    state = state,
                    callbacks = WorkbenchCallbacks(
                        onInputChange = { state = state.copy(input = it) },
                        onEnqueue = { enqueueCalls += 1 },
                    ),
                )
            }
        }

        val enqueueButton = composeRule.onNodeWithText("解析并下载")
        enqueueButton.assertIsNotEnabled()

        composeRule.onNodeWithTag("link_input").performTextReplacement(
            "https://music.163.com/song?id=1\nhttps://music.163.com/song?id=2",
        )

        enqueueButton.assertIsEnabled()
        enqueueButton.performClick()

        composeRule.runOnIdle { assertEquals(1, enqueueCalls) }
    }

    @Test
    fun qualityMenuOffersAllQualitiesAndReportsLosslessSelection() {
        var selected: MusicQuality? = null
        setWorkbench(
            state = uiState(selectedQuality = MusicQuality.Exhigh),
            callbacks = WorkbenchCallbacks(onQualityChange = { selected = it }),
        )

        composeRule.onNode(
            hasClickAction() and hasText("极高", substring = true),
        ).performClick()

        // 下拉打开后全部七个音质都可看到；当前音质可能同时出现在选择器与菜单中。
        listOf("标准", "极高", "无损", "Hi-Res", "沉浸环绕", "沉浸天空", "超清母带").forEach { label ->
            composeRule.onAllNodesWithText(label, substring = true).onFirst().assertIsDisplayed()
        }

        composeRule.onNodeWithText("无损").performClick()

        composeRule.runOnIdle { assertEquals(MusicQuality.Lossless, selected) }
    }

    @Test
    fun mergedListShowsUnfinishedAndCompletedTasksWithStatusCounts() {
        setWorkbench(
            uiState(
                inProgress = listOf(
                    task(
                        id = 1,
                        title = "失败的歌",
                        statusLabel = "网络错误",
                        failureKind = "Network",
                        canRetry = true,
                    ),
                    task(id = 2, title = "进行中的歌", statusLabel = "下载中", canPause = true),
                ),
                completed = listOf(
                    task(id = 3, title = "历史里的歌", statusLabel = "已完成", completedAt = 1L),
                ),
            ),
        )

        composeRule.onNodeWithText("异常 1").assertIsDisplayed()
        composeRule.onNodeWithText("进行中 1").assertIsDisplayed()
        composeRule.onNodeWithText("已完成 1").assertIsDisplayed()
        composeRule.onNodeWithText("已取消", substring = true).assertDoesNotExist()

        val list = composeRule.onNodeWithTag("task_list")
        listOf("失败的歌", "进行中的歌", "历史里的歌").forEach { title ->
            list.performScrollToNode(hasText(title))
            composeRule.onNodeWithText(title).assertIsDisplayed()
        }
    }

    @Test
    fun downloadingCardShowsProgressDetailsAndPauses() {
        val pausedIds = mutableListOf<Long>()
        setWorkbench(
            state = uiState(
                inProgress = listOf(
                    task(
                        id = 42L,
                        title = "夏霞",
                        artist = "あたらよ",
                        statusLabel = "下载中",
                        progress = 0.42f,
                        bytesDownloaded = 9_600_590L,
                        totalBytes = 22_858_547L,
                        speedBytesPerSecond = 2_202_010L,
                        canPause = true,
                        canCancel = true,
                    ),
                ),
            ),
            callbacks = WorkbenchCallbacks(onPause = { pausedIds += it }),
        )

        composeRule.onNodeWithText("42%", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("9.2", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("21.8", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("2.1 MB/s", substring = true).assertIsDisplayed()

        // 百分比文本之外，进度条本身也要反映进度，避免省略进度条只渲染文本。
        composeRule.onNodeWithTag("download_progress", useUnmergedTree = true)
            .assert(progressApproximately(0.42f))

        composeRule.onNode(
            hasClickAction() and (hasText("暂停") or hasContentDescription("暂停")),
        ).performClick()

        composeRule.runOnIdle { assertEquals(listOf(42L), pausedIds) }
    }

    @Test
    fun failedCardShowsLocalizedMessageAndExpandsToRawMessage() {
        val retriedIds = mutableListOf<Long>()
        setWorkbench(
            state = uiState(
                inProgress = listOf(
                    task(
                        id = 7L,
                        title = "失败的歌",
                        statusLabel = "网络错误，请检查网络后重试",
                        failureKind = "Network",
                        failureMessage = "连接超时",
                        canRetry = true,
                        canCancel = true,
                    ),
                ),
            ),
            callbacks = WorkbenchCallbacks(onRetry = { retriedIds += it }),
        )

        composeRule.onNodeWithText("网络错误，请检查网络后重试").assertIsDisplayed()
        composeRule.onNodeWithText("重试").assertIsDisplayed()
        composeRule.onNodeWithText("连接超时", substring = true).assertDoesNotExist()

        composeRule.onNodeWithTag("task_failure_toggle").performClick()

        composeRule.onNodeWithText("连接超时", substring = true).assertIsDisplayed()

        composeRule.onNode(
            hasClickAction() and (hasText("重试") or hasContentDescription("重试")),
        ).performClick()

        composeRule.runOnIdle { assertEquals(listOf(7L), retriedIds) }
    }

    @Test
    fun cancelledCardOffersDeleteRecord() {
        setWorkbench(
            state = uiState(
                inProgress = listOf(
                    // 状态文案刻意不使用 string resource 的“已取消”：按钮必须由 canDelete
                    // 驱动，而不是从本地化文案反推状态，否则此处不会显示删除记录。
                    task(id = 9L, title = "取消的歌", statusLabel = "任意状态", canDelete = true),
                ),
            ),
        )

        composeRule.onNodeWithText("删除记录").assertIsDisplayed()
    }

    @Test
    fun cardWithoutDeleteCapabilityHidesDeleteRecord() {
        setWorkbench(
            state = uiState(
                inProgress = listOf(
                    // 文案恰好是资源中的“已取消”，但能力为 false：界面不能凭文案推断状态。
                    // 同时给出另一个动作，确保动作区不会被整体跳过，从而真正检验 canDelete。
                    task(
                        id = 9L,
                        title = "取消的歌",
                        statusLabel = "已取消",
                        canCancel = true,
                        canDelete = false,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("删除记录").assertDoesNotExist()
    }

    @Test
    fun lowQuotaShowsWarningWithTestTag() {
        setWorkbench(
            state = uiState(
                quota = Quota(freeRemaining = 5, paidRemaining = 50, limitPerMinute = 30),
            ),
        )

        composeRule.onNodeWithTag("quota_warning").assertIsDisplayed()
        composeRule.onNodeWithText("额度不足", substring = true).assertIsDisplayed()
    }

    @Test
    fun quotaWarningHiddenAtTenOrWithoutQuotaData() {
        var state by mutableStateOf(
            uiState(
                quota = Quota(freeRemaining = 10, paidRemaining = 50, limitPerMinute = 30),
            ),
        )
        composeRule.setContent {
            MusicDownloadTheme(themeMode = ThemeMode.Light) {
                WorkbenchScreen(state = state, callbacks = WorkbenchCallbacks())
            }
        }

        // 边界：额度恰好为 10 时不满足“低于 10”。
        composeRule.onNodeWithTag("quota_warning").assertDoesNotExist()

        // 无额度数据时不显示额度警示。
        composeRule.runOnIdle { state = state.copy(quota = null) }
        composeRule.onNodeWithTag("quota_warning").assertDoesNotExist()
    }

    private fun progressApproximately(
        expected: Float,
        tolerance: Float = 0.01f,
    ): SemanticsMatcher = SemanticsMatcher(
        "progress within $tolerance of $expected",
    ) { node ->
        val rangeInfo = node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
            ?: return@SemanticsMatcher false
        abs(rangeInfo.current - expected) <= tolerance
    }

    private fun setWorkbench(
        state: WorkbenchUiState,
        callbacks: WorkbenchCallbacks = WorkbenchCallbacks(),
    ) {
        composeRule.setContent {
            MusicDownloadTheme(themeMode = ThemeMode.Light) {
                WorkbenchScreen(state = state, callbacks = callbacks)
            }
        }
    }

    private fun uiState(
        input: String = "",
        selectedQuality: MusicQuality = MusicQuality.Exhigh,
        quota: Quota? = null,
        inProgress: List<TaskUiModel> = emptyList(),
        completed: List<TaskUiModel> = emptyList(),
    ): WorkbenchUiState = workbenchState(
        tasks = inProgress + completed,
        input = input,
        selectedQuality = selectedQuality,
        quota = quota,
    )

    private fun task(
        id: Long,
        title: String,
        statusLabel: String,
        artist: String? = null,
        progress: Float? = null,
        bytesDownloaded: Long? = null,
        totalBytes: Long? = null,
        speedBytesPerSecond: Long? = null,
        failureKind: String? = null,
        failureMessage: String? = null,
        completedAt: Long? = null,
        canPause: Boolean = false,
        canCancel: Boolean = false,
        canRetry: Boolean = false,
        canDelete: Boolean = false,
    ): TaskUiModel = testTask(
        id = id,
        title = title,
        statusLabel = statusLabel,
        artist = artist,
        progress = progress,
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        speedBytesPerSecond = speedBytesPerSecond,
        failureKind = failureKind,
        failureMessage = failureMessage,
        completedAt = completedAt,
        canPause = canPause,
        canCancel = canCancel,
        canRetry = canRetry,
        canDelete = canDelete,
    )
}
