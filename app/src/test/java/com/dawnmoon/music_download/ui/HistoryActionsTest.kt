package com.dawnmoon.music_download.ui

import android.content.Context
import android.content.ContextWrapper
import com.dawnmoon.music_download.data.repository.EnqueueOutcome
import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.model.BaseFileName
import com.dawnmoon.music_download.domain.model.DownloadTask
import com.dawnmoon.music_download.domain.model.FailureKind
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.ResolvedTrack
import com.dawnmoon.music_download.domain.model.SongId
import com.dawnmoon.music_download.domain.model.SongLink
import com.dawnmoon.music_download.domain.model.TaskStatus
import com.dawnmoon.music_download.download.CreatedFile
import com.dawnmoon.music_download.download.DownloadController
import com.dawnmoon.music_download.download.EngineSnapshot
import com.dawnmoon.music_download.download.TaskFileStore
import com.dawnmoon.music_download.ui.history.HistoryActions
import com.dawnmoon.music_download.ui.history.RedownloadResult
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HistoryActions] 的历史记录编排测试。
 *
 * 仓库与文件存储都用行为化的 Fake：删除记录会真实改变 Fake 仓库的列表，删除文件会真实
 * 改变 Fake 存储的剩余条目，因此断言的是用户可见结果而不是调用次数。
 *
 * Intent 的内容（action、URI、flags）由 [HistoryScreenTest] 在设备上断言：JVM 单元测试
 * 运行在 mockable android.jar 上，无法构造 `android.content.Intent`。
 */
class HistoryActionsTest {

    private val context: Context = ContextWrapper(null)

    @Test
    fun `open and share are unavailable when the file is missing`() {
        assertNull(HistoryActions.openIntent(null))
        assertNull(HistoryActions.shareIntent(null, "夏霞"))
    }

    @Test
    fun `deleting a record with its file removes both when the file store succeeds`() = runTest {
        val task = completedTask(id = 1L, mediaStoreId = 42L)
        val repo = FakeTaskRepository(completed = listOf(task))
        val fileStore = FakeTaskFileStore()
        val actions = HistoryActions(repo, fileStore, FakeDownloadController(), context)

        val removed = actions.delete(task, deleteFile = true)

        assertTrue(removed)
        assertTrue(repo.completed.value.isEmpty())
        assertEquals(setOf(42L), fileStore.deletedIds)
    }

    @Test
    fun `deleting a record without a media id removes it without touching files`() = runTest {
        val task = completedTask(id = 1L, mediaStoreId = null)
        val repo = FakeTaskRepository(completed = listOf(task))
        val actions = HistoryActions(repo, UntouchableTaskFileStore(), FakeDownloadController(), context)

        val removed = actions.delete(task, deleteFile = true)

        assertTrue(removed)
        assertTrue(repo.completed.value.isEmpty())
    }

    @Test
    fun `a failed file deletion keeps the record`() = runTest {
        val task = completedTask(id = 1L, mediaStoreId = 42L)
        val repo = FakeTaskRepository(completed = listOf(task))
        val fileStore = FakeTaskFileStore(failingIds = setOf(42L))
        val actions = HistoryActions(repo, fileStore, FakeDownloadController(), context)

        val removed = actions.delete(task, deleteFile = true)

        assertFalse(removed)
        assertEquals(listOf(1L), repo.completed.value.map { it.id })
        assertTrue(fileStore.deletedIds.isEmpty())
    }

    @Test
    fun `deleting only the record leaves the file untouched`() = runTest {
        val task = completedTask(id = 1L, mediaStoreId = 42L)
        val repo = FakeTaskRepository(completed = listOf(task))
        val fileStore = UntouchableTaskFileStore()
        val actions = HistoryActions(repo, fileStore, FakeDownloadController(), context)

        val removed = actions.delete(task, deleteFile = false)

        assertTrue(removed)
        assertTrue(repo.completed.value.isEmpty())
    }

    @Test
    fun `clearing history removes only the records whose file was deleted`() = runTest {
        val tasks = listOf(
            completedTask(id = 1L, mediaStoreId = 11L),
            completedTask(id = 2L, mediaStoreId = 12L),
        )
        val repo = FakeTaskRepository(completed = tasks)
        val fileStore = FakeTaskFileStore(failingIds = setOf(12L))
        val actions = HistoryActions(repo, fileStore, FakeDownloadController(), context)

        val failedCount = actions.clearHistory(tasks, deleteFiles = true)

        assertEquals(1, failedCount)
        assertEquals(listOf(2L), repo.completed.value.map { it.id })
        assertEquals(setOf(11L), fileStore.deletedIds)
    }

    @Test
    fun `clearing history treats a missing media id as nothing to delete`() = runTest {
        val tasks = listOf(
            completedTask(id = 1L, mediaStoreId = null),
            completedTask(id = 2L, mediaStoreId = 12L),
        )
        val repo = FakeTaskRepository(completed = tasks)
        val fileStore = FakeTaskFileStore()
        val actions = HistoryActions(repo, fileStore, FakeDownloadController(), context)

        val failedCount = actions.clearHistory(tasks, deleteFiles = true)

        assertEquals(0, failedCount)
        assertTrue(repo.completed.value.isEmpty())
        assertEquals(setOf(12L), fileStore.deletedIds)
    }

    @Test
    fun `clearing history without deleting files clears every record`() = runTest {
        val tasks = listOf(
            completedTask(id = 1L, mediaStoreId = 11L),
            completedTask(id = 2L, mediaStoreId = 12L),
        )
        val repo = FakeTaskRepository(completed = tasks)
        val fileStore = UntouchableTaskFileStore()
        val actions = HistoryActions(repo, fileStore, FakeDownloadController(), context)

        val failedCount = actions.clearHistory(tasks, deleteFiles = false)

        assertEquals(0, failedCount)
        assertTrue(repo.completed.value.isEmpty())
    }

    @Test
    fun `redownload enqueues the direct song link and starts the engine`() = runTest {
        val task = completedTask(
            id = 7L,
            mediaStoreId = 42L,
            songId = 9001L,
            sourceUrl = "https://music.163.com/song?id=9001",
        )
        val repo = FakeTaskRepository(completed = listOf(task)).apply {
            enqueueOutcome = EnqueueOutcome.Enqueued(
                created = listOf(88L),
                duplicates = 0,
                ignoredUrlCount = 0,
                failures = emptyList(),
            )
        }
        val engine = FakeDownloadController()
        val actions = HistoryActions(repo, FakeTaskFileStore(), engine, context)

        val result = actions.redownload(task, MusicQuality.Lossless)

        assertEquals(RedownloadResult.Started, result)
        assertEquals(
            listOf<SongLink>(SongLink.Direct(task.sourceUrl, SongId(9001L))),
            repo.enqueuedLinks,
        )
        assertEquals(MusicQuality.Lossless, repo.enqueuedQuality)
        assertEquals(1, engine.startCount)
        assertEquals(listOf(88L), engine.requestedIds)
    }

    @Test
    fun `redownload reports a missing api key instead of a generic failure`() = runTest {
        val task = completedTask(id = 7L, mediaStoreId = 42L, songId = 9001L)
        val repo = FakeTaskRepository(completed = listOf(task)).apply {
            enqueueOutcome = EnqueueOutcome.MissingApiKey(extractedCount = 1)
        }
        val engine = FakeDownloadController()
        val actions = HistoryActions(repo, FakeTaskFileStore(), engine, context)

        val result = actions.redownload(task, MusicQuality.Lossless)

        assertEquals(RedownloadResult.MissingApiKey, result)
        assertEquals(0, engine.startCount)
        assertTrue(engine.requestedIds.isEmpty())
    }

    @Test
    fun `redownload reports an already queued song when nothing is created`() = runTest {
        val task = completedTask(id = 7L, mediaStoreId = 42L, songId = 9001L)
        val repo = FakeTaskRepository(completed = listOf(task)).apply {
            enqueueOutcome = EnqueueOutcome.Enqueued(
                created = emptyList(),
                duplicates = 1,
                ignoredUrlCount = 0,
                failures = emptyList(),
            )
        }
        val engine = FakeDownloadController()
        val actions = HistoryActions(repo, FakeTaskFileStore(), engine, context)

        val result = actions.redownload(task, MusicQuality.Lossless)

        assertEquals(RedownloadResult.AlreadyQueued, result)
        assertEquals(0, engine.startCount)
    }

    @Test
    fun `redownload does nothing when the task has no song id`() = runTest {
        val task = completedTask(id = 7L, mediaStoreId = 42L, songId = null)
        val repo = FakeTaskRepository(completed = listOf(task))
        val engine = FakeDownloadController()
        val actions = HistoryActions(repo, FakeTaskFileStore(), engine, context)

        val result = actions.redownload(task, MusicQuality.Lossless)

        assertEquals(RedownloadResult.MissingSongId, result)
        assertNull(repo.enqueuedLinks)
        assertEquals(0, engine.startCount)
        assertTrue(engine.requestedIds.isEmpty())
    }

    private fun completedTask(
        id: Long,
        mediaStoreId: Long?,
        songId: Long? = id + 1L,
        sourceUrl: String = "https://music.163.com/song?id=${id + 1L}",
    ): DownloadTask = DownloadTask(
        id = id,
        songId = songId?.let(::SongId),
        sourceUrl = sourceUrl,
        requestedQuality = MusicQuality.Exhigh,
        actualQuality = MusicQuality.Exhigh,
        title = "夏霞",
        artist = "あたらよ",
        album = null,
        artworkUrl = null,
        format = null,
        sizeBytes = 1_000L,
        md5 = null,
        streamUrl = null,
        etag = null,
        bytesDownloaded = 1_000L,
        status = TaskStatus.Completed,
        mediaStoreId = mediaStoreId,
        fileName = "夏霞.mp3",
        failureKind = null,
        failureMessage = null,
        createdAt = id,
        updatedAt = id,
        completedAt = 1_000L + id,
    )

    /** 记录实际删除过的媒体标识；[failingIds] 中的条目模拟存储删除失败。 */
    private class FakeTaskFileStore(
        private val failingIds: Set<Long> = emptySet(),
    ) : TaskFileStore {

        val deletedIds = mutableSetOf<Long>()

        override suspend fun deleteById(mediaStoreId: Long): Boolean {
            if (mediaStoreId in failingIds) return false
            deletedIds += mediaStoreId
            return true
        }

        override suspend fun createPending(displayName: String, mimeType: String): CreatedFile =
            error("unused")

        override suspend fun openAppend(file: CreatedFile): OutputStream = error("unused")

        override suspend fun openTruncate(file: CreatedFile): OutputStream = error("unused")

        override suspend fun complete(file: CreatedFile) = error("unused")

        override suspend fun delete(file: CreatedFile) = error("unused")

        override suspend fun sizeOf(file: CreatedFile): Long = error("unused")

        override suspend fun md5Of(file: CreatedFile): String = error("unused")
    }

    /** 任何文件操作都视为违反预期：用于验证"仅删记录"不会碰文件存储。 */
    private class UntouchableTaskFileStore : TaskFileStore {

        override suspend fun deleteById(mediaStoreId: Long): Boolean =
            error("file store must not be touched")

        override suspend fun createPending(displayName: String, mimeType: String): CreatedFile =
            error("file store must not be touched")

        override suspend fun openAppend(file: CreatedFile): OutputStream =
            error("file store must not be touched")

        override suspend fun openTruncate(file: CreatedFile): OutputStream =
            error("file store must not be touched")

        override suspend fun complete(file: CreatedFile) =
            error("file store must not be touched")

        override suspend fun delete(file: CreatedFile) =
            error("file store must not be touched")

        override suspend fun sizeOf(file: CreatedFile): Long =
            error("file store must not be touched")

        override suspend fun md5Of(file: CreatedFile): String =
            error("file store must not be touched")
    }

    /** 只实现历史操作涉及的仓库方法；其余方法一旦被调用即说明实现越过职责边界。 */
    private class FakeTaskRepository(completed: List<DownloadTask>) : TaskRepository {

        val completed = MutableStateFlow(completed)
        var enqueuedLinks: List<SongLink>? = null
        var enqueuedQuality: MusicQuality? = null
        var enqueueOutcome: EnqueueOutcome = EnqueueOutcome.Enqueued(
            created = emptyList(),
            duplicates = 0,
            ignoredUrlCount = 0,
            failures = emptyList(),
        )

        override fun observeInProgress(): Flow<List<DownloadTask>> = error("unused")

        override fun observeCompleted(): Flow<List<DownloadTask>> = completed

        override suspend fun byId(id: Long): DownloadTask? =
            completed.value.firstOrNull { it.id == id }

        override suspend fun enqueue(text: String, quality: MusicQuality): EnqueueOutcome =
            error("unused")

        override suspend fun enqueueLinks(
            links: List<SongLink>,
            quality: MusicQuality,
        ): EnqueueOutcome {
            enqueuedLinks = links
            enqueuedQuality = quality
            return enqueueOutcome
        }

        override suspend fun markResolving(id: Long) = error("unused")

        override suspend fun completeResolve(id: Long, track: ResolvedTrack) = error("unused")

        override suspend fun startDownloading(id: Long) = error("unused")

        override suspend fun updateProgress(id: Long, bytesDownloaded: Long) = error("unused")

        override suspend fun updateStream(id: Long, track: ResolvedTrack) = error("unused")

        override suspend fun markCompleted(
            id: Long,
            fileName: String,
            mediaStoreId: Long,
            completedAt: Long,
        ) = error("unused")

        override suspend fun markFailed(
            id: Long,
            failureKind: FailureKind,
            message: String,
        ) = error("unused")

        override suspend fun markPaused(id: Long) = error("unused")

        override suspend fun markCancelled(id: Long) = error("unused")

        override suspend fun resetForRetry(id: Long) = error("unused")

        override suspend fun attachFile(
            id: Long,
            fileName: String,
            mediaStoreId: Long,
            bytesDownloaded: Long,
        ) = error("unused")

        override suspend fun pause(id: Long) = error("unused")

        override suspend fun resume(id: Long) = error("unused")

        override suspend fun retry(id: Long) = error("unused")

        override suspend fun deleteRecord(id: Long) {
            completed.value = completed.value.filterNot { it.id == id }
        }

        override suspend fun deleteRecords(ids: List<Long>) {
            val removed = ids.toSet()
            completed.value = completed.value.filterNot { it.id in removed }
        }

        override suspend fun clearCompleted() {
            completed.value = emptyList()
        }

        override suspend fun pauseAll() = error("unused")

        override suspend fun resumeAll() = error("unused")

        override suspend fun pendingIds(): List<Long> = error("unused")

        override suspend fun cancelAll() = error("unused")

        override suspend fun retryAllFailed() = error("unused")

        override suspend fun allocateFileName(id: Long, base: BaseFileName): String =
            error("unused")

        override suspend fun releaseFileName(id: Long) = error("unused")
    }

    private class FakeDownloadController : DownloadController {

        override val snapshot: StateFlow<EngineSnapshot> = MutableStateFlow(
            EngineSnapshot(
                activeCount = 0,
                completedInSession = 0,
                totalInSession = 0,
                bytesDone = 0,
                bytesTotal = null,
                speedBytesPerSecond = 0,
                isPaused = false,
            ),
        )

        var startCount: Int = 0
        val requestedIds = mutableListOf<Long>()

        override suspend fun attach(scope: kotlinx.coroutines.CoroutineScope) = Unit

        override fun detach() = Unit

        override fun start(context: Context) {
            startCount += 1
        }

        override fun requestDownload(ids: List<Long>) {
            requestedIds += ids
        }

        override fun pause(id: Long) = Unit

        override fun resume(id: Long) = Unit

        override fun cancel(id: Long) = Unit

        override fun retry(id: Long) = Unit

        override fun pauseAll() = Unit

        override fun resumeAll() = Unit

        override fun cancelAll() = Unit

        override fun retryAllFailed() = Unit
    }
}
