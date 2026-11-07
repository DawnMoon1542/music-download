package com.dawnmoon.music_download.ui.viewmodel

import android.content.Context
import android.os.Build
import com.dawnmoon.music_download.R
import com.dawnmoon.music_download.data.repository.AppSettings
import com.dawnmoon.music_download.data.repository.EnqueueOutcome
import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.download.DownloadController
import kotlinx.coroutines.flow.first

/**
 * 工作台与系统分享入口共享的入队用例。
 *
 * 入队结果决定两类必须一致的副作用：新任务交给下载引擎，以及首次下载时请求一次通知
 * 权限；提示文案也在这里拼装。调用方只负责用 Snackbar 或 Toast 呈现 [EnqueueResult]。
 */
class EnqueueDownloads(
    private val repo: TaskRepository,
    private val settings: AppSettings,
    private val engine: DownloadController,
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {

    /**
     * 执行入队，并在产生新任务时启动引擎、请求下载与按需触发通知权限。
     *
     * 权限请求只在 Android 13+ 且从未询问过时发生；标记在返回结果前写入，
     * 避免重复入队再次弹窗。是否真正展示权限弹窗由调用方决定（Toast 或事件）。
     */
    suspend fun enqueue(text: String, quality: MusicQuality): EnqueueResult =
        when (val outcome = repo.enqueue(text, quality)) {
            is EnqueueOutcome.MissingApiKey -> EnqueueResult.MissingApiKey

            is EnqueueOutcome.NoLinks -> EnqueueResult.NoLinks(text(R.string.enqueue_no_links))

            is EnqueueOutcome.Enqueued -> {
                val notificationPermissionNeeded = if (outcome.created.isNotEmpty()) {
                    engine.start(context)
                    engine.requestDownload(outcome.created)
                    requestNotificationPermissionIfNeeded()
                } else {
                    false
                }
                EnqueueResult.Enqueued(
                    outcome = outcome,
                    message = enqueuedMessage(outcome),
                    notificationPermissionNeeded = notificationPermissionNeeded,
                )
            }
        }

    /** 汇总新建、跳过与无法识别三类结果；全部为零时给出统一的空结果文案。 */
    private fun enqueuedMessage(outcome: EnqueueOutcome.Enqueued): String {
        val parts = buildList {
            if (outcome.created.isNotEmpty()) {
                add(text(R.string.enqueue_success, outcome.created.size))
            }
            if (outcome.duplicates > 0) {
                add(text(R.string.enqueue_skipped, outcome.duplicates))
            }
            if (outcome.failures.isNotEmpty()) {
                add(text(R.string.enqueue_unrecognized, outcome.failures.size))
            }
        }
        return parts.ifEmpty { listOf(text(R.string.enqueue_nothing)) }.joinToString("；")
    }

    private suspend fun requestNotificationPermissionIfNeeded(): Boolean {
        if (sdkInt < Build.VERSION_CODES.TIRAMISU) return false
        if (settings.notificationAsked.first()) return false
        settings.markNotificationAsked()
        return true
    }

    private fun text(resId: Int, vararg args: Any): String =
        context.resources.getString(resId, *args)
}

/** 共享入队用例的结果；文案已按当前上下文解析，两个入口展示同一套提示。 */
sealed interface EnqueueResult {

    data object MissingApiKey : EnqueueResult

    data class NoLinks(val message: String) : EnqueueResult

    data class Enqueued(
        val outcome: EnqueueOutcome.Enqueued,
        val message: String,
        val notificationPermissionNeeded: Boolean,
    ) : EnqueueResult
}
