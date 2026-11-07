package com.dawnmoon.music_download.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.dawnmoon.music_download.domain.model.ThemeMode

/** Android 12 以下使用的浅色配色，主色为设计规定的种子色。 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF5B5BD6),
)

/** Android 12 以下使用的深色配色；主色提亮以保证深色表面上的可读性。 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9B8F0),
)

/**
 * 应用主题入口：按 [themeMode] 解析实际深浅色，System 跟随系统设置。
 *
 * Android 12+ 使用系统动态取色；低版本回退到内置配色。
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
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
