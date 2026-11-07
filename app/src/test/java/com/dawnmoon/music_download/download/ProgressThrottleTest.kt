package com.dawnmoon.music_download.download

import com.dawnmoon.music_download.core.AppClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProgressReporter] 的进度节流契约。
 *
 * 以可控时钟驱动报告，断言写库频率由 500ms 或 1 MiB 两个阈值决定，且结束必然落最终值。
 */
class ProgressThrottleTest {

    @Test
    fun `writes progress at most once per 500 ms`() = runTest {
        val clock = VirtualClock()
        val writes = mutableListOf<Long>()
        val writeTimes = mutableListOf<Long>()
        val reporter = ProgressReporter(
            clock = clock,
            write = { bytes ->
                writes += bytes
                writeTimes += clock.elapsedRealtime()
            },
        )

        val total = 8 * 1024 * 1024
        var downloaded = 0L
        while (downloaded < total) {
            downloaded += 64 * 1024
            clock.advanceBy(100)
            reporter.report(downloaded)
        }

        assertTrue("an 8 MiB stream must produce progress writes", writes.size >= 2)
        for (index in 1 until writeTimes.size) {
            val gap = writeTimes[index] - writeTimes[index - 1]
            assertTrue(
                "progress writes $index and ${index - 1} happened ${gap}ms apart, expected at least 500ms",
                gap >= 500,
            )
        }
    }

    @Test
    fun `writes immediately when a megabyte accumulates`() = runTest {
        val clock = VirtualClock()
        val writes = mutableListOf<Long>()
        val reporter = ProgressReporter(clock = clock, write = { writes += it })

        repeat(16) { index -> reporter.report((index + 1) * 64L * 1024L) }

        assertEquals(
            "one megabyte must be reported without waiting for the time threshold",
            listOf(1024L * 1024L),
            writes,
        )
    }

    @Test
    fun `writes the final byte count on finish`() = runTest {
        val clock = VirtualClock()
        val writes = mutableListOf<Long>()
        val reporter = ProgressReporter(clock = clock, write = { writes += it })

        repeat(4) { index -> reporter.report((index + 1) * 64L * 1024L) }
        assertTrue("small progress values must stay below both thresholds", writes.isEmpty())

        reporter.finish(4 * 64L * 1024L)

        assertEquals(listOf(4 * 64L * 1024L), writes)
    }

    private class VirtualClock : AppClock {

        private var now = 0L

        override fun nowMillis(): Long = now

        override fun elapsedRealtime(): Long = now

        fun advanceBy(millis: Long) {
            now += millis
        }
    }
}
