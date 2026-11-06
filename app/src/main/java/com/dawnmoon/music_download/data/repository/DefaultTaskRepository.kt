package com.dawnmoon.music_download.data.repository

import com.dawnmoon.music_download.core.AppClock
import com.dawnmoon.music_download.data.local.DownloadTaskDao
import com.dawnmoon.music_download.data.local.DownloadTaskEntity
import com.dawnmoon.music_download.data.remote.ApiFailure
import com.dawnmoon.music_download.data.remote.ApiResponse
import com.dawnmoon.music_download.data.remote.LinkResolver
import com.dawnmoon.music_download.domain.LinkExtractor
import com.dawnmoon.music_download.domain.TaskTransitions
import com.dawnmoon.music_download.domain.model.AudioFormat
import com.dawnmoon.music_download.domain.model.BaseFileName
import com.dawnmoon.music_download.domain.model.DownloadTask
import com.dawnmoon.music_download.domain.model.FailureKind
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.ResolvedTrack
import com.dawnmoon.music_download.domain.model.SongId
import com.dawnmoon.music_download.domain.model.SongLink
import com.dawnmoon.music_download.domain.model.TaskStatus
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * [TaskRepository] 的默认实现。
 *
 * 数据库是唯一事实来源：所有状态写入都先读取当前实体、按 [TaskTransitions] 校验后再整行更新，
 * 因此非法迁移不会产生部分写入。不改变状态的字段写入（进度、文件、换链）没有对应的迁移条目，
 * 改为校验当前状态是否属于该方法允许的前置状态，避免终态记录的字段被覆盖。
 *
 * 入队与文件名分配共享同一把仓库级 [Mutex]。文件名分配除查询数据库外，还会把本次已分配的
 * 名称按任务记入 [reservedFileNames]：名称在 [attachFile]/[markCompleted] 落库前不会出现在
 * 数据库里，只查询数据库会让并发分配拿到同一个名称。预留与任务绑定，在任务进入终态、
 * 重试或删除时释放，避免失败/取消的任务永久占用名称。
 */
class DefaultTaskRepository(
    private val dao: DownloadTaskDao,
    private val settings: AppSettings,
    private val shortLinkResolver: LinkResolver,
    private val clock: AppClock,
) : TaskRepository {

    private val mutex = Mutex()
    private val linkExtractor = LinkExtractor()

    /**
     * 已分配但尚未落库的文件名及其所属任务，受 [mutex] 保护。
     * 落库后即释放，由数据库继续负责去重；任务进入终态、重试或被删除时也会释放，
     * 因此进程内的预留数量不会随失败次数无界增长。
     */
    private val reservedFileNames = mutableMapOf<String, Long>()

    override fun observeInProgress(): Flow<List<DownloadTask>> =
        dao.observeInProgress().map { tasks -> tasks.map { it.toDomain() } }

    override fun observeCompleted(): Flow<List<DownloadTask>> =
        dao.observeCompleted().map { tasks -> tasks.map { it.toDomain() } }

    override suspend fun byId(id: Long): DownloadTask? = dao.byId(id)?.toDomain()

    // region 入队

    override suspend fun enqueue(text: String, quality: MusicQuality): EnqueueOutcome =
        mutex.withLock {
            val extraction = linkExtractor.extract(text)
            if (extraction.links.isEmpty()) {
                return@withLock EnqueueOutcome.NoLinks(extraction.ignoredUrlCount)
            }
            enqueueLocked(extraction.links, quality, extraction.ignoredUrlCount)
        }

    override suspend fun enqueueLinks(
        links: List<SongLink>,
        quality: MusicQuality,
    ): EnqueueOutcome = mutex.withLock {
        if (links.isEmpty()) return@withLock EnqueueOutcome.NoLinks(ignoredUrlCount = 0)
        enqueueLocked(links, quality, ignoredUrlCount = 0)
    }

    /**
     * 在已持有 [mutex] 时完成解析、Key 检查与事务插入。
     *
     * 短链解析先于 Key 检查，但未配置 Key 时无论解析结果如何都不插入任务，
     * 避免产生一份无法下载的记录。
     */
    private suspend fun enqueueLocked(
        links: List<SongLink>,
        quality: MusicQuality,
        ignoredUrlCount: Int,
    ): EnqueueOutcome {
        val resolutions = resolveLinks(links)
        if (settings.apiKey.first().isNullOrBlank()) {
            return EnqueueOutcome.MissingApiKey(extractedCount = links.size)
        }

        val created = mutableListOf<Long>()
        val failures = mutableListOf<EnqueueFailure>()
        var duplicates = 0
        for (resolution in resolutions) {
            when (resolution) {
                is LinkResolution.Resolved -> {
                    val id = dao.insertIfNoActive(
                        queuedEntity(
                            songId = resolution.songId,
                            sourceUrl = resolution.sourceUrl,
                            quality = quality,
                        ),
                    )
                    if (id == DUPLICATE_ID) duplicates++ else created += id
                }

                is LinkResolution.Failed ->
                    failures += EnqueueFailure(resolution.sourceUrl, resolution.failure)
            }
        }
        return EnqueueOutcome.Enqueued(created, duplicates, ignoredUrlCount, failures)
    }

    /** 直链可直接使用；短链解析并发上限为 2，避免一次入队打满网络连接。 */
    private suspend fun resolveLinks(links: List<SongLink>): List<LinkResolution> =
        coroutineScope {
            val permits = Semaphore(MAX_CONCURRENT_RESOLVES)
            links.map { link ->
                async { permits.withPermit { resolveLink(link) } }
            }.awaitAll()
        }

    private suspend fun resolveLink(link: SongLink): LinkResolution = when (link) {
        is SongLink.Direct -> LinkResolution.Resolved(link.sourceUrl, link.songId.value)

        is SongLink.ShortLink -> when (val response = shortLinkResolver.resolve(link)) {
            is ApiResponse.Success -> LinkResolution.Resolved(
                sourceUrl = link.sourceUrl,
                songId = response.value.value,
            )

            is ApiResponse.Failure -> LinkResolution.Failed(link.sourceUrl, response.failure)
        }
    }

    private fun queuedEntity(
        songId: Long,
        sourceUrl: String,
        quality: MusicQuality,
    ): DownloadTaskEntity {
        val now = clock.nowMillis()
        return DownloadTaskEntity(
            songId = songId,
            sourceUrl = sourceUrl,
            requestedQuality = quality.name,
            actualQuality = null,
            title = null,
            artist = null,
            album = null,
            artworkUrl = null,
            format = null,
            sizeBytes = null,
            md5 = null,
            streamUrl = null,
            etag = null,
            status = TaskStatus.Queued.name,
            bytesDownloaded = 0,
            mediaStoreId = null,
            fileName = null,
            failureKind = null,
            failureMessage = null,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
        )
    }

    // endregion

    // region 状态写入

    override suspend fun markResolving(id: Long) {
        transition(id, TaskStatus.Resolving)
    }

    override suspend fun completeResolve(id: Long, track: ResolvedTrack) {
        transition(id, TaskStatus.Waiting) {
            it.copy(
                songId = track.songId.value,
                requestedQuality = track.requestedQuality.name,
                title = track.title,
                artist = track.artist,
                album = track.album,
                artworkUrl = track.artworkUrl,
                actualQuality = track.actualQuality.name,
                format = track.format.name,
                sizeBytes = track.sizeBytes,
                md5 = track.md5,
                streamUrl = track.streamUrl,
                etag = track.etag,
            )
        }
    }

    override suspend fun startDownloading(id: Long) {
        transition(id, TaskStatus.Downloading)
    }

    override suspend fun updateProgress(id: Long, bytesDownloaded: Long) {
        updateExisting(id, allowedFrom = DOWNLOADING_ONLY) {
            it.copy(bytesDownloaded = bytesDownloaded)
        }
    }

    override suspend fun updateStream(id: Long, track: ResolvedTrack) {
        updateExisting(id, allowedFrom = STREAM_REFRESH_STATES) { current ->
            val sameContent = provesSameContent(current, track)
            current.copy(
                streamUrl = track.streamUrl,
                etag = track.etag,
                sizeBytes = track.sizeBytes,
                md5 = track.md5,
                // 无法证明内容一致时，旧字节可能属于另一份文件；清零并解除绑定，
                // 让下载层从零重新创建文件，而不是把新内容的字节追加到旧文件尾部。
                bytesDownloaded = if (sameContent) current.bytesDownloaded else 0,
                mediaStoreId = if (sameContent) current.mediaStoreId else null,
                fileName = if (sameContent) current.fileName else null,
            )
        }
    }

    override suspend fun markCompleted(
        id: Long,
        fileName: String,
        mediaStoreId: Long,
        completedAt: Long,
    ) {
        transition(id, TaskStatus.Completed) {
            it.copy(
                fileName = fileName,
                mediaStoreId = mediaStoreId,
                completedAt = completedAt,
            )
        }
        // 文件名已随任务落库，数据库查询可以继续负责去重。
        releaseFileName(id)
    }

    override suspend fun markFailed(id: Long, failureKind: FailureKind, message: String) {
        transition(id, TaskStatus.Failed) {
            it.copy(failureKind = failureKind.name, failureMessage = message)
        }
        // 失败可能发生在 attachFile 之前；释放该任务尚未落库的预留，避免后续同名分配被无谓加序号。
        releaseFileName(id)
    }

    override suspend fun markPaused(id: Long) {
        transition(id, TaskStatus.Paused)
    }

    override suspend fun markCancelled(id: Long) {
        transition(id, TaskStatus.Cancelled)
        releaseFileName(id)
    }

    override suspend fun resetForRetry(id: Long) {
        transition(id, TaskStatus.Queued) { it.clearedForRetry() }
        // 重试会重新解析并按新格式重新分配文件名，旧预留必须释放。
        releaseFileName(id)
    }

    override suspend fun attachFile(
        id: Long,
        fileName: String,
        mediaStoreId: Long,
        bytesDownloaded: Long,
    ) {
        try {
            updateExisting(id, allowedFrom = DOWNLOADING_ONLY) {
                it.copy(
                    fileName = fileName,
                    mediaStoreId = mediaStoreId,
                    bytesDownloaded = bytesDownloaded,
                )
            }
        } catch (e: Throwable) {
            // 写入失败时调用方多半会放弃该次创建；释放预留以免名称在本进程内永久占用。
            // 取消异常也必须完成释放，否则取消发生在 attachFile 期间同样会泄漏。
            withContext(NonCancellable) { releaseFileName(id) }
            throw e
        }
        // 落库后释放预留：此后 isFileNameTaken 已能看到该名称。
        releaseFileName(id)
    }

    // endregion

    // region 用户操作

    override suspend fun pause(id: Long) {
        markPaused(id)
    }

    override suspend fun resume(id: Long) {
        val current = dao.byId(id) ?: throw IllegalStateException("任务 $id 不存在")
        val target = if (current.streamUrl != null) TaskStatus.Waiting else TaskStatus.Queued
        transition(id, target)
    }

    override suspend fun retry(id: Long) {
        resetForRetry(id)
    }

    override suspend fun deleteRecord(id: Long) {
        dao.delete(id)
        releaseFileName(id)
    }

    override suspend fun deleteRecords(ids: List<Long>) {
        ids.forEach { dao.delete(it) }
        ids.forEach { releaseFileName(it) }
    }

    override suspend fun clearCompleted() {
        // Completed 任务通常在 markCompleted 时已释放预留；这里兜底清理历史遗留。
        val completedIds = dao.observeCompleted().first().map { it.id }
        dao.clearCompleted()
        completedIds.forEach { releaseFileName(it) }
    }

    override suspend fun pauseAll() {
        dao.allInProgress()
            .filter { TaskStatus.valueOf(it.status).isActive }
            .forEach { markPaused(it.id) }
    }

    override suspend fun resumeAll() {
        dao.allInProgress()
            .filter { it.status == TaskStatus.Paused.name }
            .forEach { resume(it.id) }
    }

    override suspend fun pendingIds(): List<Long> =
        dao.allInProgress()
            .filter { !TaskStatus.valueOf(it.status).isTerminal }
            .map { it.id }

    override suspend fun cancelAll() {
        dao.allInProgress()
            .filter { !TaskStatus.valueOf(it.status).isTerminal }
            .forEach { markCancelled(it.id) }
    }

    override suspend fun retryAllFailed() {
        dao.allInProgress()
            .filter { it.status == TaskStatus.Failed.name }
            .forEach { resetForRetry(it.id) }
    }

    // endregion

    // region 文件名分配

    override suspend fun allocateFileName(id: Long, base: BaseFileName): String = mutex.withLock {
        if (dao.byId(id) == null) throw IllegalStateException("任务 $id 不存在")
        val baseName = base.value
        if (isNameAvailable(baseName)) {
            reservedFileNames[baseName] = id
            return@withLock baseName
        }

        // 序号插在扩展名前：a.mp3 -> a (1).mp3。没有扩展名时直接追加序号。
        val dot = baseName.lastIndexOf('.')
        val stem = if (dot > 0) baseName.substring(0, dot) else baseName
        val extension = if (dot > 0) baseName.substring(dot) else ""

        var index = 1
        var candidate: String
        do {
            candidate = "$stem ($index)$extension"
            index++
        } while (!isNameAvailable(candidate))
        reservedFileNames[candidate] = id
        candidate
    }

    override suspend fun releaseFileName(id: Long) {
        mutex.withLock { releaseNamesLocked(id) }
    }

    /**
     * 名称可用 = 未被数据库中的未取消任务占用，且没有被本仓库分配后尚未落库。
     * 已在 [mutex] 内调用。
     */
    private suspend fun isNameAvailable(name: String): Boolean =
        name !in reservedFileNames && !dao.isFileNameTaken(name)

    /** 释放某任务的全部未落库预留；已在 [mutex] 内调用。 */
    private fun releaseNamesLocked(id: Long) {
        reservedFileNames.entries.removeAll { it.value == id }
    }

    // endregion

    /**
     * 读取当前任务、校验迁移并整行写回。
     *
     * @throws IllegalStateException 任务不存在或迁移不在 [TaskTransitions] 中
     */
    private suspend fun transition(
        id: Long,
        target: TaskStatus,
        mutate: (DownloadTaskEntity) -> DownloadTaskEntity = { it },
    ) {
        val current = dao.byId(id) ?: throw IllegalStateException("任务 $id 不存在")
        val from = TaskStatus.valueOf(current.status)
        if (!TaskTransitions.isAllowed(from, target)) {
            throw IllegalStateException("非法状态迁移：$from -> $target（任务 $id）")
        }
        dao.update(
            mutate(current).copy(
                status = target.name,
                updatedAt = clock.nowMillis(),
            ),
        )
    }

    /**
     * 不改变状态、只更新字段的写入。
     *
     * 这类写入没有对应的状态迁移条目（迁移表不含同状态迁移），因此改为校验当前状态属于
     * [allowedFrom]：既保留了“写入前先读实体”的约定，也阻止终态任务被改写。
     *
     * @throws IllegalStateException 任务不存在，或当前状态不在 [allowedFrom] 中
     */
    private suspend fun updateExisting(
        id: Long,
        allowedFrom: Set<TaskStatus>,
        mutate: (DownloadTaskEntity) -> DownloadTaskEntity,
    ) {
        val current = dao.byId(id) ?: throw IllegalStateException("任务 $id 不存在")
        val status = TaskStatus.valueOf(current.status)
        if (status !in allowedFrom) {
            throw IllegalStateException("任务 $id 处于 $status，不允许写入该字段")
        }
        dao.update(mutate(current).copy(updatedAt = clock.nowMillis()))
    }

    /**
     * 换链前后是否可证明指向同一份内容。
     *
     * MD5 是内容指纹，双方都存在时优先比较；任一方缺失时退化为比较 ETag。
     * 两者都无法比较时返回 false——仅 sizeBytes 相同不足以证明内容一致，
     * 继续续传可能把不同文件的字节拼接在一起。
     */
    private fun provesSameContent(
        current: DownloadTaskEntity,
        next: ResolvedTrack,
    ): Boolean = when {
        current.md5 != null && next.md5 != null -> current.md5 == next.md5
        current.etag != null && next.etag != null -> current.etag == next.etag
        else -> false
    }

    private fun DownloadTaskEntity.clearedForRetry(): DownloadTaskEntity = copy(
        actualQuality = null,
        title = null,
        artist = null,
        album = null,
        artworkUrl = null,
        format = null,
        sizeBytes = null,
        md5 = null,
        streamUrl = null,
        etag = null,
        bytesDownloaded = 0,
        mediaStoreId = null,
        fileName = null,
        failureKind = null,
        failureMessage = null,
    )

    private fun DownloadTaskEntity.toDomain(): DownloadTask = DownloadTask(
        id = id,
        songId = songId?.let { SongId(it) },
        sourceUrl = sourceUrl,
        requestedQuality = MusicQuality.valueOf(requestedQuality),
        actualQuality = actualQuality?.let { MusicQuality.valueOf(it) },
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl,
        format = format?.let { AudioFormat.valueOf(it) },
        sizeBytes = sizeBytes,
        md5 = md5,
        streamUrl = streamUrl,
        etag = etag,
        bytesDownloaded = bytesDownloaded,
        status = TaskStatus.valueOf(status),
        mediaStoreId = mediaStoreId,
        fileName = fileName,
        failureKind = failureKind?.let { FailureKind.valueOf(it) },
        failureMessage = failureMessage,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
    )

    private sealed interface LinkResolution {
        data class Resolved(val sourceUrl: String, val songId: Long) : LinkResolution

        data class Failed(val sourceUrl: String, val failure: ApiFailure) : LinkResolution
    }

    private companion object {
        const val MAX_CONCURRENT_RESOLVES = 2
        const val DUPLICATE_ID = -1L

        /** 进度与文件只属于正在下载的任务。 */
        val DOWNLOADING_ONLY = setOf(TaskStatus.Downloading)

        /** 换链发生在 Downloading -> Resolving 之后；两者都接受，兼容调用方先后顺序。 */
        val STREAM_REFRESH_STATES = setOf(TaskStatus.Downloading, TaskStatus.Resolving)
    }
}
