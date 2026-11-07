package com.dawnmoon.music_download.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.ThemeMode
import com.dawnmoon.music_download.ui.theme.MusicDownloadTheme
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchTab
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchUiState
import com.dawnmoon.music_download.ui.workbench.WorkbenchCallbacks
import com.dawnmoon.music_download.ui.workbench.WorkbenchScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 工作台溢出菜单的批量操作：菜单提供全部批量动作，"全部取消"必须经过确认。
 */
@RunWith(AndroidJUnit4::class)
class BatchActionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun overflowMenuOffersEveryBatchAction() {
        setWorkbench()

        composeRule.onNodeWithContentDescription("更多操作").performClick()

        listOf("全部暂停", "全部继续", "全部重试失败", "全部取消").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun confirmingCancelAllReportsTheBatchCancellationOnce() {
        var cancelAllCalls = 0
        setWorkbench(
            callbacks = WorkbenchCallbacks(onCancelAll = { cancelAllCalls += 1 }),
        )

        composeRule.onNodeWithContentDescription("更多操作").performClick()
        composeRule.onNodeWithText("全部取消").performClick()

        // 菜单选择只打开确认框，此时不能已经触发取消。
        composeRule.onNodeWithText("确认").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, cancelAllCalls) }

        composeRule.onNodeWithText("确认").performClick()

        composeRule.runOnIdle { assertEquals(1, cancelAllCalls) }
    }

    @Test
    fun dismissingCancelAllLeavesTasksUntouched() {
        var cancelAllCalls = 0
        setWorkbench(
            callbacks = WorkbenchCallbacks(onCancelAll = { cancelAllCalls += 1 }),
        )

        composeRule.onNodeWithContentDescription("更多操作").performClick()
        composeRule.onNodeWithText("全部取消").performClick()
        composeRule.onNodeWithText("取消").performClick()

        composeRule.runOnIdle { assertEquals(0, cancelAllCalls) }
        composeRule.onNodeWithText("确认").assertDoesNotExist()
    }

    private fun setWorkbench(callbacks: WorkbenchCallbacks = WorkbenchCallbacks()) {
        composeRule.setContent {
            MusicDownloadTheme(themeMode = ThemeMode.Light) {
                WorkbenchScreen(
                    state = WorkbenchUiState(
                        input = "",
                        selectedQuality = MusicQuality.Exhigh,
                        quota = null,
                        inProgress = emptyList(),
                        completed = emptyList(),
                        selectedTab = WorkbenchTab.InProgress,
                        historyQuery = "",
                        isEnqueuing = false,
                        engine = null,
                    ),
                    callbacks = callbacks,
                )
            }
        }
    }
}
