package com.dawnmoon.music_download.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 验证工作台与设置页之间的单 Activity 路由。
 *
 * 设置页入口使用可访问性描述“设置”，页面切换以各页标题为可观察结果。
 */
@RunWith(AndroidJUnit4::class)
class AppRootTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun clickingSettingsIconShowsSettingsScreen() {
        composeRule.onNodeWithContentDescription("设置").performClick()

        composeRule.onNodeWithText("设置").assertIsDisplayed()
    }

    @Test
    fun backFromSettingsReturnsToWorkbench() {
        composeRule.onNodeWithContentDescription("设置").performClick()
        composeRule.onNodeWithText("设置").assertIsDisplayed()

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithText("音乐下载").assertIsDisplayed()
    }
}
