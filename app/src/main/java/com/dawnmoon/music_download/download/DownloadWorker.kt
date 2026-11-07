package com.dawnmoon.music_download.download

import com.dawnmoon.music_download.core.AppClock
import com.dawnmoon.music_download.data.remote.ApiFailure
import com.dawnmoon.music_download.data.remote.ApiResponse
import com.dawnmoon.music_download.data.repository.AppSettings
import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.FileNameBuilder
import com.dawnmoon.music_download.domain.TaskTransitions
import com.dawnmoon.music_download.domain.TrackResolver
import com.dawnmoon.music_download.domain.model.AudioFormat
import com.dawnmoon.music_download.domain.model.DownloadTask
import com.dawnmoon.music_download.domain.model.FailureKind
import com.dawnmoon.music_download.domain.model.ResolvedTrack
import com.dawnmoon.music_download.domain.model.SongId
import com.dawnmoon.music_download.domain.model.TaskStatus
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
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
 * 引擎取消下载 Job 时携带的用户意图。
 *
 * 两者都表现为协程取消，只有取消原因能区分收尾方式：暂停保留 pending 文件与已写字节，
 * 取消删除 pending 文件并把任务记为 `Cancelled`。作为 [CancellationException] 的子类型
 * 通过 `Job.cancel(signal)` 传入，下载器在 `NonCancellable` 收尾中读取。
 */
sealed class CancellationSignal : CancellationException() {
    data object Pause : CancellationSignal()

    data object Cancel : CancellationSignal()
}

/**
 * 基于任务行与直链的下载实现。
 *
 * 首次下载时创建 pending 文件并绑定到任务；已有进度时按文件实际大小校准后用
 * `Range`/`If-Range` 续传。下载完成后校验大小与 MD5，通过才发布文件。
 * 校验失败会删除自己创建的 pending 条目，避免残缺文件进入媒体库。
 *
 * 直链过期（403/404/410）或部分响应描述的文件大小与任务不符时，会重新解析并换链：
 * 内容未变则保留已写字节继续续传，格式或内容变化则删除旧条目并按新元数据重建。
 * 网络异常与 5xx 最多重试两次，416 最多截断重下一次。
 */
class DefaultDownloadWorker(
    private val repo: TaskRepository,
    private val resolver: TrackResolver,
    private val settings: AppSettings,
    private val fileStore: TaskFileStore,
    private val namer: FileNamer,
    private val client: OkHttpClient,
    private val clock: AppClock,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) : TaskDownloader {

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun execute(taskId: Long): WorkerResult = try {
        // 读取任务与迁移到 Downloading 同样是真实仓库的挂起点。若取消发生在这两处而不纳入
        // 收尾，暂停/取消请求会被直接抛出的 CancellationException 吞掉：任务可能停在 Waiting，
        // 也可能在状态已落库后停在 Downloading（Downloading->Waiting 非法，会话内无法恢复）。
        val task = repo.byId(taskId) ?: return WorkerResult.Failed(FailureKind.NotFound)
        // 本实现只接受新任务首下；Paused 任务须先由仓库层回到 Waiting 才能续传。
        if (task.status != TaskStatus.Waiting) {
            return WorkerResult.Failed(FailureKind.Unknown)
        }

        repo.startDownloading(taskId)

        if (task.streamUrl == null) {
            return fail(taskId, FailureKind.StreamExpired, "任务缺少直链")
        }

        val prepared = prepareFile(task)
        download(task, prepared.file, prepared.start)
    } catch (error: CancellationException) {
        // 协程取消后仍须把任务迁移到 Paused/Cancelled，并删除（或保留）pending 文件；
        // 收尾中的挂起点必须在 NonCancellable 中执行，否则第一个挂起点就会再次抛出取消。
        val signal = error as? CancellationSignal
            ?: currentCoroutineContext().job.getCancellationException() as? CancellationSignal
        finishCancelled(taskId, signal)
    } catch (error: IOException) {
        fail(taskId, FailureKind.Network, error.message ?: "网络中断")
    }

    /**
     * 协程被取消后的收尾：按 [signal] 区分暂停与取消。
     *
     * 暂停保留 pending 文件与已写字节，供之后续传；取消删除 pending 文件并保留任务行，
     * 让用户仍能看到该记录。无法识别的取消原因（例如作用域整体结束）按暂停处理，
     * 因为保留进度比丢弃更安全。
     *
     * 取消可能在终态写入已提交、但挂起点（如 `markCompleted` 之后的 `releaseFileName`
     * 仓库锁）尚未返回时才送达。此时任务行已是 Completed/Failed，既不能再迁移，也不能
     * 按 Cancelled 删除文件：那会误删已发布的正式媒体文件。因此收尾必须以任务行当前
     * 状态为准，先判断迁移是否合法，非法时直接返回既有结局。
     */
    private suspend fun finishCancelled(
        taskId: Long,
        signal: CancellationSignal?,
    ): WorkerResult = withContext(NonCancellable) {
        val task = repo.byId(taskId)
            ?: return@withContext WorkerResult.Failed(FailureKind.NotFound)
        if (signal == CancellationSignal.Cancel) {
            if (!TaskTransitions.isAllowed(task.status, TaskStatus.Cancelled)) {
                return@withContext outcomeOf(task)
            }
            val mediaStoreId = task.mediaStoreId
            val fileName = task.fileName
            if (mediaStoreId != null && fileName != null) {
                fileStore.delete(CreatedFile(mediaStoreId = mediaStoreId, displayName = fileName))
            }
            repo.markCancelled(taskId)
            WorkerResult.Cancelled
        } else {
            if (!TaskTransitions.isAllowed(task.status, TaskStatus.Paused)) {
                return@withContext outcomeOf(task)
            }
            repo.markPaused(taskId)
            WorkerResult.Paused
        }
    }

    /** 无法再按取消/暂停迁移时，任务当前状态对应的执行结局。 */
    private fun outcomeOf(task: DownloadTask): WorkerResult = when (task.status) {
        TaskStatus.Completed -> WorkerResult.Completed
        TaskStatus.Paused -> WorkerResult.Paused
        TaskStatus.Cancelled -> WorkerResult.Cancelled
        TaskStatus.Failed -> WorkerResult.Failed(task.failureKind ?: FailureKind.Unknown)
        else -> WorkerResult.Failed(FailureKind.Unknown)
    }

    /** 续传起点：已有文件的实际字节数；没有可复用文件时为 0。 */
    private data class Prepared(val file: CreatedFile, val start: Long)

    /**
     * 定位本次写入的文件并校准续传起点。
     *
     * 数据库中的 `bytesDownloaded` 可能落后于实际落盘字节（进程被杀时进度尚未写库），
     * 因此只要任务已绑定文件就以 [TaskFileStore.sizeOf] 为准；文件已丢失则重建条目并清零，
     * 避免把 Range 建立在不存在或不完整的文件上。
     */
    private suspend fun prepareFile(task: DownloadTask): Prepared {
        val mediaStoreId = task.mediaStoreId
        val fileName = task.fileName
        if (mediaStoreId == null || fileName == null) {
            return Prepared(createAndAttach(task, bytesDownloaded = 0), start = 0)
        }

        val file = CreatedFile(mediaStoreId = mediaStoreId, displayName = fileName)
        val actualSize = fileStore.sizeOf(file)
        if (actualSize < 0) {
            fileStore.delete(file)
            return Prepared(recreatePending(task, file), start = 0)
        }
        if (actualSize != task.bytesDownloaded) {
            repo.updateProgress(task.id, actualSize)
        }
        return Prepared(file, actualSize)
    }

    /**
     * 按任务元数据分配唯一文件名、创建 pending 条目并绑定到任务。
     *
     * 调用前任务必须已处于 `Downloading`；[bytesDownloaded] 由调用方决定：
     * 全新下载与格式变化重建传 0，已有文件绑定时不调用本方法。
     */
    private suspend fun createAndAttach(task: DownloadTask, bytesDownloaded: Long): CreatedFile {
        val format = resolveFormat(task)
        val base = FileNameBuilder.build(
            title = task.title.orEmpty(),
            artist = task.artist.orEmpty(),
            format = format,
            songIdForFallback = task.songId ?: SongId(0),
        )
        return createAndAttachNamed(
            task = task,
            format = format,
            fileName = namer.uniqueName(task.id, base),
            bytesDownloaded = bytesDownloaded,
        )
    }

    /**
     * 为丢失的条目重建 pending 文件。
     *
     * 旧条目的显示名通常可以原样复用：条目已经不存在，名称不会再与它冲突，而且重新分配时
     * 数据库里这一行仍持有旧文件名，[TaskRepository.allocateFileName] 会把任务自身算作占用，
     * 平白得到 ` (1)` 序号。只有当前格式与旧显示名的扩展名不一致（换链重建后进程恰好死亡）
     * 时才重新命名，此时旧扩展名与新的主名天然不同，不会自冲突。
     */
    private suspend fun recreatePending(task: DownloadTask, oldFile: CreatedFile): CreatedFile {
        val format = resolveFormat(task)
        val oldExtension = oldFile.displayName.substringAfterLast('.', missingDelimiterValue = "")
        return if (oldExtension.equals(format.extension, ignoreCase = true)) {
            createAndAttachNamed(
                task = task,
                format = format,
                fileName = oldFile.displayName,
                bytesDownloaded = 0,
            )
        } else {
            createAndAttach(task, bytesDownloaded = 0)
        }
    }

    /** 以给定文件名创建 pending 条目、绑定到任务并清零进度；调用前任务须为 `Downloading`。 */
    private suspend fun createAndAttachNamed(
        task: DownloadTask,
        format: AudioFormat,
        fileName: String,
        bytesDownloaded: Long,
    ): CreatedFile {
        val created = fileStore.createPending(fileName, format.mimeType)
        repo.attachFile(
            id = task.id,
            fileName = fileName,
            mediaStoreId = created.mediaStoreId,
            bytesDownloaded = bytesDownloaded,
        )
        return created
    }

    private fun resolveFormat(task: DownloadTask): AudioFormat = task.format
        ?: AudioFormat.fromUrl(task.streamUrl.orEmpty(), task.actualQuality ?: task.requestedQuality)

    /**
     * 发起请求并写盘，处理续传、换链与可重试错误。
     *
     * 每次循环对应一次 HTTP 请求；请求之间共享 [start]（已落盘字节）与当前任务行，
     * 换链成功后更新这两者后继续，瞬时失败则在退避后沿用最新起点重试。
     */
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun download(
        task: DownloadTask,
        initialFile: CreatedFile,
        initialStart: Long,
    ): WorkerResult = withContext(Dispatchers.IO) {
        var workingTask = task
        var file = initialFile
        var start = initialStart
        var transientAttempts = 0
        var refreshed = false
        var truncated = false

        /**
         * 以实际落盘大小校准起点；条目在写入过程中消失时重建 pending 条目并回到 0。
         * 重建会改变任务的文件绑定，因此需重新读取任务行。
         */
        suspend fun resync() {
            val recalibrated = recalibrate(workingTask, file)
            if (recalibrated.rebuilt) {
                workingTask = repo.byId(workingTask.id) ?: workingTask
            }
            file = recalibrated.file
            start = recalibrated.start
        }

        /**
         * 换链一次；已经换过链仍被拒绝则按 StreamExpired 失败，避免无限刷新。
         */
        suspend fun refreshOrFail(): Decision {
            if (refreshed) {
                return Decision.Done(
                    fail(workingTask.id, FailureKind.StreamExpired, "换链后直链仍不可用"),
                )
            }
            refreshed = true
            return when (val result = refreshLink(workingTask, file, start)) {
                is RefreshResult.Failed -> Decision.Done(result.result)
                is RefreshResult.Updated -> {
                    workingTask = result.task
                    file = result.file
                    start = result.start
                    Decision.Continue
                }
            }
        }

        while (true) {
            val streamUrl = workingTask.streamUrl
                ?: return@withContext fail(workingTask.id, FailureKind.StreamExpired, "任务缺少直链")

            val call = client.newCall(buildRequest(streamUrl, start, workingTask.etag))
            // 协程取消不会打断 OkHttp 的阻塞读；在取消开始时主动 cancel Call 关闭连接，
            // 让 execute/read 立即抛出 IOException，收尾逻辑才能在每个 1 秒内结束。
            val cancelHandle = currentCoroutineContext().job
                .invokeOnCompletion(onCancelling = true) { call.cancel() }

            val response = try {
                call.execute()
            } catch (error: IOException) {
                cancelHandle.dispose()
                // 取消触发的 IOException 必须按取消收尾，不能当成可重试的网络错误。
                currentCoroutineContext().ensureActive()
                resync()
                if (transientAttempts >= MAX_TRANSIENT_RETRIES) {
                    return@withContext fail(
                        workingTask.id,
                        FailureKind.Network,
                        error.message ?: "网络中断",
                    )
                }
                sleeper(RETRY_DELAYS[transientAttempts])
                transientAttempts += 1
                continue
            }

            var decision: Decision = Decision.Continue
            try {
                response.use { result ->
                    // ETag 必须在读取响应体之前取得：写入中断后下一次 Range 请求要靠它
                    // 通过 If-Range 证明服务端内容未变，否则续传可能把另一份文件拼进来。
                    if (result.isSuccessful) {
                        workingTask = captureEtag(workingTask, result.header("ETag"))
                    }
                    when {
                        result.code == HTTP_PARTIAL_CONTENT -> {
                            val total = contentRangeTotal(result.header("Content-Range"))
                            val knownTotal = workingTask.sizeBytes
                            if (total != null && knownTotal != null && total != knownTotal) {
                                // 服务端返回的区间属于另一份文件，不能把它追加到现有文件。
                                decision = refreshOrFail()
                            } else {
                                writeBody(
                                    taskId = workingTask.id,
                                    file = file,
                                    stream = result.body.byteStream(),
                                    append = true,
                                    from = start,
                                )
                                decision = Decision.Done(verifyAndFinish(workingTask, file))
                            }
                        }

                        result.code == HTTP_RANGE_NOT_SATISFIABLE -> {
                            if (truncated) {
                                decision = Decision.Done(
                                    fail(workingTask.id, FailureKind.Network, "Range 请求持续无法满足"),
                                )
                            } else {
                                truncated = true
                                start = 0
                                decision = Decision.Continue
                            }
                        }

                        result.isSuccessful -> {
                            // 200 表示服务端忽略了 Range；必须截断重写，不能追加。
                            writeBody(
                                taskId = workingTask.id,
                                file = file,
                                stream = result.body.byteStream(),
                                append = false,
                                from = 0,
                            )
                            decision = Decision.Done(verifyAndFinish(workingTask, file))
                        }

                        result.code in EXPIRED_LINK_CODES -> {
                            decision = refreshOrFail()
                        }

                        result.code >= SERVER_ERROR_MIN -> {
                            decision = Decision.RetryTransient
                        }

                        else -> {
                            decision = Decision.Done(
                                fail(workingTask.id, FailureKind.Network, "HTTP ${result.code}"),
                            )
                        }
                    }
                }
            } catch (error: StorageWriteFailure) {
                // 本地写盘失败（磁盘满等）重试也不会好转，删除不可用条目并直接失败。
                return@withContext failStorage(
                    taskId = workingTask.id,
                    file = file,
                    message = error.message ?: "本地写入失败",
                )
            } catch (error: IOException) {
                // 取消触发的读中断必须按取消收尾，不能当成可重试的网络错误。
                currentCoroutineContext().ensureActive()
                // 写入中途断流时以实际落盘字节为新的续传起点；已完成的结果不受关闭异常影响。
                val alreadyDone = decision is Decision.Done
                if (!alreadyDone) {
                    resync()
                    decision = Decision.RetryTransient
                }
            } finally {
                cancelHandle.dispose()
            }

            when (val current = decision) {
                is Decision.Done -> return@withContext current.result
                Decision.Continue -> Unit
                Decision.RetryTransient -> {
                    if (transientAttempts >= MAX_TRANSIENT_RETRIES) {
                        return@withContext fail(
                            workingTask.id,
                            FailureKind.ServiceUnavailable,
                            "服务端连续失败",
                        )
                    }
                    sleeper(RETRY_DELAYS[transientAttempts])
                    transientAttempts += 1
                }
            }
        }

        @Suppress("UNREACHABLE_CODE")
        return@withContext fail(workingTask.id, FailureKind.Unknown, "下载循环异常结束")
    }

    /** 一次请求处理后的走向。 */
    private sealed interface Decision {
        data object Continue : Decision

        data object RetryTransient : Decision

        data class Done(val result: WorkerResult) : Decision
    }

    /**
     * 本地存储容量不足（磁盘满）。
     *
     * 用独立类型把这类写入侧 [IOException] 与网络读取侧区分开：前者不可重试，
     * 也不应被报成 `Network`/`ServiceUnavailable`。
     */
    private class StorageWriteFailure(cause: IOException) : IOException(cause.message, cause)

    /** 换链结果；[Updated] 携带刷新后的任务行、文件与续传起点。 */
    private sealed interface RefreshResult {
        data class Updated(
            val task: DownloadTask,
            val file: CreatedFile,
            val start: Long,
        ) : RefreshResult

        data class Failed(val result: WorkerResult) : RefreshResult
    }

    /**
     * 重新解析直链并迁移任务状态。
     *
     * 新结果与当前文件内容一致（格式相同且 MD5 或 ETag 可证明未变）时沿用文件与进度，
     * 仅更新直链元数据；否则走 `completeResolve` 拿到最新格式，删除旧条目后重建。
     * [start] 是调用方当前实际写盘的位置，不能改用数据库里的节流进度作为续传起点。
     */
    private suspend fun refreshLink(
        task: DownloadTask,
        file: CreatedFile,
        start: Long,
    ): RefreshResult {
        repo.markResolving(task.id)

        val apiKey = settings.apiKey.first()
            ?: return RefreshResult.Failed(
                fail(task.id, FailureKind.InvalidKey, "缺少 API Key，无法换链"),
            )
        val songId = task.songId
            ?: return RefreshResult.Failed(
                fail(task.id, FailureKind.NotFound, "任务缺少歌曲标识"),
            )

        return when (val response = resolver.resolve(songId, task.requestedQuality, apiKey)) {
            is ApiResponse.Failure -> RefreshResult.Failed(
                fail(task.id, response.failure.toFailureKind(), response.failure.message),
            )

            is ApiResponse.Success -> {
                val track = response.value
                if (isSameContent(task, track)) {
                    repo.updateStream(task.id, track)
                    repo.startDownloading(task.id)
                    // 数据库进度按节流写入，可能落后于 [start]；换链后先对齐，避免之后的
                    // 进程恢复把旧值当成续传起点。
                    repo.updateProgress(task.id, start)
                    val updated = repo.byId(task.id)
                        ?: return RefreshResult.Failed(
                            fail(task.id, FailureKind.Unknown, "换链后任务丢失"),
                        )
                    RefreshResult.Updated(updated, file, start)
                } else {
                    rebuildForNewContent(task, file, track)
                }
            }
        }
    }

    /**
     * 内容变化时迁移到新文件。
     *
     * 同格式：先截断并复用原条目，既避免重新分配文件名把任务自身旧文件名算作占用而多出
     * ` (1)` 序号，也保证进程在切换后死亡时不会从旧字节继续发 Range。条目不可写而无法
     * 截断时，必须在切换元数据前删除旧条目：否则库里已是新 md5/sizeBytes，却仍绑定旧
     * 字节，恢复时会用新元数据对旧内容发 Range 并拼接出损坏文件。
     * 不同格式：先删除旧条目，再切换元数据并重建；即使进程在 attach 前死亡，恢复时
     * `sizeOf` 返回 -1，会按新格式重建，不会把新内容追加到旧文件尾部。
     */
    private suspend fun rebuildForNewContent(
        task: DownloadTask,
        oldFile: CreatedFile,
        track: ResolvedTrack,
    ): RefreshResult {
        if (task.format == track.format) {
            // 条目可能在解析期间被系统清理或不可写；此时不做截断，按丢失处理。
            var reusable = false
            if (fileStore.sizeOf(oldFile) >= 0) {
                try {
                    fileStore.openTruncate(oldFile).close()
                    reusable = true
                } catch (_: IOException) {
                    // 无法截断的条目不能继续复用，按丢失处理。
                }
            }

            // 不可复用时，旧条目必须在切换元数据前解除绑定，否则进程在切换后死亡会让
            // 恢复流程以新元数据续传旧字节。
            if (!reusable) {
                fileStore.delete(oldFile)
            }

            repo.completeResolve(task.id, track)
            repo.startDownloading(task.id)
            repo.updateProgress(task.id, 0)
            val updated = repo.byId(task.id)
                ?: return RefreshResult.Failed(
                    fail(task.id, FailureKind.Unknown, "换链后任务丢失"),
                )
            if (reusable) {
                return RefreshResult.Updated(updated, oldFile, start = 0)
            }
            val recreated = recreatePending(updated, oldFile)
            val attached = repo.byId(task.id) ?: updated
            return RefreshResult.Updated(attached, recreated, start = 0)
        }

        fileStore.delete(oldFile)
        repo.completeResolve(task.id, track)
        repo.startDownloading(task.id)
        val updated = repo.byId(task.id)
            ?: return RefreshResult.Failed(
                fail(task.id, FailureKind.Unknown, "换链后任务丢失"),
            )
        return RefreshResult.Updated(
            task = updated,
            file = createAndAttach(updated, bytesDownloaded = 0),
            start = 0,
        )
    }

    /** 只有格式相同且 MD5（双方可用）或 ETag 能证明内容未变时，才允许复用已写字节。 */
    private fun isSameContent(current: DownloadTask, refreshed: ResolvedTrack): Boolean {
        if (current.format != refreshed.format) return false
        return when {
            current.md5 != null && refreshed.md5 != null ->
                current.md5.equals(refreshed.md5, ignoreCase = true)

            current.etag != null && refreshed.etag != null ->
                current.etag == refreshed.etag

            else -> false
        }
    }

    /**
     * 捕获成功响应头中的 ETag 并持久化，供后续续传用 `If-Range` 校验内容未变。
     *
     * 只有任务已有 MD5 时才写入：构造的 [ResolvedTrack] 复用任务现有元数据、仅替换 etag，
     * 使仓库的 `provesSameContent` 走 MD5 分支，从而保留已写字节与文件绑定。缺少 MD5 时
     * 无法证明内容一致，若贸然写入仓库会按未知内容处理而清零进度并解除文件绑定。
     *
     * 返回带上新 ETag 的任务副本，让当前循环里紧接着的 Range 请求立即带 `If-Range`。
     */
    private suspend fun captureEtag(task: DownloadTask, etag: String?): DownloadTask {
        val md5 = task.md5 ?: return task
        if (etag == null || etag == task.etag) return task
        val songId = task.songId ?: return task
        val streamUrl = task.streamUrl ?: return task
        repo.updateStream(
            task.id,
            ResolvedTrack(
                songId = songId,
                title = task.title.orEmpty(),
                artist = task.artist.orEmpty(),
                album = task.album,
                artworkUrl = task.artworkUrl,
                requestedQuality = task.requestedQuality,
                actualQuality = task.actualQuality ?: task.requestedQuality,
                format = resolveFormat(task),
                streamUrl = streamUrl,
                sizeBytes = task.sizeBytes,
                md5 = md5,
                etag = etag,
            ),
        )
        return task.copy(etag = etag)
    }

    private fun buildRequest(url: String, start: Long, etag: String?): Request =
        Request.Builder().url(url).get().apply {
            if (start > 0) {
                header("Range", "bytes=$start-")
                if (etag != null) header("If-Range", etag)
            }
        }.build()

    /** 重新校准后的文件与续传起点；[rebuilt] 表示文件绑定已被替换。 */
    private data class Recalibration(
        val file: CreatedFile,
        val start: Long,
        val rebuilt: Boolean,
    )

    /**
     * 以实际落盘大小作为新的续传起点。
     *
     * 条目在下载中途消失时 [TaskFileStore.sizeOf] 返回 -1：继续沿用旧偏移发 `Range`
     * 会让 openAppend/openTruncate 因条目不存在而抛错，或把响应写到错误的文件。
     * 因此这里重建 pending 条目并回到 0，由下一次完整响应从头写入。
     */
    private suspend fun recalibrate(task: DownloadTask, file: CreatedFile): Recalibration {
        val actual = fileStore.sizeOf(file)
        if (actual >= 0) return Recalibration(file, actual, rebuilt = false)
        fileStore.delete(file)
        return Recalibration(recreatePending(task, file), start = 0, rebuilt = true)
    }

    /** `Content-Range: bytes <start>-<end>/<total>` 中的 total；缺失或为 `*` 时返回 null。 */
    private fun contentRangeTotal(header: String?): Long? =
        header?.substringAfterLast('/', missingDelimiterValue = "")?.toLongOrNull()

    private fun ApiFailure.toFailureKind(): FailureKind = when (this) {
        is ApiFailure.InvalidKey -> FailureKind.InvalidKey
        is ApiFailure.QuotaExhausted -> FailureKind.QuotaExhausted
        is ApiFailure.Forbidden -> FailureKind.Forbidden
        is ApiFailure.RateLimited -> FailureKind.RateLimited
        is ApiFailure.InvalidRequest -> FailureKind.InvalidRequest
        is ApiFailure.QualityUnavailable -> FailureKind.QualityUnavailable
        is ApiFailure.NotFound -> FailureKind.NotFound
        is ApiFailure.ServiceUnavailable -> FailureKind.ServiceUnavailable
        is ApiFailure.Network -> FailureKind.Network
        is ApiFailure.Malformed -> FailureKind.Malformed
        is ApiFailure.LinkUnsupported -> FailureKind.Unknown
    }

    /**
     * 写入响应体并节流汇报进度。
     *
     * [append] 为 true 时保留已有字节并从 [from] 继续计数；否则截断文件从头写。
     * 结束必定汇报最终字节数，避免 UI 停在最后一个节流点。
     */
    private suspend fun writeBody(
        taskId: Long,
        file: CreatedFile,
        stream: InputStream,
        append: Boolean,
        from: Long,
    ): Long {
        val reporter = ProgressReporter(clock) { bytes -> repo.updateProgress(taskId, bytes) }
        val output = try {
            if (append) fileStore.openAppend(file) else fileStore.openTruncate(file)
        } catch (error: IOException) {
            if (isStorageFailure(error)) throw StorageWriteFailure(error)
            throw error
        }
        val buffer = ByteArray(BUFFER_SIZE)
        var total = from

        output.use { out ->
            while (true) {
                // 读取来自网络，保留原始 IOException，交由瞬时重试处理。
                val read = stream.read(buffer)
                if (read < 0) break
                try {
                    out.write(buffer, 0, read)
                } catch (error: IOException) {
                    // 只有磁盘空间不足这类本地存储错误才终止；其余写入异常沿用瞬时重试。
                    if (isStorageFailure(error)) throw StorageWriteFailure(error)
                    throw error
                }
                total += read
                reporter.report(total)
            }
            try {
                out.flush()
            } catch (error: IOException) {
                if (isStorageFailure(error)) throw StorageWriteFailure(error)
                throw error
            }
        }

        reporter.finish(total)
        return total
    }

    /** 是否为本地存储容量/写入不可用错误（磁盘满），这类错误重试无意义。 */
    private fun isStorageFailure(error: IOException): Boolean {
        val message = error.message ?: return false
        return message.contains("ENOSPC", ignoreCase = true) ||
            message.contains("No space left on device", ignoreCase = true)
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

    /** 本地写盘失败：删除不可用条目并记为 [FailureKind.Storage]，不进入网络重试。 */
    private suspend fun failStorage(
        taskId: Long,
        file: CreatedFile,
        message: String,
    ): WorkerResult {
        fileStore.delete(file)
        repo.markFailed(taskId, FailureKind.Storage, message)
        return WorkerResult.Failed(FailureKind.Storage)
    }

    private companion object {
        /** 流式写入块大小：兼顾吞吐与内存占用。 */
        const val BUFFER_SIZE = 64 * 1024

        const val SERVER_ERROR_MIN = 500

        const val HTTP_PARTIAL_CONTENT = 206

        const val HTTP_RANGE_NOT_SATISFIABLE = 416

        /** 直链失效状态码；这类响应需要重新解析，而不是原样重试。 */
        val EXPIRED_LINK_CODES = setOf(403, 404, 410)

        /** 瞬时错误最多重试次数（首次请求之外）。 */
        const val MAX_TRANSIENT_RETRIES = 2

        /** 每次重试前的退避时长。 */
        val RETRY_DELAYS = longArrayOf(2_000L, 5_000L)
    }
}

/**
 * 进度写库节流器。
 *
 * 两次写库之间至少间隔 500ms 或累积 1 MiB，二者满足其一即写；[finish] 强制落最终值。
 * 依赖注入 [clock] 与 [write]，使节流逻辑可以脱离仓库与真实时间单独验证。
 */
internal class ProgressReporter(
    private val clock: AppClock,
    private val write: suspend (Long) -> Unit,
) {

    private var reported = 0L
    private var lastReportAt = clock.elapsedRealtime()

    /** 汇报当前已下载字节；未达到任一阈值时仅更新内存状态。 */
    suspend fun report(downloaded: Long) {
        val now = clock.elapsedRealtime()
        if (downloaded - reported >= PROGRESS_BYTES ||
            now - lastReportAt >= PROGRESS_INTERVAL_MS
        ) {
            write(downloaded)
            reported = downloaded
            lastReportAt = now
        }
    }

    /** 结束写入时补报最终值；最后一次节流写已等于 [total] 时不重复写。 */
    suspend fun finish(total: Long) {
        if (total != reported) {
            write(total)
            reported = total
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 500L

        const val PROGRESS_BYTES = 1024L * 1024L
    }
}
