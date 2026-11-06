package com.dawnmoon.music_download.data.repository

import com.dawnmoon.music_download.data.remote.ApiFailure
import com.dawnmoon.music_download.domain.model.BaseFileName
import com.dawnmoon.music_download.domain.model.DownloadTask
import com.dawnmoon.music_download.domain.model.FailureKind
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.ResolvedTrack
import com.dawnmoon.music_download.domain.model.SongLink
import kotlinx.coroutines.flow.Flow

/**
 * 下载任务的持久化入口。
 *
 * 界面与引擎都只通过本接口读写任务；状态转换由仓库按
 * [com.dawnmoon.music_download.domain.TaskTransitions] 校验后写入。
 */
interface TaskRepository {

    fun observeInProgress(): Flow<List<DownloadTask>>

    fun observeCompleted(): Flow<List<DownloadTask>>

    suspend fun byId(id: Long): DownloadTask?

    /** 从任意文本中提取歌曲链接并加入队列；短链会先被解析。 */
    suspend fun enqueue(text: String, quality: MusicQuality): EnqueueOutcome

    /** 把已提取的链接加入队列；空列表返回 `NoLinks(0)`，不会因空输入改报缺少 Key。 */
    suspend fun enqueueLinks(links: List<SongLink>, quality: MusicQuality): EnqueueOutcome

    suspend fun markResolving(id: Long)

    suspend fun completeResolve(id: Long, track: ResolvedTrack)

    suspend fun startDownloading(id: Long)

    /** 仅允许 Downloading 任务写入，避免终态记录的进度被覆盖。 */
    suspend fun updateProgress(id: Long, bytesDownloaded: Long)

    /**
     * 换链后更新直链、ETag、大小与校验值。
     *
     * 仅允许 Downloading/Resolving 任务写入。只有能证明新直链仍指向同一份内容时才保留
     * [DownloadTask.bytesDownloaded]：MD5 双方可用时比较 MD5，否则比较 ETag；
     * 无法证明时清零进度并解除文件绑定，避免把不同内容的字节追加到已有文件。
     */
    suspend fun updateStream(id: Long, track: ResolvedTrack)

    suspend fun markCompleted(id: Long, fileName: String, mediaStoreId: Long, completedAt: Long)

    suspend fun markFailed(id: Long, failureKind: FailureKind, message: String)

    suspend fun markPaused(id: Long)

    suspend fun markCancelled(id: Long)

    /** Failed -> Queued，并清空解析、进度、文件与失败字段，供重新解析使用。 */
    suspend fun resetForRetry(id: Long)

    /** 仅允许 Downloading 任务写入，避免终态记录的文件字段被覆盖。 */
    suspend fun attachFile(id: Long, fileName: String, mediaStoreId: Long, bytesDownloaded: Long)

    suspend fun pause(id: Long)

    /** 暂停的任务按是否已解析回到 Queued 或 Waiting。 */
    suspend fun resume(id: Long)

    suspend fun retry(id: Long)

    suspend fun deleteRecord(id: Long)

    suspend fun deleteRecords(ids: List<Long>)

    suspend fun clearCompleted()

    suspend fun pauseAll()

    suspend fun resumeAll()

    suspend fun pendingIds(): List<Long>

    suspend fun cancelAll()

    suspend fun retryAllFailed()

    /**
     * 为 [id] 任务在本应用未取消任务占用的文件名之外，返回 base 或带 ` (n)` 序号的首个可用名。
     *
     * 分配在同一临界区内完成，并按任务预留返回的名称；预留会在 [attachFile]/[markCompleted]
     * 落库或任务进入终态、被删除时释放，因此并发调用会得到互不重复的结果。
     */
    suspend fun allocateFileName(id: Long, base: BaseFileName): String

    /** 显式释放 [id] 任务尚未落库的文件名预留；创建文件失败或任务被放弃时由调用方使用。 */
    suspend fun releaseFileName(id: Long)
}

/** 入队结果；`created` 非空时由调用方启动下载引擎。 */
sealed interface EnqueueOutcome {

    /** 未配置 API Key；[extractedCount] 是提取到的链接数。 */
    data class MissingApiKey(val extractedCount: Int) : EnqueueOutcome

    /** 文本中没有可识别的歌曲链接；空链接列表入队也返回该结果并计 0 个被忽略的 URL。 */
    data class NoLinks(val ignoredUrlCount: Int) : EnqueueOutcome

    data class Enqueued(
        val created: List<Long>,
        val duplicates: Int,
        val ignoredUrlCount: Int,
        val failures: List<EnqueueFailure>,
    ) : EnqueueOutcome
}

/** 单条短链解析失败；失败链接不创建任务，也不影响同批其他链接。 */
data class EnqueueFailure(val sourceUrl: String, val failure: ApiFailure)
