package com.dawnmoon.music_download.download

import com.dawnmoon.music_download.data.remote.ApiResponse
import com.dawnmoon.music_download.domain.model.FailureKind
import com.dawnmoon.music_download.domain.model.SongId
import com.dawnmoon.music_download.domain.model.TaskStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ParseWorker] 的队列消费与取消收尾契约。
 *
 * 直接驱动 `run(queue)`，覆盖引擎测试难以精确控制的取消、缺少 Key 与缺失 songId 分支。
 */
class ParseWorkerTest {

    @Test
    fun `cancelling the active parse marks the task cancelled`() = runTest {
        val repo = EngineFakeRepository()
        val resolver = EngineFakeResolver()
        val gate = CompletableDeferred<Unit>()
        resolver.behavior = { _, _, _ ->
            gate.await()
            ApiResponse.Success(resolvedTrack(SongId(1)), null)
        }
        repo.add(queuedTask(1))

        val worker = ParseWorker(repo, resolver, EngineFakeSettings(1), EngineFakeNotifier())
        val queue = Channel<Long>(Channel.UNLIMITED)
        queue.send(1)
        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val runJob = scope.launch { worker.run(queue) }

        runCurrent()
        assertEquals("the worker must expose the task it is parsing", 1L, worker.currentTaskId)

        assertTrue(worker.cancelActive(CancellationSignal.Cancel))
        assertEquals("a cancelled parse must be persisted as cancelled", TaskStatus.Cancelled, repo.byId(1)?.status)
        runCurrent()
        assertNull(worker.currentTaskId)

        runJob.cancel()
        queue.close()
        runCurrent()
    }

    @Test
    fun `a missing api key stops the queue with an invalid key failure`() = runTest {
        val repo = EngineFakeRepository()
        val settings = EngineFakeSettings(1).apply { key.value = null }
        val worker = ParseWorker(repo, EngineFakeResolver(), settings, EngineFakeNotifier())
        var pauseSignals = 0
        worker.onKeyLevelFailure = { pauseSignals += 1 }

        repo.add(queuedTask(1))
        val queue = Channel<Long>(Channel.UNLIMITED)
        queue.send(1)
        queue.close()

        worker.run(queue)

        val task = repo.byId(1)
        assertEquals(TaskStatus.Failed, task?.status)
        assertEquals(FailureKind.InvalidKey, task?.failureKind)
        assertEquals("a missing key must pause the whole parse queue", 1, pauseSignals)
    }

    @Test
    fun `a queued task without a song id fails as malformed`() = runTest {
        val repo = EngineFakeRepository()
        val resolver = EngineFakeResolver()
        val worker = ParseWorker(repo, resolver, EngineFakeSettings(1), EngineFakeNotifier())

        repo.add(queuedTask(1).copy(songId = null))
        val queue = Channel<Long>(Channel.UNLIMITED)
        queue.send(1)
        queue.close()

        worker.run(queue)

        val task = repo.byId(1)
        assertEquals(TaskStatus.Failed, task?.status)
        assertEquals(FailureKind.Malformed, task?.failureKind)
        assertTrue("a task without a song id must not reach the resolver", resolver.requestedSongIds.isEmpty())
        assertFalse(worker.isPaused())
    }
}
