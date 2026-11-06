package com.dawnmoon.music_download.smoke

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

/** Smoke-only service: verifies dataSync foreground service declaration and notification API. */
class SmokeService : LifecycleService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val channel = NotificationChannel(CHANNEL, "smoke", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification: Notification =
            if (Build.VERSION.SDK_INT >= 33) {
                Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("smoke")
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("smoke")
                    .build()
            }
        startForeground(1, notification)
        stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "smoke"
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val scope = SmokeScope(applicationContext)
        val sharedText = extractSharedLink(intent)
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val context = LocalContext.current
            val scheme =
                if (Build.VERSION.SDK_INT >= 31) {
                    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                } else {
                    if (dark) MaterialTheme.colorScheme else MaterialTheme.colorScheme
                }
            MaterialTheme(colorScheme = scheme) {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    androidx.compose.foundation.layout.Box(modifier = Modifier.padding(padding)) {
                        SmokeScreen(onClick = { lifecycleScope.launch { scope.settings.setApiKey("smoke") } })
                    }
                }
            }
        }
    }

    private fun extractSharedLink(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else null
}

@Suppress("unused")
private fun File.smoke(): Boolean = exists()

@Composable
private fun unused() {}
