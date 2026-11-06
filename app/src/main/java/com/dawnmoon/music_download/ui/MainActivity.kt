package com.dawnmoon.music_download.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.dawnmoon.music_download.domain.model.ThemeMode
import com.dawnmoon.music_download.ui.theme.MusicDownloadTheme

/** 应用唯一 Activity；Stage 1 仅提供可启动的空界面。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MusicDownloadTheme(ThemeMode.System) {
                Surface(Modifier.fillMaxSize()) {
                    Text("音乐下载")
                }
            }
        }
    }
}
