package com.dawnmoon.music_download.data

import com.dawnmoon.music_download.data.remote.ApiCaller
import com.dawnmoon.music_download.data.remote.ApiFailure
import com.dawnmoon.music_download.data.remote.ApiResponse
import com.dawnmoon.music_download.data.remote.ChkszApi
import com.dawnmoon.music_download.data.remote.RateLimiter
import com.dawnmoon.music_download.data.repository.DefaultKeyValidator
import com.dawnmoon.music_download.data.repository.KeyValidator
import com.dawnmoon.music_download.data.repository.QuotaStore
import com.dawnmoon.music_download.domain.model.ApiCredentials
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.Quota
import com.dawnmoon.music_download.domain.model.ResolvedTrack
import com.dawnmoon.music_download.domain.model.SongId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyValidatorTest {

    @Test
    fun `valid key returns the quota from the API response`() = runTest {
        val quota = Quota(freeRemaining = 8, paidRemaining = 40, limitPerMinute = 20)
        val api = FakeChkszApi(ApiResponse.Success(Unit, quota))

        val result = validator(api).validate(credentials(VALID_KEY))

        assertEquals(KeyValidator.Result.Valid(quota), result)
        assertEquals(1, api.validateCalls)
    }

    @Test
    fun `unauthorized response returns invalid key`() = runTest {
        val api = FakeChkszApi(ApiResponse.Failure(ApiFailure.InvalidKey("apikey invalid"), null))

        val result = validator(api).validate(credentials(VALID_KEY))

        assertTrue("expected InvalidKey but was $result", result is KeyValidator.Result.InvalidKey)
        assertEquals(1, api.validateCalls)
    }

    @Test
    fun `failures that do not prove the key invalid return indeterminate`() = runTest {
        val failures = listOf(
            ApiFailure.Network(exceptionType = "IOException", message = "network"),
            ApiFailure.QuotaExhausted("quota exhausted"),
            ApiFailure.Forbidden("forbidden"),
        )

        failures.forEach { failure ->
            val api = FakeChkszApi(ApiResponse.Failure(failure, null))

            val result = validator(api).validate(credentials(VALID_KEY))

            assertTrue("expected Indeterminate for $failure but was $result", result is KeyValidator.Result.Indeterminate)
            assertEquals(1, api.validateCalls)
        }
    }

    @Test
    fun `malformed keys are rejected without an API request`() = runTest {
        val api = FakeChkszApi(ApiResponse.Success(Unit, null))
        val subject = validator(api)

        val noPrefix = subject.validate(credentials("abc123"))
        val containingSpace = subject.validate(credentials("chksz_with space"))

        assertTrue("expected InvalidKey for a key without the chksz_ prefix but was $noPrefix", noPrefix is KeyValidator.Result.InvalidKey)
        assertTrue("expected InvalidKey for a key containing a space but was $containingSpace", containingSpace is KeyValidator.Result.InvalidKey)
        assertEquals(0, api.validateCalls)
    }

    @Test
    fun `key containing every allowed punctuation character passes local validation`() = runTest {
        val api = FakeChkszApi(ApiResponse.Success(Unit, null))

        val result = validator(api).validate(credentials("chksz_A-z.0_9~-"))

        assertTrue("expected Valid but was $result", result is KeyValidator.Result.Valid)
        assertEquals(1, api.validateCalls)
    }

    @Test
    fun `malformed base urls are rejected without an API request`() = runTest {
        val api = FakeChkszApi(ApiResponse.Success(Unit, null))
        val subject = validator(api)

        val noScheme = subject.validate(ApiCredentials(baseUrl = "api.test", apiKey = VALID_KEY))
        val ftp = subject.validate(ApiCredentials(baseUrl = "ftp://api.test", apiKey = VALID_KEY))

        assertTrue("expected InvalidKey but was $noScheme", noScheme is KeyValidator.Result.InvalidKey)
        assertTrue("expected InvalidKey but was $ftp", ftp is KeyValidator.Result.InvalidKey)
        assertEquals(0, api.validateCalls)
    }

    @Test
    fun `validation request carries the submitted base url`() = runTest {
        val api = FakeChkszApi(ApiResponse.Success(Unit, null))

        validator(api).validate(ApiCredentials(baseUrl = "https://mirror.test/proxy", apiKey = VALID_KEY))

        assertEquals("https://mirror.test/proxy", api.lastCredentials?.baseUrl)
    }

    private fun credentials(apiKey: String) = ApiCredentials(baseUrl = BASE_URL, apiKey = apiKey)

    private fun validator(api: ChkszApi): DefaultKeyValidator {
        val time = VirtualTime()
        return DefaultKeyValidator(
            api = api,
            apiCaller = ApiCaller(
                rateLimiter = RateLimiter(clock = time.clock, sleeper = time.sleeper),
                quotaStore = FakeQuotaStore(),
            ),
        )
    }

    /** Controllable time source so the real [RateLimiter] never waits on a real clock. */
    private class VirtualTime {

        var now = 0L
            private set

        val clock: () -> Long = { now }

        val sleeper: suspend (Long) -> Unit = { millis -> now += millis }
    }

    private class FakeQuotaStore : QuotaStore {

        override val quota: Flow<Quota?> = flowOf(null)

        override suspend fun record(quota: Quota) = Unit
    }

    private class FakeChkszApi(
        private val response: ApiResponse<Unit>,
    ) : ChkszApi {

        var validateCalls = 0
            private set

        var lastCredentials: ApiCredentials? = null
            private set

        override suspend fun fetchSong(
            songId: SongId,
            quality: MusicQuality,
            credentials: ApiCredentials,
        ): ApiResponse<ResolvedTrack> =
            throw UnsupportedOperationException("fetchSong is not used by key validation")

        override suspend fun validateKey(credentials: ApiCredentials): ApiResponse<Unit> {
            validateCalls += 1
            lastCredentials = credentials
            return response
        }
    }

    private companion object {
        const val VALID_KEY = "chksz_test_secret_key_123"
        const val BASE_URL = "https://api.test"
    }
}
