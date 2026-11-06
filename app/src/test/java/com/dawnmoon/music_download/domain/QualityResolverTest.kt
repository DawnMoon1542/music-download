package com.dawnmoon.music_download.domain

import com.dawnmoon.music_download.data.remote.ApiCaller
import com.dawnmoon.music_download.data.remote.ApiFailure
import com.dawnmoon.music_download.data.remote.ApiResponse
import com.dawnmoon.music_download.data.remote.ChkszApi
import com.dawnmoon.music_download.data.remote.RateLimiter
import com.dawnmoon.music_download.data.repository.QuotaStore
import com.dawnmoon.music_download.domain.model.AudioFormat
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.Quota
import com.dawnmoon.music_download.domain.model.ResolvedTrack
import com.dawnmoon.music_download.domain.model.SongId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class QualityResolverTest {

    @Test
    fun `falls back through the quality chain until a level succeeds`() = runTest {
        val songId = SongId(1867150097)
        val unavailable = setOf(
            MusicQuality.Jymaster,
            MusicQuality.Sky,
            MusicQuality.Jyeffect,
            MusicQuality.Hires,
        )
        val api = ScriptedChkszApi { quality ->
            if (quality in unavailable) {
                ApiResponse.Failure(ApiFailure.QualityUnavailable("unavailable ${quality.apiValue}"), SNAPSHOT)
            } else {
                ApiResponse.Success(track(songId, quality), SNAPSHOT)
            }
        }

        val harness = resolver(api)
        val result = harness.resolver.resolve(songId, MusicQuality.Jymaster, API_KEY)

        assertEquals(MusicQuality.Lossless, successOf(result).actualQuality)
        // 每次尝试都经 ApiCaller，因此每个响应携带的额度都会被写入 QuotaStore。
        assertEquals(List(5) { SNAPSHOT }, harness.quotaStore.recorded)
        assertEquals(
            listOf(
                MusicQuality.Jymaster,
                MusicQuality.Sky,
                MusicQuality.Jyeffect,
                MusicQuality.Hires,
                MusicQuality.Lossless,
            ),
            api.requestedQualities,
        )
    }

    @Test
    fun `accepts a silently downgraded response as success`() = runTest {
        val songId = SongId(1867150097)
        val api = ScriptedChkszApi { quality ->
            if (quality == MusicQuality.Hires) {
                ApiResponse.Success(track(songId, quality, actual = MusicQuality.Lossless), SNAPSHOT)
            } else {
                ApiResponse.Failure(ApiFailure.QualityUnavailable("unavailable ${quality.apiValue}"), SNAPSHOT)
            }
        }

        val harness = resolver(api)
        val result = harness.resolver.resolve(songId, MusicQuality.Hires, API_KEY)

        assertEquals(MusicQuality.Lossless, successOf(result).actualQuality)
        assertEquals(listOf(MusicQuality.Hires), api.requestedQualities)
        assertEquals(listOf(SNAPSHOT), harness.quotaStore.recorded)
    }

    @Test
    fun `invalid key failure stops the fallback chain`() = runTest {
        val songId = SongId(1867150097)
        val failure = ApiFailure.InvalidKey("apikey invalid")
        val api = ScriptedChkszApi { quality ->
            if (quality == MusicQuality.Hires) {
                ApiResponse.Failure(failure, SNAPSHOT)
            } else {
                // 若错误地继续降级，这里会返回成功，测试即可发现短路失效。
                ApiResponse.Success(track(songId, quality), SNAPSHOT)
            }
        }

        val harness = resolver(api)
        val result = harness.resolver.resolve(songId, MusicQuality.Hires, API_KEY)

        assertEquals(ApiResponse.Failure(failure, SNAPSHOT), result)
        assertEquals(listOf(MusicQuality.Hires), api.requestedQualities)
        assertEquals(listOf(SNAPSHOT), harness.quotaStore.recorded)
    }

    @Test
    fun `rate limited failure stops the fallback chain`() = runTest {
        val songId = SongId(1867150097)
        val failure = ApiFailure.RateLimited(retryAfterSeconds = 7, message = "slow down")
        val api = ScriptedChkszApi { quality ->
            if (quality == MusicQuality.Hires) {
                ApiResponse.Failure(failure, SNAPSHOT)
            } else {
                // 若错误地继续降级，这里会返回成功，测试即可发现短路失效。
                ApiResponse.Success(track(songId, quality), SNAPSHOT)
            }
        }

        val harness = resolver(api)
        val result = harness.resolver.resolve(songId, MusicQuality.Hires, API_KEY)

        assertEquals(ApiResponse.Failure(failure, SNAPSHOT), result)
        // ApiCaller 对 429 各重试一次，两次请求都必须来自同一次 resolve，且之后不再降级。
        assertEquals(listOf(MusicQuality.Hires, MusicQuality.Hires), api.requestedQualities)
        assertEquals(listOf(SNAPSHOT, SNAPSHOT), harness.quotaStore.recorded)
    }

    @Test
    fun `returns the last quality unavailable failure when every level fails`() = runTest {
        val songId = SongId(1867150097)
        val api = ScriptedChkszApi { quality ->
            ApiResponse.Failure(ApiFailure.QualityUnavailable("unavailable ${quality.apiValue}"), SNAPSHOT)
        }

        val harness = resolver(api)
        val result = harness.resolver.resolve(songId, MusicQuality.Exhigh, API_KEY)

        // Exhigh 的降级链以 Standard 结束，最终应返回最后一次失败而不是第一次。
        assertEquals(
            ApiResponse.Failure(ApiFailure.QualityUnavailable("unavailable standard"), SNAPSHOT),
            result,
        )
        assertEquals(listOf(MusicQuality.Exhigh, MusicQuality.Standard), api.requestedQualities)
        assertEquals(listOf(SNAPSHOT, SNAPSHOT), harness.quotaStore.recorded)
    }

    private fun resolver(api: ChkszApi): Harness {
        val time = VirtualTime()
        val quotaStore = FakeQuotaStore()
        return Harness(
            resolver = DefaultQualityResolver(
                api = api,
                apiCaller = ApiCaller(
                    rateLimiter = RateLimiter(clock = time.clock, sleeper = time.sleeper),
                    quotaStore = quotaStore,
                ),
            ),
            quotaStore = quotaStore,
        )
    }

    private fun track(
        songId: SongId,
        requested: MusicQuality,
        actual: MusicQuality = requested,
    ): ResolvedTrack = ResolvedTrack(
        songId = songId,
        title = "test song",
        artist = "test artist",
        album = null,
        artworkUrl = null,
        requestedQuality = requested,
        actualQuality = actual,
        format = AudioFormat.Flac,
        streamUrl = "https://cdn.example.com/audio/song.flac",
        sizeBytes = 1_000L,
        md5 = null,
    )

    private fun successOf(response: ApiResponse<ResolvedTrack>): ResolvedTrack = when (response) {
        is ApiResponse.Success -> response.value
        is ApiResponse.Failure -> throw AssertionError("expected success but got ${response.failure}")
    }

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

    private data class Harness(
        val resolver: TrackResolver,
        val quotaStore: FakeQuotaStore,
    )

    private class ScriptedChkszApi(
        private val onFetch: (MusicQuality) -> ApiResponse<ResolvedTrack>,
    ) : ChkszApi {

        val requestedQualities = mutableListOf<MusicQuality>()

        override suspend fun fetchSong(
            songId: SongId,
            quality: MusicQuality,
            apiKey: String,
        ): ApiResponse<ResolvedTrack> {
            requestedQualities += quality
            return onFetch(quality)
        }

        override suspend fun validateKey(apiKey: String): ApiResponse<Unit> =
            throw UnsupportedOperationException("validateKey is not used by track resolution")
    }

    private companion object {
        const val API_KEY = "chksz_test_secret_key_123"

        /** 每条脚本化响应都携带同一份额度，用于确认调用确实经由 ApiCaller。 */
        val SNAPSHOT = Quota(freeRemaining = 3, paidRemaining = 5, limitPerMinute = 20)
    }
}
