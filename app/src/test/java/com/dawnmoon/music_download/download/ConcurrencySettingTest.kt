package com.dawnmoon.music_download.download

import com.dawnmoon.music_download.domain.model.TaskStatus
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 运行中调整设置并发数时，[DownloadEngine] 必须立即按新容量调度：
 * 调大唤醒等待任务，调小只约束后续获取，不中断已持有许可的下载。
 */
class ConcurrencySettingTest {

    @Test
    fun `two permits allow at most two downloads at once`() = engineTest(concurrency = 2) { harness ->
        val gates = DownloadGates()
        harness.downloader.behavior = { id ->
            gates.waitFor(id)
            harness.downloader.complete(id)
        }
        (1L..3L).forEach { harness.repo.add(waitingTask(it)) }

        harness.engine.requestDownload(listOf(1, 2, 3))
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals("two permits admit exactly two downloads", 2, harness.downloader.started.size)
        assertEquals(setOf(1L, 2L), harness.downloader.started.toSet())
        assertEquals(2, harness.downloader.maxActive)
        assertFalse("the third task must wait for a permit", 3L in harness.downloader.started)

        gates.release(1)
        advanceTimeBy(2_000)
        runCurrent()

        assertTrue("a released permit must admit the waiting task", 3L in harness.downloader.started)
        assertEquals("the pool must never exceed its permits", 2, harness.downloader.maxActive)

        gates.release(2)
        gates.release(3)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(TaskStatus.Completed, harness.repo.byId(1)?.status)
        assertEquals(TaskStatus.Completed, harness.repo.byId(2)?.status)
        assertEquals(TaskStatus.Completed, harness.repo.byId(3)?.status)
    }

    @Test
    fun `lowering concurrency does not interrupt running downloads and new work waits`() = engineTest(concurrency = 2) { harness ->
        val gates = DownloadGates()
        harness.downloader.behavior = { id ->
            gates.waitFor(id)
            harness.downloader.complete(id)
        }
        (1L..4L).forEach { harness.repo.add(waitingTask(it)) }

        harness.engine.requestDownload(listOf(1, 2, 3, 4))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(setOf(1L, 2L), harness.downloader.started.toSet())

        harness.settings.concurrency.value = 1
        advanceTimeBy(2_000)
        runCurrent()

        assertTrue("lowering the limit must not cancel in-flight downloads", harness.downloader.cancelledSignals.isEmpty())
        assertEquals("existing downloads must keep running", setOf(1L, 2L), harness.downloader.started.toSet())

        gates.release(1)
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals("one running download still occupies the only permit", setOf(1L, 2L), harness.downloader.started.toSet())
        assertFalse(3L in harness.downloader.started)

        gates.release(2)
        advanceTimeBy(2_000)
        runCurrent()

        assertTrue("the waiting task proceeds once every in-flight download finished", 3L in harness.downloader.started)
        assertFalse("only one new download may start under the lowered limit", 4L in harness.downloader.started)

        gates.release(3)
        gates.release(4)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(TaskStatus.Completed, harness.repo.byId(4)?.status)
    }

    @Test
    fun `raising concurrency starts waiting downloads immediately`() = engineTest(concurrency = 2) { harness ->
        val gates = DownloadGates()
        harness.downloader.behavior = { id ->
            gates.waitFor(id)
            harness.downloader.complete(id)
        }
        (1L..5L).forEach { harness.repo.add(waitingTask(it)) }

        harness.engine.requestDownload(listOf(1, 2, 3, 4, 5))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("two permits admit two downloads", 2, harness.downloader.started.size)

        harness.settings.concurrency.value = 5
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals("raising the limit must start every waiting download", (1L..5L).toSet(), harness.downloader.started.toSet())
        assertEquals(5, harness.downloader.maxActive)

        gates.releaseAll()
        advanceTimeBy(5_000)
        runCurrent()
        (1L..5L).forEach { id ->
            assertEquals(TaskStatus.Completed, harness.repo.byId(id)?.status)
        }
    }
}
