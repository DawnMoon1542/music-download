package com.dawnmoon.music_download.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dawnmoon.music_download.MusicDownloadApp
import com.dawnmoon.music_download.domain.model.ThemeMode
import com.dawnmoon.music_download.ui.viewmodel.AppViewModelFactory

/**
 * 应用唯一 Activity：创建依赖入口并显示路由根组合。
 *
 * `focusKey=true` extra 在 `onCreate` 与 `onNewIntent` 都会读取：Activity 为 `singleTop`，
 * 已在前台时系统投递新 Intent 给 `onNewIntent`，不能只处理冷启动路径。extra 一旦被
 * 消费即从 Intent 移除，避免屏幕旋转或进程重建后再次触发跳转。
 */
class MainActivity : ComponentActivity() {

    private var focusKeyRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 仅冷启动读取 extra；重建时路由已由 rememberSaveable 恢复，不应再次跳转。
        focusKeyRequested =
            savedInstanceState == null && intent.getBooleanExtra(EXTRA_FOCUS_KEY, false)
        val dependencies = (application as MusicDownloadApp).dependencies
        val viewModelFactory = AppViewModelFactory(dependencies = dependencies)
        setContent {
            val themeMode by dependencies.settings.themeMode.collectAsStateWithLifecycle(
                initialValue = ThemeMode.System,
            )
            val darkTheme = when (themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // 系统栏图标由用户选择的主题决定，而不是仅由系统 uiMode 决定。
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        lightScrim = Color.TRANSPARENT,
                        darkScrim = Color.TRANSPARENT,
                        detectDarkMode = { darkTheme },
                    ),
                    navigationBarStyle = SystemBarStyle.auto(
                        lightScrim = Color.TRANSPARENT,
                        darkScrim = Color.TRANSPARENT,
                        detectDarkMode = { darkTheme },
                    ),
                )
            }
            AppRoot(
                viewModelFactory = viewModelFactory,
                themeMode = themeMode,
                focusKey = focusKeyRequested,
                // 消费后复位并清除 extra，下一次投递同样的 extra 仍能触发聚焦。
                onFocusKeyConsumed = {
                    focusKeyRequested = false
                    intent.removeExtra(EXTRA_FOCUS_KEY)
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        focusKeyRequested = intent.getBooleanExtra(EXTRA_FOCUS_KEY, false)
    }

    companion object {
        const val EXTRA_FOCUS_KEY = "focusKey"
    }
}
