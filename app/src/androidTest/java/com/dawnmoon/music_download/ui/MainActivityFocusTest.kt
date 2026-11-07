package com.dawnmoon.music_download.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `focusKey` extra 的入口行为：携带 true 时直接进入设置页并聚焦 Key 输入框。
 *
 * 未携带 extra 时停留在工作台属于启动页既有行为，由 `LauncherTest` 覆盖。
 */
@RunWith(AndroidJUnit4::class)
class MainActivityFocusTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun focusKeyExtraOpensSettingsAndFocusesTheKeyInput() {
        launch(focusKey = true).use {
            composeRule.onNodeWithText("设置").assertIsDisplayed()
            composeRule.onNode(hasSetTextAction()).assertIsFocused()
        }
    }

    @Test
    fun focusKeyDeliveredToARunningActivityOpensSettingsAndFocusesTheKeyInput() {
        launch(focusKey = false).use { scenario ->
            // MainActivity 为 singleTop：已在前台时再次启动同一 Activity，系统把 intent
            // 投递给现有实例的 onNewIntent，而不是新建实例。
            scenario.onActivity { activity ->
                activity.startActivity(
                    Intent(activity, MainActivity::class.java)
                        .putExtra("focusKey", true),
                )
            }

            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("设置").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("设置").assertIsDisplayed()
            composeRule.onNode(hasSetTextAction()).assertIsFocused()
        }
    }

    @Test
    fun consumedFocusKeyIsNotReappliedAfterRecreation() {
        launch(focusKey = true).use { scenario ->
            composeRule.onNodeWithText("设置").assertIsDisplayed()

            // 返回工作台后重建 Activity：已被消费的 extra 不应再次跳到设置页。
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
            }
            composeRule.onNodeWithText("音乐下载").assertIsDisplayed()

            scenario.recreate()

            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithText("音乐下载").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("设置").assertDoesNotExist()
        }
    }

    private fun launch(focusKey: Boolean): ActivityScenario<MainActivity> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("focusKey", focusKey)
        return ActivityScenario.launch(intent)
    }
}
