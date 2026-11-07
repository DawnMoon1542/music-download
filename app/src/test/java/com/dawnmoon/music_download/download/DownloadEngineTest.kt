package com.dawnmoon.music_download.download

import com.dawnmoon.music_download.core.AppClock
import com.dawnmoon.music_download.data.remote.ApiFailure
import com.dawnmoon.music_download.data.remote.ApiResponse
import com.dawnmoon.music_download.data.repository.AppSettings
import com.dawnmoon.music_download.data.repository.EnqueueOutcome
import com.dawnmoon.music_download.data.repository.TaskRepository
import com.dawnmoon.music_download.domain.TrackResolver
import com.dawnmoon.music_download.domain.model.AudioFormat
import com.dawnmoon.music_download.domain.model.BaseFileName
import com.dawnmoon.music_download.domain.model.DownloadTask
import com.dawnmoon.music_download.domain.model.FailureKind
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.ResolvedTrack
import com.dawnmoon.music_download.domain.model.SongId
import com.dawnmoon.music_download.domain.model.SongLink
import com.dawnmoon.music_download.domain.model.TaskStatus
import com.dawnmoon.music_download.domain.model.ThemeMode
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DownloadEngine] 的队列、下载池与批量操作契约测试。
 *
 * 期望的生产接口：
 * - `DownloadEngine(repo, resolver, settings, fileStore, downloader, notifications, wakeLock, clock, serviceStopper, serviceStarter)`
 *   实现 `DownloadController`。
 * - `suspend fun attach(scope: CoroutineScope)` 启动解析 worker 与下载调度；
 *   `fun detach()` 取消作业并释放 WakeLock。
 * - `ServiceStopper` 是注入的空闲回调，`fun stop()`。
 *
 * 所有断言针对任务状态、文件删除、快照字段和通知/WakeLock 的可见结果，而不是内部调用顺序。
 */
class DownloadEngineTest {

    @Test
    fun `requesting downloads parses tasks serially and drives them to completion`() = engineTest { harness ->
        harness.repo.add(queuedTask(1))
        harness.repo.add(queuedTask(2))
        harness.resolver.behavior = { songId, _, _ ->
            delay(50)
            ApiResponse.Success(resolvedTrack(songId), null)
        }

        harness.engine.requestDownload(listOf(1, 2))
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals("parsing must not run concurrently", 1, harness.resolver.maxActive)
        assertEquals(TaskStatus.Completed, harness.repo.byId(1)?.status)
        assertEquals(TaskStatus.Completed, harness.repo.byId(2)?.status)
        assertEquals(
            "each task must pass through the queue and download states in order",
            listOf(
                TaskStatus.Queued,
                TaskStatus.Resolving,
                TaskStatus.Waiting,
                TaskStatus.Downloading,
                TaskStatus.Completed,
            ),
            harness.repo.statusHistory(1),
        )
    }

    @Test
    fun `with one permit the next download waits for the first to finish`() = engineTest { harness ->
        val gates = DownloadGates()
        harness.downloader.behavior = { id ->
            gates.waitFor(id)
            harness.downloader.complete(id)
        }
        harness.repo.add(waitingTask(1))
        harness.repo.add(waitingTask(2))

        harness.engine.requestDownload(listOf(1, 2))
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals("only the first task may start", listOf(1L), harness.downloader.started)
        assertEquals(TaskStatus.Waiting, harness.repo.byId(2)?.status)

        gates.release(1)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals("the second task must start after the first completes", listOf(1L, 2L), harness.downloader.started)
        assertEquals(1, harness.downloader.maxActive)
    }

    @Test
    fun `with two permits tasks download at the same time`() = engineTest(concurrency = 2) { harness ->
        val gates = DownloadGates()
        harness.downloader.behavior = { id ->
            gates.waitFor(id)
            harness.downloader.complete(id)
        }
        harness.repo.add(waitingTask(1))
        harness.repo.add(waitingTask(2))

        harness.engine.requestDownload(listOf(1, 2))
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals("both tasks must start while two permits are available", setOf(1L, 2L), harness.downloader.started.toSet())
        assertEquals("the pool must not exceed the configured permits", 2, harness.downloader.maxActive)

        gates.releaseAll()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(TaskStatus.Completed, harness.repo.byId(1)?.status)
        assertEquals(TaskStatus.Completed, harness.repo.byId(2)?.status)
    }

    @Test
    fun `an invalid key pauses parsing until the user resumes`() =
        parsingFailurePausesQueue(ApiFailure.InvalidKey("bad key"), FailureKind.InvalidKey)

    @Test
    fun `exhausted quota pauses parsing until the user resumes`() =
        parsingFailurePausesQueue(ApiFailure.QuotaExhausted("no quota"), FailureKind.QuotaExhausted)

    @Test
    fun `a forbidden resolve pauses parsing until the user resumes`() =
        parsingFailurePausesQueue(ApiFailure.Forbidden("forbidden"), FailureKind.Forbidden)

    @Test
    fun `a transient parse failure fails only that task and keeps parsing the queue`() = engineTest { harness ->
        harness.repo.add(queuedTask(1))
        harness.repo.add(queuedTask(2))
        harness.resolver.behavior = { songId, _, _ ->
            if (songId.value == 1L) {
                ApiResponse.Failure(ApiFailure.ServiceUnavailable("temporarily unavailable"), null)
            } else {
                ApiResponse.Success(resolvedTrack(songId), null)
            }
        }

        harness.engine.requestDownload(listOf(1, 2))
        advanceTimeBy(5_000)
        runCurrent()

        val failed = harness.repo.byId(1)
        assertEquals(TaskStatus.Failed, failed?.status)
        assertEquals(FailureKind.ServiceUnavailable, failed?.failureKind)
        assertEquals(
            "a non-key failure must not pause the parse queue",
            TaskStatus.Completed,
            harness.repo.byId(2)?.status,
        )
        assertEquals("both queued tasks must be parsed", listOf(1L, 2L), harness.resolver.requestedSongIds)
        assertFalse("a transient parse failure must not pause the pool", harness.engine.snapshot.value.isPaused)
    }

    @Test
    fun `pausing a running task cancels its job and resuming queues it again`() = engineTest { harness ->
        val gate = CompletableDeferred<Unit>()
        var executions = 0
        harness.downloader.behavior = { id ->
            executions += 1
            if (executions == 1) gate.await()
            harness.downloader.complete(id)
        }
        harness.repo.add(waitingTask(1))

        harness.engine.requestDownload(listOf(1))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(TaskStatus.Downloading, harness.repo.byId(1)?.status)

        harness.engine.pause(1)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals("a paused task must leave the downloading state", TaskStatus.Paused, harness.repo.byId(1)?.status)
        assertEquals(
            "the engine must cancel the job with a pause signal",
            1,
            harness.downloader.cancelledSignals.count { it == CancellationSignal.Pause },
        )

        harness.engine.resume(1)
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals("resuming must download the task again", TaskStatus.Completed, harness.repo.byId(1)?.status)
        assertEquals("resuming must start a new execution", 2, executions)
    }

    @Test
    fun `cancelling a running download lets the worker remove its pending file once`() = engineTest { harness ->
        val gate = CompletableDeferred<Unit>()
        harness.downloader.behavior = { id ->
            gate.await()
            harness.downloader.complete(id)
        }
        harness.repo.add(waitingTask(1, mediaStoreId = 501))

        harness.engine.requestDownload(listOf(1))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(TaskStatus.Downloading, harness.repo.byId(1)?.status)

        harness.engine.cancel(1)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(TaskStatus.Cancelled, harness.repo.byId(1)?.status)
        assertEquals(
            "the engine must cancel the job with a cancel signal",
            1,
            harness.downloader.cancelledSignals.count { it == CancellationSignal.Cancel },
        )
        assertEquals(
            "the worker owns deletion of a running download's pending file; the engine must not delete it again",
            1,
            harness.fileStore.deletedIds.count { it == 501L },
        )
    }

    @Test
    fun `cancelling a waiting task without a running job removes its pending file`() = engineTest { harness ->
        harness.repo.add(waitingTask(1, mediaStoreId = 701))

        harness.engine.cancel(1)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(TaskStatus.Cancelled, harness.repo.byId(1)?.status)
        assertEquals(
            "a task without a job must still have its pending file removed",
            1,
            harness.fileStore.deletedIds.count { it == 701L },
        )
        assertTrue("no download job may run for this task", harness.downloader.started.isEmpty())
        assertTrue("no cancellation signal can reach a job that does not exist", harness.downloader.cancelledSignals.isEmpty())
    }

    @Test
    fun `retrying a failed task removes the pending file and queues it again`() = engineTest { harness ->
        harness.repo.add(failedTask(1, mediaStoreId = 601))

        harness.engine.retry(1)
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue("the stale pending file must be removed before retrying", 601L in harness.fileStore.deletedIds)
        assertEquals("retry must re-resolve and download the task", TaskStatus.Completed, harness.repo.byId(1)?.status)
    }

    @Test
    fun `pausing all pauses a task that is genuinely resolving`() = engineTest { harness ->
        val resolveGate = CompletableDeferred<Unit>()
        harness.resolver.behavior = { songId, _, _ ->
            if (songId.value == 1L) resolveGate.await()
            ApiResponse.Success(resolvedTrack(songId), null)
        }
        harness.repo.add(queuedTask(1))

        harness.engine.requestDownload(listOf(1))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("the task must be held inside the resolver", TaskStatus.Resolving, harness.repo.byId(1)?.status)
        assertEquals(
            "a task in the resolving phase must count as active",
            1,
            harness.engine.snapshot.value.activeCount,
        )

        harness.engine.pauseAll()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("pauseAll must pause a task that is actively resolving", TaskStatus.Paused, harness.repo.byId(1)?.status)

        resolveGate.complete(Unit)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(
            "a resolve that completes after pauseAll must not revive the task",
            TaskStatus.Paused,
            harness.repo.byId(1)?.status,
        )
        assertTrue("a paused task must not start downloading", harness.downloader.started.isEmpty())
    }

    @Test
    fun `pausing all cancels in-flight downloads and prevents new ones from starting`() = engineTest(concurrency = 2) { harness ->
        val gates = DownloadGates()
        harness.downloader.behavior = { id ->
            gates.waitFor(id)
            harness.downloader.complete(id)
        }
        harness.repo.add(queuedTask(4))
        harness.repo.add(waitingTask(1))
        harness.repo.add(waitingTask(2))
        harness.repo.add(waitingTask(3))

        harness.engine.requestDownload(listOf(1, 2, 3))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("two tasks occupy the permits", setOf(1L, 2L), harness.downloader.started.toSet())
        assertEquals(TaskStatus.Waiting, harness.repo.byId(3)?.status)

        harness.engine.pauseAll()
        advanceTimeBy(1_000)
        runCurrent()

        (1L..4L).forEach { id ->
            assertEquals("task $id must be paused", TaskStatus.Paused, harness.repo.byId(id)?.status)
        }
        assertEquals(
            "every in-flight download must be cancelled with a pause signal",
            2,
            harness.downloader.cancelledSignals.count { it == CancellationSignal.Pause },
        )
        assertTrue("the pool must report itself paused", harness.engine.snapshot.value.isPaused)

        // 释放被取消任务曾经等待的闸门与许可：等待中的任务不得因此启动。
        gates.releaseAll()
        advanceTimeBy(2_000)
        runCurrent()

        assertFalse("a paused pool must not admit the task that was waiting for a permit", 3L in harness.downloader.started)
        (1L..3L).forEach { id ->
            assertEquals("task $id must stay paused", TaskStatus.Paused, harness.repo.byId(id)?.status)
        }
    }

    @Test
    fun `resuming all requeues paused tasks`() = engineTest { harness ->
        harness.repo.add(pausedTask(1, resolved = false))
        harness.repo.add(pausedTask(2, resolved = true))

        harness.engine.resumeAll()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals("an unresolved paused task must be parsed and downloaded", TaskStatus.Completed, harness.repo.byId(1)?.status)
        assertEquals("a resolved paused task must resume downloading", TaskStatus.Completed, harness.repo.byId(2)?.status)
    }

    @Test
    fun `resuming all counts recovered tasks in the download session`() = engineTest(concurrency = 2) { harness ->
        val gates = DownloadGates()
        harness.downloader.behavior = { id ->
            val size = harness.repo.byId(id)?.sizeBytes ?: 0L
            harness.repo.updateProgress(id, size / 2)
            // 先取得一个进度样本，再推进注入时钟并补一次进度，速度窗口才有非零差值。
            delay(750)
            harness.clock.advance(1_000L)
            harness.repo.updateProgress(id, size / 2 + 100L)
            gates.waitFor(id)
            harness.downloader.complete(id)
        }
        // 直接放入已解析的暂停任务，会话集合初始为空，只能由 resumeAll 的补投路径建立。
        harness.repo.add(pausedTask(1, resolved = true))
        harness.repo.add(pausedTask(2, resolved = true))

        harness.engine.resumeAll()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(TaskStatus.Downloading, harness.repo.byId(1)?.status)
        assertEquals(TaskStatus.Downloading, harness.repo.byId(2)?.status)
        val during = harness.engine.snapshot.value
        assertEquals(
            "a task recovered by resumeAll must join the session",
            2,
            during.totalInSession,
        )
        assertEquals(
            "the session total must include every recovered task's size",
            2_003L,
            during.bytesTotal,
        )
        assertTrue("recovered tasks must contribute progress", during.bytesDone > 0L)
        assertTrue(
            "recovered downloads must report a sampled speed",
            during.speedBytesPerSecond > 0L,
        )
        assertTrue(
            "the notification must receive the recovered session totals",
            harness.notifier.updates.any { it.totalInSession == 2 && it.bytesTotal == 2_003L },
        )

        gates.release(1)
        advanceTimeBy(1_500)
        runCurrent()

        val oneFinished = harness.engine.snapshot.value
        assertEquals(
            "a finished recovered task must count as completed in the session",
            1,
            oneFinished.completedInSession,
        )
        assertEquals(
            "the remaining recovered task must keep the session size at two",
            2,
            oneFinished.totalInSession,
        )
        assertEquals(2_003L, oneFinished.bytesTotal)

        gates.releaseAll()
        advanceTimeBy(5_000)
        runCurrent()
    }

    @Test
    fun `a task redispatched after resuming a key failure joins the session`() = engineTest { harness ->
        val gate = CompletableDeferred<Unit>()
        harness.repo.add(queuedTask(1))
        harness.repo.add(queuedTask(2))
        harness.resolver.behavior = { _, _, _ -> ApiResponse.Failure(ApiFailure.InvalidKey("bad key"), null) }

        harness.engine.requestDownload(listOf(1))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(TaskStatus.Failed, harness.repo.byId(1)?.status)
        assertEquals(TaskStatus.Queued, harness.repo.byId(2)?.status)
        assertEquals(
            "a key-level failure must stop parsing before the queued task is dispatched",
            listOf(1L),
            harness.resolver.requestedSongIds,
        )

        harness.resolver.behavior = { songId, _, _ -> ApiResponse.Success(resolvedTrack(songId), null) }
        harness.downloader.behavior = { id ->
            if (id == 2L) {
                harness.repo.updateProgress(id, 500L)
                gate.await()
            }
            harness.downloader.complete(id)
        }
        // 解析暂停期间引擎可能已经空闲并清空会话，因此只断言本次恢复带来的增量。
        val sessionBeforeResume = harness.engine.snapshot.value.totalInSession
        harness.engine.resume(1)
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(
            "resuming must redispatch the task stranded by the parse pause",
            TaskStatus.Downloading,
            harness.repo.byId(2)?.status,
        )
        assertEquals(
            "a redispatched task must join the session on top of whatever remains",
            sessionBeforeResume + 1,
            harness.engine.snapshot.value.totalInSession,
        )

        gate.complete(Unit)
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(TaskStatus.Completed, harness.repo.byId(2)?.status)
        assertEquals(
            "the session counters must still reset once nothing remains",
            0,
            harness.engine.snapshot.value.totalInSession,
        )
    }

    @Test
    fun `cancelling all cancels jobs and removes every pending file once`() = engineTest(concurrency = 2) { harness ->
        val gates = DownloadGates()
        harness.downloader.behavior = { id ->
            gates.waitFor(id)
            harness.downloader.complete(id)
        }
        harness.repo.add(waitingTask(1, mediaStoreId = 101))
        harness.repo.add(waitingTask(2, mediaStoreId = 102))
        // 已带 pending 文件但从未启动过的任务：没有 Job，必须由引擎删除。
        harness.repo.add(waitingTask(3, mediaStoreId = 103))
        harness.repo.add(completedTask(4, mediaStoreId = 104))

        harness.engine.requestDownload(listOf(1, 2))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("both requested tasks must be downloading", setOf(1L, 2L), harness.downloader.started.toSet())

        harness.engine.cancelAll()
        advanceTimeBy(1_000)
        runCurrent()

        (1L..3L).forEach { id ->
            assertEquals("task $id must be cancelled", TaskStatus.Cancelled, harness.repo.byId(id)?.status)
        }
        assertEquals(
            "every running job must be cancelled with a cancel signal",
            2,
            harness.downloader.cancelledSignals.count { it == CancellationSignal.Cancel },
        )
        assertEquals(
            "a running task's worker deletes its file exactly once",
            1,
            harness.fileStore.deletedIds.count { it == 101L },
        )
        assertEquals(
            "a running task's worker deletes its file exactly once",
            1,
            harness.fileStore.deletedIds.count { it == 102L },
        )
        assertEquals(
            "a waiting task without a job must have its file deleted by the engine",
            1,
            harness.fileStore.deletedIds.count { it == 103L },
        )
        assertFalse("completed files must be left alone", 104L in harness.fileStore.deletedIds)
        assertEquals(TaskStatus.Completed, harness.repo.byId(4)?.status)

        // 被取消的任务即使闸门打开也不得完成。
        gates.releaseAll()
        advanceTimeBy(2_000)
        runCurrent()
        (1L..3L).forEach { id ->
            assertEquals("task $id must remain cancelled", TaskStatus.Cancelled, harness.repo.byId(id)?.status)
        }
    }

    @Test
    fun `retrying all failed requeues failures and removes their files`() = engineTest { harness ->
        harness.repo.add(failedTask(1, mediaStoreId = 201))
        harness.repo.add(failedTask(2, mediaStoreId = 202))
        harness.repo.add(completedTask(3, mediaStoreId = 203))

        harness.engine.retryAllFailed()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(TaskStatus.Completed, harness.repo.byId(1)?.status)
        assertEquals(TaskStatus.Completed, harness.repo.byId(2)?.status)
        assertTrue("stale pending files must be deleted", 201L in harness.fileStore.deletedIds)
        assertTrue("stale pending files must be deleted", 202L in harness.fileStore.deletedIds)
        assertFalse("completed files must be left alone", 203L in harness.fileStore.deletedIds)
    }

    @Test
    fun `a failed task releases its concurrency permit for the next task`() = engineTest { harness ->
        harness.downloader.behavior = { id ->
            when (id) {
                1L, 2L -> harness.downloader.fail(id, FailureKind.Network)
                else -> harness.downloader.complete(id)
            }
        }
        harness.repo.add(waitingTask(1))
        harness.repo.add(waitingTask(2))
        harness.repo.add(waitingTask(3))

        harness.engine.requestDownload(listOf(1, 2, 3))
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(TaskStatus.Failed, harness.repo.byId(1)?.status)
        assertEquals(TaskStatus.Failed, harness.repo.byId(2)?.status)
        assertTrue("two failures must not starve the single permit", 3L in harness.downloader.started)
        assertEquals(TaskStatus.Completed, harness.repo.byId(3)?.status)
    }

    @Test
    fun `a storage failure pauses the pool until everything is resumed`() = engineTest { harness ->
        harness.downloader.behavior = { id ->
            if (id == 1L) {
                harness.downloader.fail(id, FailureKind.Storage)
            } else {
                harness.downloader.complete(id)
            }
        }
        harness.repo.add(waitingTask(1))
        harness.repo.add(waitingTask(2))

        harness.engine.requestDownload(listOf(1, 2))
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(TaskStatus.Failed, harness.repo.byId(1)?.status)
        assertEquals(TaskStatus.Waiting, harness.repo.byId(2)?.status)
        assertFalse("a storage failure must prevent new downloads", 2L in harness.downloader.started)

        harness.engine.resumeAll()
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue("resuming must clear the pool pause", 2L in harness.downloader.started)
        assertEquals(TaskStatus.Completed, harness.repo.byId(2)?.status)
        assertFalse(harness.engine.snapshot.value.isPaused)
    }

    @Test
    fun `the snapshot reports progress and speed while a download is running`() = engineTest { harness ->
        val gate = CompletableDeferred<Unit>()
        harness.downloader.behavior = { id ->
            harness.repo.updateProgress(id, 100L)
            // 让引擎先观察到第一个进度样本，再推进注入时钟，速度窗口才有可测的时间差。
            delay(750)
            harness.clock.advance(1_000L)
            harness.repo.updateProgress(id, 600L)
            gate.await()
            harness.downloader.complete(id)
        }
        harness.repo.add(waitingTask(1, sizeBytes = 1_000))

        harness.engine.requestDownload(listOf(1))
        advanceTimeBy(1_000)
        runCurrent()

        val during = harness.engine.snapshot.value
        assertEquals(1, during.activeCount)
        assertEquals(0, during.completedInSession)
        assertEquals(1, during.totalInSession)
        assertEquals(600L, during.bytesDone)
        assertEquals(1_000L, during.bytesTotal)
        assertTrue(
            "speed must reflect the progress made within the sampled clock window",
            during.speedBytesPerSecond > 0L,
        )
        assertFalse(during.isPaused)

        gate.complete(Unit)
        advanceTimeBy(5_000)
        runCurrent()
    }

    @Test
    fun `the snapshot counts a finished task alongside the one still downloading`() =
        engineTest(concurrency = 2) { harness ->
            val gates = DownloadGates()
            harness.downloader.behavior = { id ->
                gates.waitFor(id)
                harness.downloader.complete(id)
            }
            harness.repo.add(waitingTask(1, sizeBytes = 1_000))
            harness.repo.add(waitingTask(2, sizeBytes = 1_000))

            harness.engine.requestDownload(listOf(1, 2))
            advanceTimeBy(1_500)
            runCurrent()

            val running = harness.engine.snapshot.value
            assertEquals("both downloads must be active", 2, running.activeCount)
            assertEquals("no task has completed yet", 0, running.completedInSession)
            assertEquals("both requested tasks belong to the session", 2, running.totalInSession)

            gates.release(1)
            advanceTimeBy(1_500)
            runCurrent()

            assertEquals(TaskStatus.Completed, harness.repo.byId(1)?.status)
            assertEquals(TaskStatus.Downloading, harness.repo.byId(2)?.status)
            val during = harness.engine.snapshot.value
            assertEquals("only the remaining download is active", 1, during.activeCount)
            assertEquals("the finished task must count in the session", 1, during.completedInSession)
            assertEquals("both requested tasks must stay in the session", 2, during.totalInSession)

            gates.releaseAll()
            advanceTimeBy(5_000)
            runCurrent()
        }

    @Test
    fun `the snapshot resets to zero when the download session becomes idle`() = engineTest { harness ->
        harness.downloader.behavior = { id ->
            harness.repo.updateProgress(id, 400L)
            harness.downloader.complete(id)
        }
        harness.repo.add(waitingTask(1, sizeBytes = 1_000))

        harness.engine.requestDownload(listOf(1))
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(TaskStatus.Completed, harness.repo.byId(1)?.status)
        val after = harness.engine.snapshot.value
        assertEquals("idle downloads must not be counted as active", 0, after.activeCount)
        assertEquals("session counters must reset once nothing remains", 0, after.completedInSession)
        assertEquals("session counters must reset once nothing remains", 0, after.totalInSession)
        assertEquals("accumulated bytes must reset once nothing remains", 0L, after.bytesDone)
        assertEquals("accumulated total must reset once nothing remains", 0L, after.bytesTotal)
        assertEquals("speed must drop to zero when nothing is running", 0L, after.speedBytesPerSecond)
        assertFalse(after.isPaused)
    }

    @Test
    fun `progress notifications are published at most once per 500ms window`() = engineTest { harness ->
        val gate = CompletableDeferred<Unit>()
        harness.downloader.behavior = { id ->
            gate.await()
            harness.downloader.complete(id)
        }
        harness.repo.add(waitingTask(1, sizeBytes = 10_000))

        harness.engine.requestDownload(listOf(1))
        // 先让启动阶段的通知与节流窗口稳定，再只观察持续进度产生的通知。
        advanceTimeBy(1_500)
        runCurrent()
        val baseIndex = harness.notifier.updates.size

        // 以 10ms 为步进连续推进 2 秒：同一 500ms 窗口内的多次进度变化必须合并为至多一次通知。
        repeat(200) { index ->
            harness.repo.updateProgress(1, 100L + index)
            advanceTimeBy(10)
            runCurrent()
        }
        // 再推进一个完整窗口，确保最后一次进度被节流器发布。
        harness.repo.updateProgress(1, 999L)
        advanceTimeBy(600)
        runCurrent()

        val publishedTimes = harness.notifier.updateTimes.drop(baseIndex)
        assertTrue(
            "progress changes must still reach the notifier across 500ms windows",
            publishedTimes.size >= 2,
        )
        val gaps = publishedTimes.zipWithNext { first, second -> second - first }
        assertTrue(
            "a notification must not be published until 500ms have elapsed: $gaps",
            gaps.all { gap -> gap >= 500L },
        )
        assertTrue(
            "the throttle window must be 500ms rather than 600ms: $gaps",
            gaps.any { gap -> gap <= 510L },
        )
        assertEquals(
            "the latest notification must carry the latest progress",
            999L,
            harness.notifier.updates.last().bytesDone,
        )

        gate.complete(Unit)
        advanceTimeBy(5_000)
        runCurrent()
    }

    @Test
    fun `the wake lock is held while downloads are active and released when idle`() = engineTest { harness ->
        val gate = CompletableDeferred<Unit>()
        harness.downloader.behavior = { id ->
            gate.await()
            harness.downloader.complete(id)
        }
        harness.repo.add(waitingTask(1))

        harness.engine.requestDownload(listOf(1))
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue("an active download must hold the wake lock", harness.wakeLock.isHeld)

        gate.complete(Unit)
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse("the wake lock must be released when downloads become idle", harness.wakeLock.isHeld)
    }

    @Test
    fun `detaching releases the wake lock unconditionally`() = engineTest { harness ->
        val gate = CompletableDeferred<Unit>()
        harness.downloader.behavior = { id ->
            gate.await()
            harness.downloader.complete(id)
        }
        harness.repo.add(waitingTask(1))

        harness.engine.requestDownload(listOf(1))
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(harness.wakeLock.isHeld)

        harness.detachOnce()
        runCurrent()

        assertFalse("detach must release the wake lock", harness.wakeLock.isHeld)
        assertTrue("detach must release all references", harness.wakeLock.releaseAllCount >= 1)
    }

    @Test
    fun `the service stopper runs after two idle seconds`() = engineTest { harness ->
        runCurrent()

        advanceTimeBy(1_999)
        runCurrent()
        assertEquals("the service must stay alive before the idle timeout", 0, harness.stopper.stopCount)

        advanceTimeBy(2)
        runCurrent()
        assertEquals("the service must stop after two idle seconds", 1, harness.stopper.stopCount)
    }

    @Test
    fun `unknown task ids are ignored`() = engineTest { harness ->
        harness.engine.requestDownload(listOf(999))
        harness.engine.pause(999)
        harness.engine.resume(999)
        harness.engine.cancel(999)
        harness.engine.retry(999)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(0, harness.engine.snapshot.value.activeCount)
        assertTrue(harness.downloader.started.isEmpty())
        assertTrue(harness.repo.statusHistory(999).isEmpty())
    }

    @Test
    fun `operations issued before attach are replayed once the engine attaches`() = runTest {
        val harness = EngineHarness()
        harness.notifier.timeSource = { testScheduler.currentTime }
        harness.repo.add(queuedTask(1))

        harness.engine.requestDownload(listOf(1))
        runCurrent()

        assertEquals(
            "a cold-start request must be buffered instead of silently dropped",
            TaskStatus.Queued,
            harness.repo.byId(1)?.status,
        )
        assertTrue(harness.downloader.started.isEmpty())

        val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        harness.engine.attach(scope)
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(TaskStatus.Completed, harness.repo.byId(1)?.status)
        harness.detachOnce()
        runCurrent()
        scope.cancel()
    }

    @Test
    fun `a download request after the service stopped starts it again and replays once attached`() = runTest {
        val harness = EngineHarness()
        harness.notifier.timeSource = { testScheduler.currentTime }
        harness.repo.add(queuedTask(1))

        // 模拟前台服务空闲自停：先 attach，再 detach 清空引擎作用域。
        val initialScope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        harness.engine.attach(initialScope)
        runCurrent()
        harness.engine.detach()
        runCurrent()
        initialScope.cancel()

        harness.engine.requestDownload(listOf(1))
        runCurrent()

        assertEquals(
            "requesting downloads on a detached engine must start the foreground service",
            1,
            harness.serviceStarter.startCount,
        )
        assertEquals(
            "the request must stay buffered until the service attaches again",
            TaskStatus.Queued,
            harness.repo.byId(1)?.status,
        )

        val replayScope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        harness.engine.attach(replayScope)
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(
            "attaching again must replay the buffered request and drive the task to completion",
            TaskStatus.Completed,
            harness.repo.byId(1)?.status,
        )

        harness.engine.detach()
        runCurrent()
        replayScope.cancel()
    }

    @Test
    fun `resuming all after the service stopped starts it again`() = engineTest { harness ->
        harness.repo.add(pausedTask(1, resolved = true))

        harness.detachOnce()
        runCurrent()

        harness.engine.resumeAll()
        runCurrent()

        assertEquals(
            "resumeAll on a detached engine must start the foreground service",
            1,
            harness.serviceStarter.startCount,
        )
        assertEquals(
            "the buffered resumeAll must leave the task paused until the service attaches",
            TaskStatus.Paused,
            harness.repo.byId(1)?.status,
        )
    }

    @Test
    fun `retrying after the service stopped starts it again`() = engineTest { harness ->
        harness.repo.add(failedTask(1))

        harness.detachOnce()
        runCurrent()

        harness.engine.retry(1)
        runCurrent()

        assertEquals(
            "retry on a detached engine must start the foreground service",
            1,
            harness.serviceStarter.startCount,
        )
        assertEquals(
            "the buffered retry must leave the failed task for the service to replay",
            TaskStatus.Failed,
            harness.repo.byId(1)?.status,
        )
    }

    @Test
    fun `repeated control operations while detached start the service only once`() = engineTest { harness ->
        harness.repo.add(queuedTask(1))

        harness.detachOnce()
        runCurrent()

        harness.engine.requestDownload(listOf(1))
        runCurrent()
        harness.engine.resumeAll()
        runCurrent()

        assertEquals(
            "a service that is already starting must not be started again",
            1,
            harness.serviceStarter.startCount,
        )
    }

    @Test
    fun `retrying a key-level failure resumes parsing for tasks dropped by the pause`() = engineTest { harness ->
        harness.repo.add(queuedTask(1))
        harness.repo.add(queuedTask(2))
        harness.resolver.behavior = { _, _, _ -> ApiResponse.Failure(ApiFailure.InvalidKey("bad key"), null) }

        harness.engine.requestDownload(listOf(1, 2))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(TaskStatus.Failed, harness.repo.byId(1)?.status)
        assertEquals("the second task must be dropped while parsing is paused", TaskStatus.Queued, harness.repo.byId(2)?.status)

        harness.resolver.behavior = { songId, _, _ -> ApiResponse.Success(resolvedTrack(songId), null) }
        harness.engine.retry(1)
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(TaskStatus.Completed, harness.repo.byId(1)?.status)
        assertEquals(
            "retry must clear the parse pause and redispatch the dropped queued task",
            TaskStatus.Completed,
            harness.repo.byId(2)?.status,
        )
    }

    @Test
    fun `retrying all failures clears a key-level parse pause`() = engineTest { harness ->
        harness.repo.add(queuedTask(1))
        harness.repo.add(queuedTask(2))
        harness.resolver.behavior = { _, _, _ -> ApiResponse.Failure(ApiFailure.QuotaExhausted("no quota"), null) }

        harness.engine.requestDownload(listOf(1, 2))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(TaskStatus.Failed, harness.repo.byId(1)?.status)
        assertEquals(TaskStatus.Queued, harness.repo.byId(2)?.status)

        harness.resolver.behavior = { songId, _, _ -> ApiResponse.Success(resolvedTrack(songId), null) }
        harness.engine.retryAllFailed()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(TaskStatus.Completed, harness.repo.byId(1)?.status)
        assertEquals(
            "retryAllFailed must clear the parse pause and redispatch queued tasks",
            TaskStatus.Completed,
            harness.repo.byId(2)?.status,
        )
    }

    @Test
    fun `retrying all failures clears a storage pool pause and requeues waiting tasks`() = engineTest { harness ->
        harness.downloader.behavior = { id ->
            if (id == 1L) {
                harness.downloader.fail(id, FailureKind.Storage)
            } else {
                harness.downloader.complete(id)
            }
        }
        harness.repo.add(waitingTask(1))
        harness.repo.add(waitingTask(2))

        harness.engine.requestDownload(listOf(1, 2))
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(TaskStatus.Failed, harness.repo.byId(1)?.status)
        assertEquals(TaskStatus.Waiting, harness.repo.byId(2)?.status)
        assertFalse("a storage failure must hold the pool", 2L in harness.downloader.started)

        harness.downloader.behavior = { id -> harness.downloader.complete(id) }
        harness.engine.retryAllFailed()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(TaskStatus.Completed, harness.repo.byId(1)?.status)
        assertEquals(
            "retryAllFailed must clear the pool pause and redispatch a dropped waiting task",
            TaskStatus.Completed,
            harness.repo.byId(2)?.status,
        )
    }

    @Test
    fun `requesting a download after a key failure redispatches other queued tasks`() = engineTest { harness ->
        val gates = DownloadGates()
        harness.repo.add(queuedTask(1))
        harness.repo.add(queuedTask(2))
        harness.resolver.behavior = { _, _, _ -> ApiResponse.Failure(ApiFailure.InvalidKey("bad key"), null) }

        harness.engine.requestDownload(listOf(1))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(TaskStatus.Failed, harness.repo.byId(1)?.status)
        assertEquals(TaskStatus.Queued, harness.repo.byId(2)?.status)
        // 解析暂停期间会话可能已被空闲规则清空，补投前只记录基线，用增量判断补投是否计入。
        val sessionBeforeRedispatch = harness.engine.snapshot.value.totalInSession

        harness.resolver.behavior = { songId, _, _ -> ApiResponse.Success(resolvedTrack(songId), null) }
        harness.downloader.behavior = { id ->
            if (id == 2L) {
                harness.repo.updateProgress(id, 500L)
                gates.waitFor(id)
            }
            harness.downloader.complete(id)
        }
        harness.repo.add(queuedTask(3))
        harness.engine.requestDownload(listOf(3))
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(
            "the redispatched task must be held in the download loop for the session check",
            TaskStatus.Downloading,
            harness.repo.byId(2)?.status,
        )
        assertEquals(TaskStatus.Completed, harness.repo.byId(3)?.status)
        val during = harness.engine.snapshot.value
        assertEquals(
            "the redispatched task and the newly requested task must both join the session",
            sessionBeforeRedispatch + 2,
            during.totalInSession,
        )
        assertEquals(
            "the session bytes must include the redispatched task's progress",
            1_503L,
            during.bytesDone,
        )

        gates.releaseAll()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(TaskStatus.Failed, harness.repo.byId(1)?.status)
        assertEquals(
            "requestDownload must redispatch queued work once the key is fixed",
            TaskStatus.Completed,
            harness.repo.byId(2)?.status,
        )
        assertEquals(TaskStatus.Completed, harness.repo.byId(3)?.status)
    }

    @Test
    fun `retry keeps the task when its pending file cannot be deleted`() = engineTest { harness ->
        harness.repo.add(failedTask(1, mediaStoreId = 601))
        harness.fileStore.failingDeleteIds += 601L

        harness.engine.retry(1)
        advanceTimeBy(5_000)
        runCurrent()

        val task = harness.repo.byId(1)
        assertEquals("a failed delete must abort the retry", TaskStatus.Failed, task?.status)
        assertEquals("the pending file reference must be preserved", 601L, task?.mediaStoreId)
        assertTrue("an aborted retry must not start parsing", harness.downloader.started.isEmpty())
    }

    @Test
    fun `cancel keeps the task when its pending file cannot be deleted`() = engineTest { harness ->
        harness.repo.add(waitingTask(1, mediaStoreId = 701))
        harness.fileStore.failingDeleteIds += 701L

        harness.engine.cancel(1)
        advanceTimeBy(1_000)
        runCurrent()

        val task = harness.repo.byId(1)
        assertEquals("a failed delete must not hide the task as cancelled", TaskStatus.Waiting, task?.status)
        assertEquals("the pending file reference must be preserved", 701L, task?.mediaStoreId)
    }

    @Test
    fun `cancelling all skips tasks whose pending file cannot be deleted`() = engineTest { harness ->
        harness.repo.add(waitingTask(1, mediaStoreId = 801))
        harness.repo.add(waitingTask(2, mediaStoreId = 802))
        harness.fileStore.failingDeleteIds += 801L

        harness.engine.cancelAll()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals("the task with an undeletable file must be left intact", TaskStatus.Waiting, harness.repo.byId(1)?.status)
        assertEquals(801L, harness.repo.byId(1)?.mediaStoreId)
        assertEquals(TaskStatus.Cancelled, harness.repo.byId(2)?.status)
        assertFalse("cancelAll must clear its pool pause", harness.engine.snapshot.value.isPaused)
    }

    @Test
    fun `retrying all failures skips tasks whose pending file cannot be deleted`() = engineTest { harness ->
        harness.repo.add(failedTask(1, mediaStoreId = 901))
        harness.repo.add(failedTask(2, mediaStoreId = 902))
        harness.fileStore.failingDeleteIds += 901L

        harness.engine.retryAllFailed()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals("the undeletable failure must stay failed", TaskStatus.Failed, harness.repo.byId(1)?.status)
        assertEquals(901L, harness.repo.byId(1)?.mediaStoreId)
        assertEquals("other failures must still be retried", TaskStatus.Completed, harness.repo.byId(2)?.status)
    }

    @Test
    fun `the snapshot keeps completed bytes and totals after a task finishes`() =
        engineTest(concurrency = 2) { harness ->
            val gates = DownloadGates()
            harness.downloader.behavior = { id ->
                gates.waitFor(id)
                harness.downloader.complete(id)
            }
            harness.repo.add(waitingTask(1, sizeBytes = 1_000))
            harness.repo.add(waitingTask(2, sizeBytes = 1_000))

            harness.engine.requestDownload(listOf(1, 2))
            advanceTimeBy(1_500)
            runCurrent()

            gates.release(1)
            advanceTimeBy(1_500)
            runCurrent()

            val during = harness.engine.snapshot.value
            assertEquals(
                "a completed task's final bytes must stay in the session progress",
                1_000L,
                during.bytesDone,
            )
            assertEquals(
                "the total must include the completed task while another is running",
                2_000L,
                during.bytesTotal,
            )

            gates.releaseAll()
            advanceTimeBy(5_000)
            runCurrent()
        }

    @Test
    fun `the snapshot reports an indeterminate total when any session size is unknown`() =
        engineTest(concurrency = 2) { harness ->
            val gates = DownloadGates()
            harness.downloader.behavior = { id ->
                gates.waitFor(id)
                harness.downloader.complete(id)
            }
            harness.repo.add(waitingTask(1, sizeBytes = 1_000))
            harness.repo.add(waitingTask(2, sizeBytes = null))

            harness.engine.requestDownload(listOf(1, 2))
            advanceTimeBy(1_500)
            runCurrent()

            assertNull(
                "any unknown size must make the aggregate total indeterminate",
                harness.engine.snapshot.value.bytesTotal,
            )

            gates.releaseAll()
            advanceTimeBy(5_000)
            runCurrent()
        }

    @Test
    fun `a failed parse still triggers the idle stop`() = engineTest { harness ->
        harness.repo.add(queuedTask(1))
        harness.resolver.behavior = { _, _, _ -> ApiResponse.Failure(ApiFailure.ServiceUnavailable("down"), null) }

        harness.engine.requestDownload(listOf(1))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(TaskStatus.Failed, harness.repo.byId(1)?.status)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("a finished parse job must be re-evaluated for idling", 1, harness.stopper.stopCount)
    }

    @Test
    fun `a key-level parse failure lets the service go idle with stranded queued tasks`() = engineTest { harness ->
        harness.repo.add(queuedTask(1))
        harness.repo.add(queuedTask(2))
        harness.resolver.behavior = { _, _, _ -> ApiResponse.Failure(ApiFailure.InvalidKey("bad key"), null) }

        harness.engine.requestDownload(listOf(1, 2))
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(TaskStatus.Failed, harness.repo.byId(1)?.status)
        assertEquals(TaskStatus.Queued, harness.repo.byId(2)?.status)
        assertEquals("the idle timeout has not elapsed yet", 0, harness.stopper.stopCount)

        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(
            "queued tasks stranded by a parse pause must not keep the service alive",
            1,
            harness.stopper.stopCount,
        )
    }

    @Test
    fun `a storage failure lets the service go idle with stranded waiting tasks and resume recovers`() =
        engineTest { harness ->
            harness.downloader.behavior = { id ->
                if (id == 1L) {
                    harness.downloader.fail(id, FailureKind.Storage)
                } else {
                    harness.downloader.complete(id)
                }
            }
            harness.repo.add(waitingTask(1))
            harness.repo.add(waitingTask(2))

            harness.engine.requestDownload(listOf(1, 2))
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(TaskStatus.Failed, harness.repo.byId(1)?.status)
            assertEquals(TaskStatus.Waiting, harness.repo.byId(2)?.status)
            assertFalse("a storage failure must hold the pool", 2L in harness.downloader.started)
            assertEquals("the idle timeout has not elapsed yet", 0, harness.stopper.stopCount)

            advanceTimeBy(2_000)
            runCurrent()

            assertEquals(
                "waiting tasks stranded by a pool pause must not keep the service alive",
                1,
                harness.stopper.stopCount,
            )

            harness.engine.resumeAll()
            advanceTimeBy(5_000)
            runCurrent()

            assertFalse("resuming after the idle stop must clear the pool pause", harness.engine.snapshot.value.isPaused)
            assertTrue("resuming must redispatch the stranded waiting task", 2L in harness.downloader.started)
            assertEquals(TaskStatus.Completed, harness.repo.byId(2)?.status)
        }

    @Test
    fun `a parsed task starts downloading even when the repository update is delayed`() = engineTest { harness ->
        harness.repo.emitOnCompleteResolve = false
        harness.repo.add(queuedTask(1))

        harness.engine.requestDownload(listOf(1))
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(
            "the parse-to-download handoff must not depend on a repository refresh removing the job",
            TaskStatus.Completed,
            harness.repo.byId(1)?.status,
        )
        assertEquals(listOf(1L), harness.downloader.started)
    }

    @Test
    fun `a parsed task starts downloading when the repository emits asynchronously`() = engineTest { harness ->
        harness.repo.asyncEmissions = true
        harness.repo.add(queuedTask(1))

        harness.engine.requestDownload(listOf(1))
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(
            "an asynchronous repository flow must not make the download pool drop the handoff",
            TaskStatus.Completed,
            harness.repo.byId(1)?.status,
        )
        assertEquals(listOf(1L), harness.downloader.started)
    }

    private fun parsingFailurePausesQueue(
        failure: ApiFailure,
        expectedKind: FailureKind,
    ) = engineTest { harness ->
        harness.repo.add(queuedTask(1))
        harness.repo.add(queuedTask(2))
        harness.resolver.behavior = { _, _, _ -> ApiResponse.Failure(failure, null) }

        harness.engine.requestDownload(listOf(1, 2))
        advanceTimeBy(1_000)
        runCurrent()

        val tasks = listOf(1L, 2L).mapNotNull { harness.repo.byId(it) }
        val failed = tasks.filter { it.status == TaskStatus.Failed }
        assertEquals("exactly one task must take the failure", 1, failed.size)
        assertEquals(expectedKind, failed.single().failureKind)
        assertEquals(
            "later queued tasks must stay queued after a key-level failure",
            1,
            tasks.count { it.status == TaskStatus.Queued },
        )
        assertEquals("no later task may be parsed", 1, harness.resolver.requestedSongIds.size)
        assertTrue(harness.downloader.started.isEmpty())

        harness.resolver.behavior = { songId, _, _ -> ApiResponse.Success(resolvedTrack(songId), null) }
        harness.engine.resumeAll()
        advanceTimeBy(5_000)
        runCurrent()

        val afterResume = listOf(1L, 2L).mapNotNull { harness.repo.byId(it) }
        assertEquals("resuming must restart queued parsing", 1, afterResume.count { it.status == TaskStatus.Completed })
        assertEquals("the failed task is not silently retried", 1, afterResume.count { it.status == TaskStatus.Failed })
    }
}

// region 测试替身

/**
 * 按虚拟时间运行的引擎夹具；`engineTest` 负责 attach、detach 与作用域收尾。
 */
internal fun engineTest(
    concurrency: Int = 1,
    initialTasks: List<DownloadTask> = emptyList(),
    body: suspend TestScope.(EngineHarness) -> Unit,
) = runTest {
    val harness = EngineHarness(concurrency, initialTasks)
    harness.notifier.timeSource = { testScheduler.currentTime }
    val scope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
    harness.engine.attach(scope)
    try {
        body(harness)
    } finally {
        harness.detachOnce()
        runCurrent()
        scope.cancel()
    }
}

internal class EngineHarness(
    concurrency: Int = 1,
    initialTasks: List<DownloadTask> = emptyList(),
) {
    val clock = EngineFakeClock()
    val repo = EngineFakeRepository(initialTasks)
    val resolver = EngineFakeResolver()
    val settings = EngineFakeSettings(concurrency)
    val fileStore = EngineFakeFileStore()
    val downloader = EngineFakeDownloader(repo, fileStore)
    val notifier = EngineFakeNotifier()
    val wakeLock = EngineFakeWakeLock()
    val stopper = EngineFakeStopper()
    val serviceStarter = EngineFakeServiceStarter()

    val engine = DownloadEngine(
        repo = repo,
        resolver = resolver,
        settings = settings,
        fileStore = fileStore,
        downloader = downloader,
        notifications = notifier,
        wakeLock = wakeLock,
        clock = clock,
        serviceStopper = stopper,
        serviceStarter = serviceStarter.starter,
    )

    private var detached = false

    /** 保证测试内的显式 detach 与夹具收尾不会重复调用。 */
    fun detachOnce() {
        if (detached) return
        detached = true
        engine.detach()
    }
}

/** 每个任务一个闸门，用于把下载协程保持在执行中。 */
internal class DownloadGates {
    private val gates = mutableMapOf<Long, CompletableDeferred<Unit>>()

    fun gate(id: Long): CompletableDeferred<Unit> = gates.getOrPut(id) { CompletableDeferred() }

    suspend fun waitFor(id: Long) {
        gate(id).await()
    }

    fun release(id: Long) {
        gate(id).complete(Unit)
    }

    fun releaseAll() {
        gates.values.forEach { it.complete(Unit) }
    }
}

internal class EngineFakeClock : AppClock {
    private var now = 1_000L
    private var elapsed = 0L

    override fun nowMillis(): Long = now

    override fun elapsedRealtime(): Long = elapsed

    fun advance(millis: Long) {
        now += millis
        elapsed += millis
    }
}

internal class EngineFakeSettings(initialConcurrency: Int) : AppSettings {
    val concurrency = MutableStateFlow(initialConcurrency)
    val key = MutableStateFlow<String?>("test-key")

    override val apiKey: Flow<String?> = key
    override val defaultQuality: Flow<MusicQuality> = MutableStateFlow(MusicQuality.Exhigh)
    override val maxConcurrentDownloads: Flow<Int> = concurrency
    override val themeMode: Flow<ThemeMode> = MutableStateFlow(ThemeMode.System)
    override val notificationAsked: Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setApiKey(value: String) {
        key.value = value
    }

    override suspend fun setDefaultQuality(value: MusicQuality) = Unit

    override suspend fun setMaxConcurrentDownloads(value: Int) = Unit

    override suspend fun setThemeMode(value: ThemeMode) = Unit

    override suspend fun markNotificationAsked() = Unit
}

internal class EngineFakeResolver : TrackResolver {
    val requestedSongIds = mutableListOf<Long>()

    var behavior: suspend (SongId, MusicQuality, String) -> ApiResponse<ResolvedTrack> =
        { songId, _, _ -> ApiResponse.Success(resolvedTrack(songId), null) }

    var active = 0
        private set

    var maxActive = 0
        private set

    override suspend fun resolve(
        songId: SongId,
        requested: MusicQuality,
        apiKey: String,
    ): ApiResponse<ResolvedTrack> {
        requestedSongIds += songId.value
        active += 1
        maxActive = maxOf(maxActive, active)
        return try {
            behavior(songId, requested, apiKey)
        } finally {
            active -= 1
        }
    }
}

internal class EngineFakeFileStore : TaskFileStore {
    val deletedIds = mutableListOf<Long>()
    val deleteAttempts = mutableListOf<Long>()

    /** 模拟 MediaStore 删除失败：返回 false，调用方必须保留任务记录。 */
    val failingDeleteIds = mutableSetOf<Long>()

    private var nextId = 1_000L

    override suspend fun createPending(displayName: String, mimeType: String): CreatedFile =
        CreatedFile(mediaStoreId = nextId++, displayName = displayName)

    override suspend fun openAppend(file: CreatedFile): OutputStream = ByteArrayOutputStream()

    override suspend fun openTruncate(file: CreatedFile): OutputStream = ByteArrayOutputStream()

    override suspend fun complete(file: CreatedFile) = Unit

    override suspend fun delete(file: CreatedFile) {
        deletedIds += file.mediaStoreId
    }

    override suspend fun sizeOf(file: CreatedFile): Long = 0L

    override suspend fun md5Of(file: CreatedFile): String = ""

    override suspend fun deleteById(mediaStoreId: Long): Boolean {
        deleteAttempts += mediaStoreId
        if (mediaStoreId in failingDeleteIds) return false
        deletedIds += mediaStoreId
        return true
    }
}

/**
 * Fake 下载器模拟真实 worker 的取消收尾：取消删除 pending 文件并落库 `Cancelled`，
 * 暂停保留文件并落库 `Paused`。引擎不得重复执行这些副作用。
 */
internal class EngineFakeDownloader(
    private val repo: EngineFakeRepository,
    private val fileStore: EngineFakeFileStore,
) : TaskDownloader {
    val started = mutableListOf<Long>()
    val cancelledSignals = mutableListOf<CancellationSignal?>()

    var behavior: suspend (Long) -> WorkerResult = { id -> complete(id) }

    var active = 0
        private set

    var maxActive = 0
        private set

    override suspend fun execute(taskId: Long): WorkerResult {
        started += taskId
        // 与 DefaultDownloadWorker 一致：下载器负责 Waiting -> Downloading 的状态迁移。
        repo.startDownloading(taskId)
        active += 1
        maxActive = maxOf(maxActive, active)
        try {
            return behavior(taskId)
        } catch (cancelled: CancellationException) {
            val signal = cancelled as? CancellationSignal
            cancelledSignals += signal
            // 模拟真实 worker 在 NonCancellable 收尾中删除文件并落库，引擎测试关心的是取消原因与等待语义。
            withContext(NonCancellable) {
                when (signal) {
                    CancellationSignal.Cancel -> {
                        repo.byId(taskId)?.mediaStoreId?.let { fileStore.deleteById(it) }
                        repo.markCancelled(taskId)
                    }
                    else -> repo.markPaused(taskId)
                }
            }
            throw cancelled
        } finally {
            active -= 1
        }
    }

    suspend fun complete(taskId: Long): WorkerResult {
        val task = repo.byId(taskId)
        repo.updateProgress(taskId, task?.sizeBytes ?: task?.bytesDownloaded ?: 0L)
        repo.markCompleted(
            id = taskId,
            fileName = task?.fileName ?: "file-$taskId.mp3",
            mediaStoreId = task?.mediaStoreId ?: (10_000L + taskId),
            completedAt = 1_000L,
        )
        return WorkerResult.Completed
    }

    suspend fun fail(taskId: Long, kind: FailureKind): WorkerResult {
        repo.markFailed(taskId, kind, "failed: $kind")
        return WorkerResult.Failed(kind)
    }
}

internal class EngineFakeNotifier : DownloadNotifier {
    val updates = mutableListOf<EngineSnapshot>()
    val updateTimes = mutableListOf<Long>()
    var timeSource: () -> Long = { 0L }
    var clearCount = 0
        private set

    override fun update(snapshot: EngineSnapshot, paused: Boolean) {
        updates += snapshot
        updateTimes += timeSource()
    }

    override fun clear() {
        clearCount += 1
    }
}

internal class EngineFakeWakeLock : WakeLockController {
    var referenceCount = 0
        private set

    var releaseAllCount = 0
        private set

    val isHeld: Boolean
        get() = referenceCount > 0

    override fun acquire() {
        referenceCount += 1
    }

    override fun release() {
        if (referenceCount > 0) referenceCount -= 1
    }

    override fun releaseAll() {
        referenceCount = 0
        releaseAllCount += 1
    }
}

internal class EngineFakeStopper : ServiceStopper {
    var stopCount = 0
        private set

    override fun stop() {
        stopCount += 1
    }
}

/**
 * 记录前台服务启动次数。
 *
 * 通过 SAM 适配器持有启动契约，而不是直接实现接口：启动器签名正在从 `start(context)` 迁移到
 * 无参 `start()`，适配器对两种签名都成立，使红阶段失败在行为断言而不是编译错误上。
 */
internal class EngineFakeServiceStarter {
    var startCount = 0
        private set

    val starter: DownloadServiceStarter = DownloadServiceStarter { startCount += 1 }
}

/**
 * 内存任务仓库；状态推进与真实仓库一致，便于断言状态历史与批量操作结果。
 *
 * `observeCompleted` 与真实仓库一样发布已完成任务，避免引擎按契约统计完成数时被 Fake 误伤。
 */
internal class EngineFakeRepository(initialTasks: List<DownloadTask> = emptyList()) : TaskRepository {

    private val tasks = linkedMapOf<Long, DownloadTask>()
    private val histories = mutableMapOf<Long, MutableList<TaskStatus>>()
    private val inProgress = MutableSharedFlow<List<DownloadTask>>(replay = 1, extraBufferCapacity = 256)
    private val completed = MutableStateFlow<List<DownloadTask>>(emptyList())

    /**
     * 模拟 Room Flow 的异步发射：为 false 时 `completeResolve` 不发布状态，
     * 用于验证解析作业交接不依赖仓库流的刷新时机。
     */
    var emitOnCompleteResolve = true

    /** 模拟 Room Flow 在独立调度器上发射：每次写入先让出，再由收集方异步观察。 */
    var asyncEmissions = false

    init {
        initialTasks.forEach { task ->
            tasks[task.id] = task
            histories[task.id] = mutableListOf(task.status)
        }
        inProgress.tryEmit(currentTasks())
        completed.value = completedTasks()
    }

    suspend fun add(task: DownloadTask) {
        tasks[task.id] = task
        histories[task.id] = mutableListOf(task.status)
        completed.value = completedTasks()
        publish()
    }

    fun statusHistory(id: Long): List<TaskStatus> = histories[id]?.toList().orEmpty()

    override fun observeInProgress(): Flow<List<DownloadTask>> = inProgress

    override fun observeCompleted(): Flow<List<DownloadTask>> = completed

    override suspend fun byId(id: Long): DownloadTask? = tasks[id]

    override suspend fun enqueue(text: String, quality: MusicQuality): EnqueueOutcome =
        error("enqueue is not used by the engine")

    override suspend fun enqueueLinks(links: List<SongLink>, quality: MusicQuality): EnqueueOutcome =
        error("enqueueLinks is not used by the engine")

    override suspend fun markResolving(id: Long) = mutate(id) { it.copy(status = TaskStatus.Resolving) }

    override suspend fun completeResolve(id: Long, track: ResolvedTrack) = mutate(
        id,
        emit = emitOnCompleteResolve,
    ) {
        it.copy(
            status = TaskStatus.Waiting,
            actualQuality = track.actualQuality,
            title = track.title,
            artist = track.artist,
            album = track.album,
            artworkUrl = track.artworkUrl,
            format = track.format,
            streamUrl = track.streamUrl,
            sizeBytes = track.sizeBytes,
            md5 = track.md5,
            etag = track.etag,
        )
    }

    override suspend fun startDownloading(id: Long) = mutate(id) { it.copy(status = TaskStatus.Downloading) }

    override suspend fun updateProgress(id: Long, bytesDownloaded: Long) =
        mutate(id) { it.copy(bytesDownloaded = bytesDownloaded) }

    override suspend fun updateStream(id: Long, track: ResolvedTrack) = mutate(id) {
        it.copy(
            streamUrl = track.streamUrl,
            etag = track.etag,
            format = track.format,
            sizeBytes = track.sizeBytes,
            md5 = track.md5,
            actualQuality = track.actualQuality,
        )
    }

    override suspend fun markCompleted(id: Long, fileName: String, mediaStoreId: Long, completedAt: Long) =
        mutate(id) {
            it.copy(
                status = TaskStatus.Completed,
                fileName = fileName,
                mediaStoreId = mediaStoreId,
                completedAt = completedAt,
            )
        }

    override suspend fun markFailed(id: Long, failureKind: FailureKind, message: String) =
        mutate(id) {
            it.copy(status = TaskStatus.Failed, failureKind = failureKind, failureMessage = message)
        }

    override suspend fun markPaused(id: Long) = mutate(id) { it.copy(status = TaskStatus.Paused) }

    override suspend fun markCancelled(id: Long) = mutate(id) { it.copy(status = TaskStatus.Cancelled) }

    override suspend fun resetForRetry(id: Long) = mutate(id) {
        it.copy(
            status = TaskStatus.Queued,
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
            bytesDownloaded = 0L,
            mediaStoreId = null,
            fileName = null,
            failureKind = null,
            failureMessage = null,
            completedAt = null,
        )
    }

    override suspend fun attachFile(id: Long, fileName: String, mediaStoreId: Long, bytesDownloaded: Long) =
        mutate(id) {
            it.copy(fileName = fileName, mediaStoreId = mediaStoreId, bytesDownloaded = bytesDownloaded)
        }

    override suspend fun pause(id: Long) = mutate(id) {
        if (it.status.isActive) it.copy(status = TaskStatus.Paused) else it
    }

    override suspend fun resume(id: Long) = mutate(id) {
        if (it.status != TaskStatus.Paused) {
            it
        } else {
            it.copy(status = if (it.streamUrl != null) TaskStatus.Waiting else TaskStatus.Queued)
        }
    }

    override suspend fun retry(id: Long) = resetForRetry(id)

    override suspend fun deleteRecord(id: Long) {
        tasks.remove(id)
        histories.remove(id)
        completed.value = completedTasks()
        publish()
    }

    override suspend fun deleteRecords(ids: List<Long>) {
        ids.forEach { tasks.remove(it) }
        ids.forEach { histories.remove(it) }
        completed.value = completedTasks()
        publish()
    }

    override suspend fun clearCompleted() {
        val completedIds = tasks.values.filter { it.status == TaskStatus.Completed }.map { it.id }
        completedIds.forEach { tasks.remove(it) }
        completed.value = completedTasks()
        publish()
    }

    override suspend fun pauseAll() {
        tasks.values.filter { it.status.isActive }.forEach { task ->
            mutate(task.id) { it.copy(status = TaskStatus.Paused) }
        }
    }

    override suspend fun resumeAll() {
        tasks.values.filter { it.status == TaskStatus.Paused }.forEach { task ->
            resume(task.id)
        }
    }

    override suspend fun pendingIds(): List<Long> =
        tasks.values.filter { !it.status.isTerminal }.map { it.id }

    override suspend fun cancelAll() {
        tasks.values.filter { !it.status.isTerminal }.forEach { task ->
            mutate(task.id) { it.copy(status = TaskStatus.Cancelled) }
        }
    }

    override suspend fun retryAllFailed() {
        tasks.values.filter { it.status == TaskStatus.Failed }.forEach { task ->
            resetForRetry(task.id)
        }
    }

    override suspend fun allocateFileName(id: Long, base: BaseFileName): String = base.value

    override suspend fun releaseFileName(id: Long) = Unit

    private suspend fun mutate(id: Long, emit: Boolean = true, transform: (DownloadTask) -> DownloadTask) {
        val current = tasks[id] ?: return
        val updated = transform(current)
        tasks[id] = updated
        if (updated.status != current.status) {
            histories.getOrPut(id) { mutableListOf(current.status) }.add(updated.status)
        }
        completed.value = completedTasks()
        if (emit) publish()
    }

    private suspend fun publish() {
        if (asyncEmissions) yield()
        inProgress.emit(currentTasks())
    }

    private fun currentTasks(): List<DownloadTask> =
        tasks.values.filter { it.status != TaskStatus.Completed }

    private fun completedTasks(): List<DownloadTask> =
        tasks.values.filter { it.status == TaskStatus.Completed }
}

// endregion

// region 任务夹具

internal fun queuedTask(id: Long, songId: Long = id): DownloadTask = DownloadTask(
    id = id,
    songId = SongId(songId),
    sourceUrl = "https://music.163.com/song?id=$songId",
    requestedQuality = MusicQuality.Exhigh,
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
    bytesDownloaded = 0L,
    status = TaskStatus.Queued,
    mediaStoreId = null,
    fileName = null,
    failureKind = null,
    failureMessage = null,
    createdAt = id,
    updatedAt = id,
    completedAt = null,
)

internal fun waitingTask(
    id: Long,
    songId: Long = id,
    sizeBytes: Long? = 1_000L + id,
    mediaStoreId: Long? = null,
): DownloadTask = DownloadTask(
    id = id,
    songId = SongId(songId),
    sourceUrl = "https://music.163.com/song?id=$songId",
    requestedQuality = MusicQuality.Exhigh,
    actualQuality = MusicQuality.Exhigh,
    title = "Title $id",
    artist = "Artist $id",
    album = null,
    artworkUrl = null,
    format = AudioFormat.Mp3,
    sizeBytes = sizeBytes,
    md5 = null,
    streamUrl = "https://cdn.test/$id.mp3",
    etag = null,
    bytesDownloaded = 0L,
    status = TaskStatus.Waiting,
    mediaStoreId = mediaStoreId,
    fileName = mediaStoreId?.let { "file-$id.mp3" },
    failureKind = null,
    failureMessage = null,
    createdAt = id,
    updatedAt = id,
    completedAt = null,
)

internal fun failedTask(id: Long, mediaStoreId: Long? = null): DownloadTask = queuedTask(id).copy(
    status = TaskStatus.Failed,
    failureKind = FailureKind.Network,
    failureMessage = "failed",
    mediaStoreId = mediaStoreId,
    fileName = mediaStoreId?.let { "file-$id.mp3" },
)

internal fun pausedTask(id: Long, resolved: Boolean): DownloadTask {
    val base = if (resolved) waitingTask(id) else queuedTask(id)
    return base.copy(status = TaskStatus.Paused)
}

internal fun completedTask(id: Long, mediaStoreId: Long? = null): DownloadTask = waitingTask(
    id = id,
    mediaStoreId = mediaStoreId,
).copy(status = TaskStatus.Completed, completedAt = 1_000L)

internal fun resolvedTrack(songId: SongId): ResolvedTrack = ResolvedTrack(
    songId = songId,
    title = "Title ${songId.value}",
    artist = "Artist",
    album = null,
    artworkUrl = null,
    requestedQuality = MusicQuality.Exhigh,
    actualQuality = MusicQuality.Exhigh,
    format = AudioFormat.Mp3,
    streamUrl = "https://cdn.test/${songId.value}.mp3",
    sizeBytes = 1_000L + songId.value,
    md5 = null,
    etag = null,
)

// endregion
