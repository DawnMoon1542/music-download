package com.dawnmoon.music_download.data

import com.dawnmoon.music_download.data.remote.ApiCaller
import com.dawnmoon.music_download.data.remote.ApiFailure
import com.dawnmoon.music_download.data.remote.ApiResponse
import com.dawnmoon.music_download.data.remote.RateLimiter
import com.dawnmoon.music_download.data.repository.QuotaStore
import com.dawnmoon.music_download.domain.model.Quota
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiCallerTest {

    @Test
    fun `successful response records its quota`() = runTest {
        val time = VirtualTime()
        val quotaStore = FakeQuotaStore()
        val caller = caller(time, quotaStore)
        val quota = Quota(freeRemaining = 12, paidRemaining = 34, limitPerMinute = 20)
        val response = ApiResponse.Success("ok", quota)

        val result = caller.call { response }

        assertEquals(response, result)
        assertEquals(listOf(quota), quotaStore.recorded)
    }

    @Test
    fun `failed response records its quota`() = runTest {
        val time = VirtualTime()
        val quotaStore = FakeQuotaStore()
        val caller = caller(time, quotaStore)
        val quota = Quota(freeRemaining = 0, paidRemaining = 0, limitPerMinute = 20)
        val response = ApiResponse.Failure(ApiFailure.InvalidKey("apikey invalid"), quota)

        val result = caller.call { response }

        assertEquals(response, result)
        assertEquals(listOf(quota), quotaStore.recorded)
    }

    @Test
    fun `rate limited response waits retry after and returns the retried result`() = runTest {
        val time = VirtualTime()
        val caller = caller(time, FakeQuotaStore())
        val callStarts = mutableListOf<Long>()
        val retried = ApiResponse.Success("ok", null)

        val result = caller.call {
            callStarts += time.now
            if (callStarts.size == 1) {
                ApiResponse.Failure(ApiFailure.RateLimited(retryAfterSeconds = 7, message = "slow down"), null)
            } else {
                retried
            }
        }

        assertEquals(retried, result)
        assertEquals(2, callStarts.size)
        assertTrue(
            "retry started ${callStarts[1] - callStarts[0]}ms after the first attempt",
            callStarts[1] - callStarts[0] >= RETRY_AFTER_MILLIS,
        )
    }

    @Test
    fun `rate limited response without retry after waits the default penalty`() = runTest {
        val time = VirtualTime()
        val caller = caller(time, FakeQuotaStore())
        val callStarts = mutableListOf<Long>()
        val retried = ApiResponse.Success("ok", null)

        val result = caller.call {
            callStarts += time.now
            if (callStarts.size == 1) {
                ApiResponse.Failure(ApiFailure.RateLimited(retryAfterSeconds = null, message = "slow down"), null)
            } else {
                retried
            }
        }

        assertEquals(retried, result)
        assertEquals(2, callStarts.size)
        assertTrue(
            "retry started ${callStarts[1] - callStarts[0]}ms after the first attempt",
            callStarts[1] - callStarts[0] >= DEFAULT_PENALTY_MILLIS,
        )
    }

    @Test
    fun `second rate limited response is returned after two attempts`() = runTest {
        val time = VirtualTime()
        val caller = caller(time, FakeQuotaStore())
        var attempts = 0
        val rateLimited = ApiFailure.RateLimited(retryAfterSeconds = 7, message = "slow down")

        val result = caller.call {
            attempts += 1
            ApiResponse.Failure(rateLimited, null)
        }

        assertEquals(2, attempts)
        assertEquals(ApiResponse.Failure(rateLimited, null), result)
    }

    private fun caller(time: VirtualTime, quotaStore: QuotaStore): ApiCaller = ApiCaller(
        rateLimiter = RateLimiter(clock = time.clock, sleeper = time.sleeper),
        quotaStore = quotaStore,
    )

    /** Controllable time source so the real [RateLimiter] never waits on a real clock. */
    private class VirtualTime {

        var now = 0L
            private set

        val clock: () -> Long = { now }

        val sleeper: suspend (Long) -> Unit = { millis -> now += millis }
    }

    private class FakeQuotaStore : QuotaStore {

        val recorded = mutableListOf<Quota>()

        override val quota: Flow<Quota?> = flowOf(null)

        override suspend fun record(quota: Quota) {
            recorded += quota
        }
    }

    private companion object {
        const val RETRY_AFTER_MILLIS = 7_000L
        const val DEFAULT_PENALTY_MILLIS = 60_000L
    }
}
