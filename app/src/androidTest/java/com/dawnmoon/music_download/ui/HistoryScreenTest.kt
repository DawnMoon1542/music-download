package com.dawnmoon.music_download.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.ThemeMode
import com.dawnmoon.music_download.ui.history.HistoryActions
import com.dawnmoon.music_download.ui.theme.MusicDownloadTheme
import com.dawnmoon.music_download.ui.viewmodel.TaskUiModel
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchTab
import com.dawnmoon.music_download.ui.viewmodel.WorkbenchUiState
import com.dawnmoon.music_download.ui.workbench.WorkbenchCallbacks
import com.dawnmoon.music_download.ui.workbench.WorkbenchScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 历史记录的界面行为与 Intent 契约。
 *
 * 界面部分直接注入 [WorkbenchUiState] 与 [WorkbenchCallbacks]，不依赖 ViewModel；
 * Intent 部分在设备上构造真实 `android.content.Intent`（JVM 单元测试无法构造），
 * 断言打开/分享所需的 action、URI 与授权 flag。
 */
@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    // region Intent 契约

    @Test
    fun openIntentViewsTheCompletedFileWithReadGrant() {
        val intent = requireNotNull(HistoryActions.openIntent(42L))

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content://media/external/audio/media/42", intent.data.toString())
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun shareIntentSendsTheAudioFileWithStreamAndReadGrant() {
        val intent = requireNotNull(HistoryActions.shareIntent(42L, "夏霞"))
        val uri = Uri.parse("content://media/external/audio/media/42")

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("audio/*", intent.type)
        assertEquals(
            uri,
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
        )
        val clipData = requireNotNull(intent.clipData)
        assertEquals(uri, clipData.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    // endregion

    // region 搜索

    @Test
    fun historySearchReportsTheTypedQuery() {
        var state by mutableStateOf(
            historyState(
                completed = listOf(completedTask(1L)),
                historyQuery = "",
            ),
        )
        composeRule.setContent {
            MusicDownloadTheme(themeMode = ThemeMode.Light) {
                WorkbenchScreen(
                    state = state,
                    callbacks = WorkbenchCallbacks(
                        onHistoryQueryChange = { state = state.copy(historyQuery = it) },
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("history_search").performTextInput("夏霞")

        composeRule.runOnIdle { assertEquals("夏霞", state.historyQuery) }
    }

    @Test
    fun emptySearchResultShowsNoMatchHint() {
        setHistory(
            state = historyState(
                completed = emptyList(),
                historyQuery = "不存在",
            ),
        )

        composeRule.onNodeWithText("没有匹配的历史记录").assertIsDisplayed()
        composeRule.onNodeWithText("暂无历史任务").assertDoesNotExist()
    }

    // endregion

    // region 清空历史

    @Test
    fun clearHistoryDialogClearsRecordsOnlyByDefault() {
        var deleteFiles: Boolean? = null
        setHistory(
            state = historyState(completed = listOf(completedTask(7L))),
            callbacks = WorkbenchCallbacks(onClearHistory = { deleteFiles = it }),
        )

        composeRule.onNodeWithContentDescription("历史操作").performClick()
        composeRule.onNodeWithText("清空历史").performClick()

        composeRule.onNodeWithText("仅清空记录").assertIsDisplayed()
        composeRule.onNodeWithText("同时删除文件").assertIsDisplayed()

        composeRule.onNodeWithText("确认").performClick()

        composeRule.runOnIdle { assertEquals(false, deleteFiles) }
    }

    @Test
    fun clearHistoryDialogCanAlsoDeleteFiles() {
        var deleteFiles: Boolean? = null
        setHistory(
            state = historyState(completed = listOf(completedTask(7L))),
            callbacks = WorkbenchCallbacks(onClearHistory = { deleteFiles = it }),
        )

        composeRule.onNodeWithContentDescription("历史操作").performClick()
        composeRule.onNodeWithText("清空历史").performClick()
        composeRule.onNodeWithText("同时删除文件").performClick()
        composeRule.onNodeWithText("确认").performClick()

        composeRule.runOnIdle { assertEquals(true, deleteFiles) }
    }

    // endregion

    // region 已完成卡片动作

    @Test
    fun completedCardPlaysTheFile() {
        var openedId: Long? = null
        setHistory(
            state = historyState(completed = listOf(completedTask(42L))),
            callbacks = WorkbenchCallbacks(onOpen = { openedId = it }),
        )

        composeRule.onNodeWithText("播放").performClick()

        composeRule.runOnIdle { assertEquals(42L, openedId) }
    }

    @Test
    fun completedCardSharesTheFile() {
        var sharedId: Long? = null
        setHistory(
            state = historyState(completed = listOf(completedTask(42L))),
            callbacks = WorkbenchCallbacks(onShare = { sharedId = it }),
        )

        composeRule.onNodeWithText("分享").performClick()

        composeRule.runOnIdle { assertEquals(42L, sharedId) }
    }

    @Test
    fun completedCardRedownloadsAtTheChosenQuality() {
        var redownloadedId: Long? = null
        var chosenQuality: MusicQuality? = null
        setHistory(
            state = historyState(completed = listOf(completedTask(42L))),
            callbacks = WorkbenchCallbacks(
                onRedownload = { id, quality ->
                    redownloadedId = id
                    chosenQuality = quality
                },
            ),
        )

        composeRule.onAllNodesWithContentDescription("更多操作").onLast().performClick()
        composeRule.onNodeWithText("按音质重新下载").performClick()
        composeRule.onNodeWithText("无损").performClick()
        composeRule.onNodeWithText("确认").performClick()

        composeRule.runOnIdle {
            assertEquals(42L, redownloadedId)
            assertEquals(MusicQuality.Lossless, chosenQuality)
        }
    }

    @Test
    fun completedCardDeletesRecordOrRecordAndFile() {
        val deletions = mutableListOf<Pair<Long, Boolean>>()
        setHistory(
            state = historyState(completed = listOf(completedTask(42L))),
            callbacks = WorkbenchCallbacks(
                onDelete = { id, deleteFile -> deletions += id to deleteFile },
            ),
        )

        composeRule.onAllNodesWithContentDescription("更多操作").onLast().performClick()
        composeRule.onNodeWithText("删除记录").performClick()
        composeRule.runOnIdle { assertEquals(listOf(42L to false), deletions) }

        composeRule.onAllNodesWithContentDescription("更多操作").onLast().performClick()
        composeRule.onNodeWithText("删除记录与文件").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(42L to false, 42L to true), deletions)
        }
    }

    // endregion

    private fun setHistory(
        state: WorkbenchUiState,
        callbacks: WorkbenchCallbacks = WorkbenchCallbacks(),
    ) {
        composeRule.setContent {
            MusicDownloadTheme(themeMode = ThemeMode.Light) {
                WorkbenchScreen(state = state, callbacks = callbacks)
            }
        }
    }

    private fun historyState(
        completed: List<TaskUiModel>,
        historyQuery: String = "",
    ): WorkbenchUiState = WorkbenchUiState(
        input = "",
        selectedQuality = MusicQuality.Exhigh,
        quota = null,
        inProgress = emptyList(),
        completed = completed,
        selectedTab = WorkbenchTab.Completed,
        historyQuery = historyQuery,
        isEnqueuing = false,
        engine = null,
    )

    private fun completedTask(id: Long): TaskUiModel = TaskUiModel(
        id = id,
        songId = id + 1L,
        sourceUrl = "https://music.163.com/song?id=${id + 1L}",
        mediaStoreId = 42L,
        title = "夏霞",
        artist = "あたらよ",
        artworkUrl = null,
        qualityLabel = MusicQuality.Exhigh.label,
        statusLabel = "已完成",
        progress = null,
        bytesDownloaded = 1_000L,
        totalBytes = 1_000L,
        speedBytesPerSecond = null,
        failureKind = null,
        failureMessage = null,
        fileName = "夏霞.mp3",
        completedAt = 1_000L,
        canPause = false,
        canResume = false,
        canCancel = false,
        canRetry = false,
        canDelete = false,
    )
}
