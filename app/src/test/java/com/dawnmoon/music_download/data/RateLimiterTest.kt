package com.dawnmoon.music_download.data

import com.dawnmoon.music_download.data.remote.RateLimiter
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    @Test
    fun `first call runs without waiting`() = runTest {
        val time = VirtualTime()
        val limiter = RateLimiter(clock = time.clock, sleeper = time.sleeper)
        var callStart = -1L

        limiter.run { callStart = time.now }

        assertEquals(0L, callStart)
    }

    @Test
    fun `consecutive calls are spaced by at least the minimum interval`() = runTest {
        val time = VirtualTime()
        val limiter = RateLimiter(clock = time.clock, sleeper = time.sleeper)
        val callStarts = mutableListOf<Long>()

        repeat(2) { limiter.run { callStarts.add(time.now) } }

        assertTrue(
            "second call started ${callStarts[1] - callStarts[0]}ms after the first",
            callStarts[1] - callStarts[0] >= MIN_INTERVAL_MILLIS,
        )
    }

    @Test
    fun `no sixty second window contains more than nineteen calls`() = runTest {
        val time = VirtualTime()
        val limiter = RateLimiter(clock = time.clock, sleeper = time.sleeper)
        val callStarts = mutableListOf<Long>()

        repeat(25) { limiter.run { callStarts.add(time.now) } }

        callStarts.indices.forEach { windowStartIndex ->
            val callsInWindow = callStarts
                .drop(windowStartIndex)
                .takeWhile { it - callStarts[windowStartIndex] <= WINDOW_MILLIS }
            assertTrue(
                "window starting at ${callStarts[windowStartIndex]} contains ${callsInWindow.size} calls",
                callsInWindow.size <= MAX_CALLS_PER_WINDOW,
            )
        }
    }

    @Test
    fun `concurrent calls execute one at a time`() = runTest {
        val time = VirtualTime()
        val limiter = RateLimiter(clock = time.clock, sleeper = time.sleeper)
        val intervals = mutableListOf<Pair<Long, Long>>()

        coroutineScope {
            repeat(3) {
                launch {
                    limiter.run {
                        val start = time.now
                        yield()
                        time.advanceBy(WORK_MILLIS)
                        intervals.add(start to time.now)
                    }
                }
            }
        }

        val ordered = intervals.sortedBy { it.first }
        assertEquals(3, ordered.size)
        ordered.zipWithNext { previous, next ->
            assertTrue(
                "call starting at ${next.first} overlaps call ending at ${previous.second}",
                next.first >= previous.second,
            )
        }
    }

    @Test
    fun `penalize delays the next call by at least the requested time`() = runTest {
        val time = VirtualTime()
        val limiter = RateLimiter(clock = time.clock, sleeper = time.sleeper)
        limiter.run { }
        limiter.penalize(PENALTY_MILLIS)

        var nextCallStart = -1L
        limiter.run { nextCallStart = time.now }

        assertTrue(
            "next call started ${nextCallStart}ms after penalize",
            nextCallStart >= PENALTY_MILLIS,
        )
    }

    @Test
    fun `penalize shorter than the remaining interval keeps the minimum interval`() = runTest {
        val time = VirtualTime()
        val limiter = RateLimiter(clock = time.clock, sleeper = time.sleeper)
        limiter.run { }

        // retryAfter 小于基础间隔时不得把 nextAllowedAt 缩短回 now + retryAfter。
        limiter.penalize(SHORT_PENALTY_MILLIS)

        var nextCallStart = -1L
        limiter.run { nextCallStart = time.now }

        assertTrue(
            "next call started ${nextCallStart}ms after the first, expected at least $MIN_INTERVAL_MILLIS ms",
            nextCallStart >= MIN_INTERVAL_MILLIS,
        )
    }

    /** Controllable time source so tests never wait on the real clock or dispatcher. */
    private class VirtualTime {

        var now = 0L
            private set

        val clock: () -> Long = { now }

        val sleeper: suspend (Long) -> Unit = { millis ->
            now += millis
            yield()
        }

        fun advanceBy(millis: Long) {
            now += millis
        }
    }

    private companion object {
        const val MIN_INTERVAL_MILLIS = 3_200L
        const val WINDOW_MILLIS = 60_000L
        const val MAX_CALLS_PER_WINDOW = 19
        const val WORK_MILLIS = 100L
        const val PENALTY_MILLIS = 5_000L
        const val SHORT_PENALTY_MILLIS = 1_000L
    }
}
