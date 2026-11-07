package com.dawnmoon.music_download.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.Quota
import com.dawnmoon.music_download.domain.model.ThemeMode
import com.dawnmoon.music_download.ui.settings.SettingsCallbacks
import com.dawnmoon.music_download.ui.settings.SettingsScreen
import com.dawnmoon.music_download.ui.theme.MusicDownloadTheme
import com.dawnmoon.music_download.ui.viewmodel.KeyStatus
import com.dawnmoon.music_download.ui.viewmodel.SettingsViewModel
import com.dawnmoon.music_download.ui.viewmodel.TaskUiModel
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchTab
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchUiState
import com.dawnmoon.music_download.ui.workbench.WorkbenchCallbacks
import com.dawnmoon.music_download.ui.workbench.WorkbenchScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 可点击控件的无障碍语义。
 *
 * 除失败卡片的「点击展开原文」区域外，工作台与设置页的关键按钮也必须提供描述且保持
 * 至少 48dp 的触控目标；这些断言按控件标签定位，避免依赖具体文案。
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun failedTaskExpansionAnnouncesItsAction() {
        setFailureCard()

        val expandRegion = composeRule.onNodeWithTag(FAILURE_TOGGLE_TAG)
            .assertIsDisplayed()
            .assert(hasClickAction())

        expandRegion.assert(hasNonEmptyClickLabel())
    }

    @Test
    fun failedTaskExpansionProvidesMinimumTapArea() {
        setFailureCard()

        // 可点击区域需要至少 48dp 高；只靠曲名一行文本时为 24dp。
        composeRule.onNodeWithTag(FAILURE_TOGGLE_TAG).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun workbenchKeyButtonsHaveDescriptionsAndTapTargets() {
        setWorkbench(
            uiState(
                inProgress = listOf(
                    task(
                        id = 1L,
                        title = "下载中的歌",
                        statusLabel = "下载中",
                        canPause = true,
                        canCancel = true,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithTag("settings_button")
            .assert(hasContentDescription("设置"))
            .assertTouchHeightIsEqualTo(48.dp)
        composeRule.onNodeWithTag("batch_actions_menu")
            .assert(hasContentDescription("更多操作"))
            .assertTouchHeightIsEqualTo(48.dp)
        composeRule.onNodeWithTag("task_action_pause")
            .assertIsDisplayed()
            .assertTouchHeightIsEqualTo(48.dp)
        composeRule.onNodeWithTag("enqueue_button").assertTouchHeightIsEqualTo(48.dp)
        composeRule.onNodeWithTag("tab_in_progress").assertTouchHeightIsEqualTo(48.dp)
    }

    @Test
    fun completedHistoryButtonsHaveDescriptionsAndTapTargets() {
        setWorkbench(
            uiState(
                selectedTab = WorkbenchTab.Completed,
                completed = listOf(
                    task(
                        id = 2L,
                        title = "历史里的歌",
                        statusLabel = "已完成",
                        completedAt = 1L,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithTag("history_actions_menu")
            .assert(hasContentDescription("历史操作"))
            .assertTouchHeightIsEqualTo(48.dp)
        composeRule.onNodeWithTag("task_action_play").assertTouchHeightIsEqualTo(48.dp)
        composeRule.onNodeWithTag("task_action_share").assertTouchHeightIsEqualTo(48.dp)
        composeRule.onNodeWithTag("task_more_actions")
            .assert(hasContentDescription("更多操作"))
            .assertTouchHeightIsEqualTo(48.dp)
    }

    @Test
    fun settingsButtonsHaveDescriptionsAndTapTargets() {
        composeRule.setContent {
            MusicDownloadTheme(themeMode = ThemeMode.Light) {
                SettingsScreen(
                    state = SettingsViewModel.State(
                        apiKeyDraft = "",
                        savedKeyMasked = null,
                        keyStatus = KeyStatus.Idle,
                        defaultQuality = MusicQuality.Exhigh,
                        concurrency = 3,
                        themeMode = ThemeMode.System,
                        quota = Quota(freeRemaining = 10, paidRemaining = 0, limitPerMinute = 20),
                    ),
                    callbacks = SettingsCallbacks(),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("settings_back")
            .assert(hasContentDescription("返回"))
            .assertTouchHeightIsEqualTo(48.dp)
        composeRule.onNodeWithTag("toggle_key_visibility")
            .assert(hasContentDescription("显示 Key"))
            .assertTouchHeightIsEqualTo(48.dp)
        composeRule.onNodeWithTag("save_key").assertTouchHeightIsEqualTo(48.dp)
        composeRule.onNodeWithTag("get_key_link").assertTouchHeightIsEqualTo(48.dp)
    }

    private fun hasNonEmptyClickLabel(): SemanticsMatcher = SemanticsMatcher(
        "has a non-empty click action label",
    ) { node ->
        node.config.getOrNull(SemanticsActions.OnClick)?.label?.isNotBlank() == true
    }

    /**
     * 只渲染失败卡片本身；标题不提供艺术家行，确保触控目标大小来自控件自身而非文本高度。
     */
    private fun setFailureCard() {
        setWorkbench(
            uiState(
                inProgress = listOf(
                    task(
                        id = 1L,
                        title = FAILED_TITLE,
                        statusLabel = "网络错误，请检查网络后重试",
                        failureKind = "Network",
                        failureMessage = RAW_MESSAGE,
                        canCancel = true,
                        canRetry = true,
                    ),
                ),
            ),
        )
    }

    private fun setWorkbench(state: WorkbenchUiState) {
        composeRule.setContent {
            MusicDownloadTheme(themeMode = ThemeMode.Light) {
                WorkbenchScreen(state = state, callbacks = WorkbenchCallbacks())
            }
        }
    }

    private fun uiState(
        inProgress: List<TaskUiModel> = emptyList(),
        completed: List<TaskUiModel> = emptyList(),
        selectedTab: WorkbenchTab = WorkbenchTab.InProgress,
    ): WorkbenchUiState = WorkbenchUiState(
        input = "",
        selectedQuality = MusicQuality.Exhigh,
        quota = null,
        inProgress = inProgress,
        completed = completed,
        selectedTab = selectedTab,
        historyQuery = "",
        isEnqueuing = false,
        engine = null,
    )

    private fun task(
        id: Long,
        title: String,
        statusLabel: String,
        failureKind: String? = null,
        failureMessage: String? = null,
        completedAt: Long? = null,
        canPause: Boolean = false,
        canCancel: Boolean = false,
        canRetry: Boolean = false,
    ): TaskUiModel = TaskUiModel(
        id = id,
        songId = id + 1L,
        sourceUrl = "https://music.163.com/song?id=${id + 1L}",
        mediaStoreId = null,
        title = title,
        artist = null,
        artworkUrl = null,
        qualityLabel = MusicQuality.Exhigh.label,
        statusLabel = statusLabel,
        progress = null,
        bytesDownloaded = null,
        totalBytes = null,
        speedBytesPerSecond = null,
        failureKind = failureKind,
        failureMessage = failureMessage,
        fileName = null,
        completedAt = completedAt,
        canPause = canPause,
        canResume = false,
        canCancel = canCancel,
        canRetry = canRetry,
        canDelete = false,
    )

    private companion object {
        const val FAILURE_TOGGLE_TAG = "task_failure_toggle"
        const val FAILED_TITLE = "夏霞"
        const val RAW_MESSAGE = "连接超时"
    }
}
