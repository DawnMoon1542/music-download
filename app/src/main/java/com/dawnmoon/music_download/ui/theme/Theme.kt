package com.dawnmoon.music_download.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.dawnmoon.music_download.domain.model.ThemeMode

/**
 * 应用主题入口：按 [themeMode] 解析实际深浅色，System 跟随系统设置。
 *
 * 动态取色在 Stage 3 接入，这里使用 Material3 默认配色。
 */
@Composable
fun MusicDownloadTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
