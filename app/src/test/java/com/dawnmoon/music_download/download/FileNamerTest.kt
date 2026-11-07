package com.dawnmoon.music_download.download

import com.dawnmoon.music_download.data.repository.EnqueueOutcome
import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.model.BaseFileName
import com.dawnmoon.music_download.domain.model.DownloadTask
import com.dawnmoon.music_download.domain.model.FailureKind
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.ResolvedTrack
import com.dawnmoon.music_download.domain.model.SongLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

private const val TASK_ID = 7L

/**
 * [FileNamer] 的契约测试。
 *
 * 序号分配由 [TaskRepository.allocateFileName] 在仓库互斥区内完成，本类只负责转发。
 * 用最小替身固定三种返回，验证名称原样透传、不会二次追加序号，且仓库异常不会被吞掉。
 */
class FileNamerTest {

    @Test
    fun `returns the allocated name as is when it has no conflict`() = runTest {
        val repo = NamingTaskRepository(allocatedName = "a.mp3")

        val name = FileNamer(repo).uniqueName(TASK_ID, BaseFileName("a.mp3"))

        assertEquals("a.mp3", name)
        assertEquals(
            "the base name must be forwarded to the repository unchanged",
            listOf(BaseFileName("a.mp3")),
            repo.allocatedBases,
        )
    }

    @Test
    fun `does not append a second sequence number to an already allocated name`() = runTest {
        val repo = NamingTaskRepository(allocatedName = "a (1).mp3")

        val name = FileNamer(repo).uniqueName(TASK_ID, BaseFileName("a.mp3"))

        assertEquals("a (1).mp3", name)
    }

    @Test
    fun `propagates the repository error`() = runTest {
        val repo = NamingTaskRepository(
            allocatedName = "unused",
            failure = IllegalStateException("文件名分配失败"),
        )

        var thrown: IllegalStateException? = null
        try {
            FileNamer(repo).uniqueName(TASK_ID, BaseFileName("a.mp3"))
        } catch (error: IllegalStateException) {
            thrown = error
        }

        assertNotNull("the allocation error must not be swallowed", thrown)
        assertEquals("文件名分配失败", thrown?.message)
    }
}

/** 只实现命名分配的最小仓库；其余入口一旦被调用即说明测试偏离了目标行为。 */
private class NamingTaskRepository(
    private val allocatedName: String,
    private val failure: IllegalStateException? = null,
) : TaskRepository {

    val allocatedBases = mutableListOf<BaseFileName>()

    override suspend fun allocateFileName(id: Long, base: BaseFileName): String {
        failure?.let { throw it }
        allocatedBases += base
        return allocatedName
    }

    override suspend fun releaseFileName(id: Long) = Unit

    override fun observeInProgress(): Flow<List<DownloadTask>> = unused()

    override fun observeCompleted(): Flow<List<DownloadTask>> = unused()

    override suspend fun byId(id: Long): DownloadTask? = unused()

    override suspend fun enqueue(text: String, quality: MusicQuality): EnqueueOutcome = unused()

    override suspend fun enqueueLinks(
        links: List<SongLink>,
        quality: MusicQuality,
    ): EnqueueOutcome = unused()

    override suspend fun markResolving(id: Long) = unused()

    override suspend fun completeResolve(id: Long, track: ResolvedTrack) = unused()

    override suspend fun startDownloading(id: Long) = unused()

    override suspend fun updateProgress(id: Long, bytesDownloaded: Long) = unused()

    override suspend fun updateStream(id: Long, track: ResolvedTrack) = unused()

    override suspend fun markCompleted(
        id: Long,
        fileName: String,
        mediaStoreId: Long,
        completedAt: Long,
    ) = unused()

    override suspend fun markFailed(id: Long, failureKind: FailureKind, message: String) = unused()

    override suspend fun markPaused(id: Long) = unused()

    override suspend fun markCancelled(id: Long) = unused()

    override suspend fun resetForRetry(id: Long) = unused()

    override suspend fun attachFile(
        id: Long,
        fileName: String,
        mediaStoreId: Long,
        bytesDownloaded: Long,
    ) = unused()

    override suspend fun pause(id: Long) = unused()

    override suspend fun resume(id: Long) = unused()

    override suspend fun retry(id: Long) = unused()

    override suspend fun deleteRecord(id: Long) = unused()

    override suspend fun deleteRecords(ids: List<Long>) = unused()

    override suspend fun clearCompleted() = unused()

    override suspend fun pauseAll() = unused()

    override suspend fun resumeAll() = unused()

    override suspend fun pendingIds(): List<Long> = unused()

    override suspend fun cancelAll() = unused()

    override suspend fun retryAllFailed() = unused()

    private fun unused(): Nothing = error("not used by FileNamer")
}
