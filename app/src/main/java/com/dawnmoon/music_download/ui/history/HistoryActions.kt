package com.dawnmoon.music_download.ui.history

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.dawnmoon.music_download.data.repository.EnqueueOutcome
import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.model.DownloadTask
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.SongLink
import com.dawnmoon.music_download.download.DownloadController
import com.dawnmoon.music_download.download.TaskFileStore
import kotlinx.coroutines.CancellationException

/** 重新下载的结果；调用方据此区分缺少 Key、重复入队与真正的失败。 */
sealed interface RedownloadResult {

    data object Started : RedownloadResult

    data object MissingSongId : RedownloadResult

    data object MissingApiKey : RedownloadResult

    data object AlreadyQueued : RedownloadResult

    data object Failed : RedownloadResult
}

/**
 * 历史记录的打开、分享、重新下载与删除编排。
 *
 * 所有文件删除都以 [TaskFileStore.deleteById] 的返回值为准：只有确认文件已删除或本就
 * 不存在时才移除任务记录，失败时保留记录，避免出现"记录没了但文件还在"的孤儿文件。
 * 设置页与工作台历史 Tab 共用本类，保持两种入口的行为一致。
 *
 * MediaStore 与数据库之间没有跨存储事务：文件删除成功后若记录删除失败，无法回滚文件，
 * 只能保留记录并由界面提示。该顺序仍优于先删记录，至少不会留下无人引用的文件。
 */
class HistoryActions(
    private val repo: TaskRepository,
    private val fileStore: TaskFileStore,
    private val engine: DownloadController,
    private val appContext: Context,
) {

    /**
     * 删除单条历史记录。
     *
     * @return true 表示记录已删除；false 表示文件删除失败或记录删除抛出异常，此时记录保持不变。
     */
    suspend fun delete(task: DownloadTask, deleteFile: Boolean): Boolean {
        if (deleteFile) {
            val mediaStoreId = task.mediaStoreId
            // 没有媒体标识说明没有可追踪的文件，直接删记录即可；不能因此把记录永久卡住。
            if (mediaStoreId != null && !fileStore.deleteById(mediaStoreId)) return false
        }
        return try {
            repo.deleteRecord(task.id)
            true
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 清空全部历史。
     *
     * @return 因文件或记录删除失败而保留的记录数量；不删除文件时正常为 0。
     */
    suspend fun clearHistory(tasks: List<DownloadTask>, deleteFiles: Boolean): Int {
        if (!deleteFiles) {
            return try {
                repo.clearCompleted()
                0
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                tasks.size
            }
        }

        val removableIds = mutableListOf<Long>()
        var failedCount = 0
        tasks.forEach { task ->
            val mediaStoreId = task.mediaStoreId
            // 无媒体标识的记录没有文件可删，和 deleteById 的"不存在视为成功"保持一致。
            if (mediaStoreId == null || fileStore.deleteById(mediaStoreId)) {
                removableIds += task.id
            } else {
                failedCount += 1
            }
        }
        if (removableIds.isEmpty()) return failedCount

        return try {
            repo.deleteRecords(removableIds)
            failedCount
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            failedCount + removableIds.size
        }
    }

    /**
     * 按选定音质重新下载一条历史任务。
     *
     * 即使同一首歌仍在队列中，也按用户意图重新投递；仓库的重复判定决定最终是否创建
     * 新任务。只有在确实创建了新任务时才启动引擎。
     */
    suspend fun redownload(task: DownloadTask, quality: MusicQuality): RedownloadResult {
        val songId = task.songId ?: return RedownloadResult.MissingSongId
        return when (val outcome = repo.enqueueLinks(
            links = listOf(SongLink.Direct(task.sourceUrl, songId)),
            quality = quality,
        )) {
            is EnqueueOutcome.MissingApiKey -> RedownloadResult.MissingApiKey

            is EnqueueOutcome.NoLinks -> RedownloadResult.Failed

            is EnqueueOutcome.Enqueued -> when {
                outcome.created.isNotEmpty() -> {
                    engine.start(appContext)
                    engine.requestDownload(outcome.created)
                    RedownloadResult.Started
                }

                outcome.duplicates > 0 -> RedownloadResult.AlreadyQueued

                else -> RedownloadResult.Failed
            }
        }
    }

    companion object {

        private const val MEDIA_URI_PREFIX = "content://media/external/audio/media/"

        /** 打开完成文件；媒体标识缺失（文件已不存在）时返回 null。 */
        fun openIntent(mediaStoreId: Long?): Intent? =
            mediaStoreId?.let { id ->
                Intent(Intent.ACTION_VIEW).apply {
                    setData(mediaUri(id))
                    addFlags(READ_URI_FLAGS)
                }
            }

        /** 分享完成文件；媒体标识缺失（文件已不存在）时返回 null。 */
        fun shareIntent(mediaStoreId: Long?, title: String?): Intent? =
            mediaStoreId?.let { id ->
                val uri = mediaUri(id)
                Intent(Intent.ACTION_SEND).apply {
                    type = "audio/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(title.orEmpty(), uri)
                    addFlags(READ_URI_FLAGS)
                }
            }

        private fun mediaUri(mediaStoreId: Long): Uri = Uri.parse("$MEDIA_URI_PREFIX$mediaStoreId")

        private const val READ_URI_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
    }
}
