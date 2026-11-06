package com.dawnmoon.music_download.download

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLimiterTest {

    @Test
    fun `initial permits admit that many acquisitions and suspend the next until release`() = runTest {
        val limiter = AdaptiveLimiter(initialPermits = 3)
        val acquired = mutableListOf<Int>()

        repeat(4) { index ->
            launch {
                limiter.acquire()
                acquired.add(index)
            }
        }
        runCurrent()

        assertEquals("only three acquisitions should be admitted", listOf(0, 1, 2), acquired)

        limiter.release()
        runCurrent()

        assertEquals("the waiting acquisition should proceed after a release", listOf(0, 1, 2, 3), acquired)
    }

    @Test
    fun `raising permits admits waiting acquisitions up to the new limit`() = runTest {
        val limiter = AdaptiveLimiter(initialPermits = 3)
        var acquired = 0

        // The probes run in the background scope so the acquisition left suspended
        // past the new limit is cancelled with the test instead of blocking runTest's
        // join of its children.
        repeat(6) {
            backgroundScope.launch {
                limiter.acquire()
                acquired += 1
            }
        }
        runCurrent()
        assertEquals("initial permits admit three acquisitions", 3, acquired)

        limiter.setPermits(5)
        runCurrent()

        assertEquals("raising permits should admit exactly two waiting acquisitions", 5, acquired)
    }

    @Test
    fun `lowering permits keeps holders and waits for all of them before admitting a new acquisition`() = runTest {
        val limiter = AdaptiveLimiter(initialPermits = 3)

        repeat(3) {
            launch { limiter.acquire() }
        }
        runCurrent()

        limiter.setPermits(1)

        var nextAcquired = false
        launch {
            limiter.acquire()
            nextAcquired = true
        }
        runCurrent()
        assertFalse("a new acquisition must wait below the lowered limit", nextAcquired)

        limiter.release()
        runCurrent()
        assertFalse("remaining holders must not be interrupted", nextAcquired)

        limiter.release()
        runCurrent()
        assertFalse("the new acquisition still exceeds the lowered limit", nextAcquired)

        limiter.release()
        runCurrent()
        assertTrue("the new acquisition should proceed once every holder released", nextAcquired)
    }

    @Test
    fun `cancelling a queued acquisition does not consume the released permit`() = runTest {
        val limiter = AdaptiveLimiter(initialPermits = 2)
        limiter.acquire()
        limiter.acquire()

        var queuedAcquired = false
        val queued = launch {
            limiter.acquire()
            queuedAcquired = true
        }
        runCurrent()
        assertFalse("the third acquisition must wait for a permit", queuedAcquired)

        queued.cancelAndJoin()
        assertFalse("a cancelled acquisition must not be admitted", queuedAcquired)

        limiter.release()
        runCurrent()

        var nextAcquired = false
        launch {
            limiter.acquire()
            nextAcquired = true
        }
        runCurrent()

        assertTrue("a cancelled waiter must not swallow the released permit", nextAcquired)
    }

    @Test
    fun `cancelling an acquisition granted before it resumes returns the permit`() = runTest {
        val limiter = AdaptiveLimiter(initialPermits = 1)
        limiter.acquire()

        val queued = launch { limiter.acquire() }
        runCurrent()

        // Request cancellation first, then release before the cancellation continuation
        // runs. release() removes and complements the waiter, so the cleanup path must
        // notice that the waiter is no longer queued and hand the granted permit back.
        queued.cancel()
        limiter.release()

        var nextAcquired = false
        launch {
            limiter.acquire()
            nextAcquired = true
        }
        runCurrent()
        queued.join()

        assertTrue("the permit granted to the cancelled waiter must be returned to the pool", nextAcquired)
    }

    @Test
    fun `release without a held permit does not admit extra acquisitions`() = runTest {
        val limiter = AdaptiveLimiter(initialPermits = 1)

        limiter.release()
        limiter.release()

        var acquired = 0
        // One probe stays suspended because only the single initial permit can be
        // granted; run it in the background so it is cancelled with the test.
        repeat(2) {
            backgroundScope.launch {
                limiter.acquire()
                acquired += 1
            }
        }
        runCurrent()

        assertEquals("excess releases must not raise the effective permit count", 1, acquired)
    }
}
