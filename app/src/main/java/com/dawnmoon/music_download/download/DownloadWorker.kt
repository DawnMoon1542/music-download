package com.dawnmoon.music_download.download

import com.dawnmoon.music_download.core.AppClock
import com.dawnmoon.music_download.data.repository.AppSettings
import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.FileNameBuilder
import com.dawnmoon.music_download.domain.TrackResolver
import com.dawnmoon.music_download.domain.model.AudioFormat
import com.dawnmoon.music_download.domain.model.DownloadTask
import com.dawnmoon.music_download.domain.model.FailureKind
import com.dawnmoon.music_download.domain.model.SongId
import com.dawnmoon.music_download.domain.model.TaskStatus
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 单任务下载入口；实现负责把一个可执行任务推进到终态或暂停。 */
interface TaskDownloader {

    /** 执行 [taskId] 指向的任务，返回本次执行的结局。 */
    suspend fun execute(taskId: Long): WorkerResult
}

/** 一次任务执行的结局；失败时 [Failed.kind] 与写入仓库的失败原因一致。 */
sealed interface WorkerResult {
    data object Completed : WorkerResult

    data object Paused : WorkerResult

    data object Cancelled : WorkerResult

    data class Failed(val kind: FailureKind) : WorkerResult
}

/**
 * 基于任务行与直链的下载实现。
 *
 * 首次下载时创建 pending 文件并绑定到任务；下载完成后校验大小与 MD5，通过才发布文件。
 * 校验失败会删除自己创建的 pending 条目，避免残缺文件进入媒体库。
 *
 * [resolver] 与 [settings] 供后续的直链过期换链使用：换链需要重新解析并提供 API Key。
 */
class DefaultDownloadWorker(
    private val repo: TaskRepository,
    private val resolver: TrackResolver,
    private val settings: AppSettings,
    private val fileStore: TaskFileStore,
    private val namer: FileNamer,
    private val client: OkHttpClient,
    private val clock: AppClock,
) : TaskDownloader {

    override suspend fun execute(taskId: Long): WorkerResult {
        val task = repo.byId(taskId) ?: return WorkerResult.Failed(FailureKind.NotFound)
        // 本实现只接受新任务首下；Paused 任务须先由仓库层回到 Waiting 才能续传。
        if (task.status != TaskStatus.Waiting) {
            return WorkerResult.Failed(FailureKind.Unknown)
        }

        repo.startDownloading(taskId)

        val streamUrl = task.streamUrl
            ?: return fail(taskId, FailureKind.StreamExpired, "任务缺少直链")

        return try {
            val file = ensurePendingFile(task)
            download(task, file, streamUrl)
        } catch (error: IOException) {
            fail(taskId, FailureKind.Network, error.message ?: "网络中断")
        }
    }

    /**
     * 返回任务已绑定的文件；尚未绑定时按任务元数据分配唯一文件名并创建 pending 条目。
     *
     * 文件名分配与落库都在 `startDownloading` 之后进行，满足仓库对 `Downloading`
     * 状态才能写文件字段的约束。
     */
    private suspend fun ensurePendingFile(task: DownloadTask): CreatedFile {
        val existingId = task.mediaStoreId
        val existingName = task.fileName
        if (existingId != null && existingName != null) {
            return CreatedFile(mediaStoreId = existingId, displayName = existingName)
        }

        val format = task.format
            ?: AudioFormat.fromUrl(task.streamUrl.orEmpty(), task.actualQuality ?: task.requestedQuality)
        val base = FileNameBuilder.build(
            title = task.title.orEmpty(),
            artist = task.artist.orEmpty(),
            format = format,
            songIdForFallback = task.songId ?: SongId(0),
        )
        val fileName = namer.uniqueName(task.id, base)
        val created = fileStore.createPending(fileName, format.mimeType)
        repo.attachFile(
            id = task.id,
            fileName = fileName,
            mediaStoreId = created.mediaStoreId,
            bytesDownloaded = task.bytesDownloaded,
        )
        return created
    }

    /** 发起 GET、把响应流写入文件并完成校验。 */
    private suspend fun download(
        task: DownloadTask,
        file: CreatedFile,
        streamUrl: String,
    ): WorkerResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(streamUrl).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val kind = if (response.code >= SERVER_ERROR_MIN) {
                    FailureKind.ServiceUnavailable
                } else {
                    FailureKind.Network
                }
                return@withContext fail(task.id, kind, "HTTP ${response.code}")
            }

            val stream = response.body.byteStream()
            writeBody(task.id, file, stream)
            verifyAndFinish(task, file)
        }
    }

    /**
     * 以 64 KiB 块写入文件，并按 500ms 或 1 MiB 的间隔节流进度写库。
     *
     * 结束时会补写一次最终值；若最后一次节流写已等于总字节则不重复写。
     * 该节流避免把小文件下载变成逐块写数据库。
     */
    private suspend fun writeBody(
        taskId: Long,
        file: CreatedFile,
        stream: InputStream,
    ): Long {
        val output = fileStore.openTruncate(file)
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        var reported = 0L
        var lastReportAt = clock.elapsedRealtime()

        output.use { out ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                total += read

                val now = clock.elapsedRealtime()
                if (total - reported >= PROGRESS_BYTES ||
                    now - lastReportAt >= PROGRESS_INTERVAL_MS
                ) {
                    repo.updateProgress(taskId, total)
                    reported = total
                    lastReportAt = now
                }
            }
            out.flush()
        }

        if (total != reported) {
            repo.updateProgress(taskId, total)
        }
        return total
    }

    /** 校验实际落盘大小与 MD5；通过则发布文件，否则删除 pending 条目并标记失败。 */
    private suspend fun verifyAndFinish(task: DownloadTask, file: CreatedFile): WorkerResult {
        // 以文件实际长度为准；-1 表示条目已在写入后丢失，此时必须失败而不是用写入计数假装成功。
        val actualSize = fileStore.sizeOf(file)
        if (actualSize < 0) {
            return failIntegrity(
                task = task,
                file = file,
                message = "文件写入后丢失，无法校验完整性",
            )
        }

        val expectedSize = task.sizeBytes
        if (expectedSize != null && actualSize != expectedSize) {
            return failIntegrity(
                task = task,
                file = file,
                message = "大小校验失败：期望 $expectedSize 字节，实际 $actualSize 字节",
            )
        }

        val expectedMd5 = task.md5
        if (expectedMd5 != null) {
            val actualMd5 = fileStore.md5Of(file)
            if (!actualMd5.equals(expectedMd5, ignoreCase = true)) {
                return failIntegrity(task = task, file = file, message = "MD5 校验失败")
            }
        }

        fileStore.complete(file)
        repo.markCompleted(
            id = task.id,
            fileName = file.displayName,
            mediaStoreId = file.mediaStoreId,
            completedAt = clock.nowMillis(),
        )
        return WorkerResult.Completed
    }

    private suspend fun failIntegrity(
        task: DownloadTask,
        file: CreatedFile,
        message: String,
    ): WorkerResult {
        fileStore.delete(file)
        repo.markFailed(task.id, FailureKind.Integrity, message)
        return WorkerResult.Failed(FailureKind.Integrity)
    }

    private suspend fun fail(taskId: Long, kind: FailureKind, message: String): WorkerResult {
        repo.markFailed(taskId, kind, message)
        return WorkerResult.Failed(kind)
    }

    private companion object {
        /** 流式写入块大小：兼顾吞吐与内存占用。 */
        const val BUFFER_SIZE = 64 * 1024

        /** 进度写库的最短间隔。 */
        const val PROGRESS_INTERVAL_MS = 500L

        /** 两次进度写库之间允许累积的最大字节数；大文件即使耗时很短也需要可见进度。 */
        const val PROGRESS_BYTES = 1024L * 1024L

        const val SERVER_ERROR_MIN = 500
    }
}
