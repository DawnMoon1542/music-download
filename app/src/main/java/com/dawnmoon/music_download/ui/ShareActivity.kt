package com.dawnmoon.music_download.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.dawnmoon.music_download.MusicDownloadApp
import com.dawnmoon.music_download.R
import com.dawnmoon.music_download.domain.LinkExtractor
import com.dawnmoon.music_download.ui.viewmodel.EnqueueDownloads
import com.dawnmoon.music_download.ui.viewmodel.EnqueueResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 系统分享入口：接收 `ACTION_SEND` 的纯文本，提取网易云链接后静默入队并立即结束。
 *
 * Activity 不渲染界面，主题为透明；所有分支都会在入队结果确定后调用 [finish]，
 * 避免分享后残留空白界面。通知权限只在首次下载时请求一次，且不等待结果。
 */
class ShareActivity : ComponentActivity() {

    /** 权限结果只影响通知是否可见；入队与下载已独立完成，因此无需在回调里做任何事。 */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { handleShareIntent() }
    }

    private suspend fun handleShareIntent() {
        try {
            // EXTRA_TEXT 的契约是 CharSequence；带样式的分享文本若按 String 强转会返回空串。
            val text = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            // 预判一次链接，避免无分享文案的误触也调用仓库入队。
            if (LinkExtractor().extract(text).links.isEmpty()) {
                showToast(getString(R.string.enqueue_no_links))
                return
            }

            val dependencies = (application as MusicDownloadApp).dependencies
            val enqueueDownloads = EnqueueDownloads(
                repo = dependencies.repository,
                settings = dependencies.settings,
                engine = dependencies.downloadController,
                context = this,
            )
            when (val result = enqueueDownloads.enqueue(
                text,
                dependencies.settings.defaultQuality.first(),
            )) {
                is EnqueueResult.Enqueued -> {
                    showToast(result.message)
                    if (result.notificationPermissionNeeded) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                is EnqueueResult.MissingApiKey -> openMainActivityForApiKey()

                is EnqueueResult.NoLinks -> showToast(result.message)
            }
        } finally {
            finish()
        }
    }

    /** 未配置 Key 时转到主界面，并让设置页聚焦 Key 输入框。 */
    private fun openMainActivityForApiKey() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_FOCUS_KEY, true)
                // 分享入口位于已有任务栈时 MainActivity 不是栈顶，singleTop 不会生效；
                // CLEAR_TOP + SINGLE_TOP 才会复用旧实例并投递 onNewIntent。
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
