package com.dawnmoon.music_download.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dawnmoon.music_download.MusicDownloadApp
import com.dawnmoon.music_download.domain.model.ThemeMode
import com.dawnmoon.music_download.ui.theme.MusicDownloadTheme
import com.dawnmoon.music_download.ui.viewmodel.AppViewModelFactory

/** 应用唯一 Activity：创建依赖入口并显示路由根组合。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val viewModelFactory = AppViewModelFactory(
            dependencies = (application as MusicDownloadApp).dependencies,
        )
        setContent {
            MusicDownloadTheme(ThemeMode.System) {
                AppRoot(viewModelFactory = viewModelFactory)
            }
        }
    }
}
