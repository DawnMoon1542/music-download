package com.dawnmoon.music_download.data

import com.dawnmoon.music_download.data.remote.ApiFailure
import com.dawnmoon.music_download.data.remote.ApiResponse
import com.dawnmoon.music_download.data.remote.ChkszApi
import com.dawnmoon.music_download.data.remote.DefaultChkszApi
import com.dawnmoon.music_download.domain.model.ApiCredentials
import com.dawnmoon.music_download.domain.model.AudioFormat
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.Quota
import com.dawnmoon.music_download.domain.model.ResolvedTrack
import com.dawnmoon.music_download.domain.model.SongId
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChkszApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ChkszApi
    private lateinit var credentials: ApiCredentials

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = DefaultChkszApi(
            client = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
        )
        credentials = ApiCredentials(baseUrl = server.url("/").toString(), apiKey = API_KEY)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `fetchSong maps successful response fields`() = runTest {
        server.enqueueJson(200, successBody())

        val track = successOf(api.fetchSong(SongId(1867150097), MusicQuality.Hires, credentials))

        assertEquals(SongId(1867150097), track.songId)
        assertEquals("夏霞", track.title)
        assertEquals("あたらよ", track.artist)
        assertEquals("夏霞", track.album)
        assertEquals("https://img.example.com/cover.jpg", track.artworkUrl)
        assertEquals(MusicQuality.Hires, track.requestedQuality)
        assertEquals(MusicQuality.Lossless, track.actualQuality)
        assertEquals(AudioFormat.Flac, track.format)
        assertEquals("https://cdn.example.com/audio/song.flac?token=abc", track.streamUrl)
        assertEquals(30_546_482L, track.sizeBytes)
        assertEquals("f1e2d3c4b5a6", track.md5)
    }

    @Test
    fun `fetchSong fills blank title and artist with fallback text`() = runTest {
        server.enqueueJson(200, successBody(name = "", artist = ""))

        val track = successOf(api.fetchSong(SongId(42), MusicQuality.Exhigh, credentials))

        assertTrue("title should fall back to non blank text", track.title.isNotBlank())
        assertTrue("artist should fall back to non blank text", track.artist.isNotBlank())
    }

    @Test
    fun `fetchSong uses requested quality when response level is unknown`() = runTest {
        server.enqueueJson(200, successBody(level = "ultra"))

        val track = successOf(api.fetchSong(SongId(42), MusicQuality.Hires, credentials))

        assertEquals(MusicQuality.Hires, track.actualQuality)
    }

    @Test
    fun `fetchSong derives format from response quality when url has no extension`() = runTest {
        server.enqueueJson(200, successBody(level = "lossless", url = "https://cdn.example.com/audio/song"))

        val track = successOf(api.fetchSong(SongId(42), MusicQuality.Standard, credentials))

        assertEquals(MusicQuality.Lossless, track.actualQuality)
        assertEquals(AudioFormat.Flac, track.format)
    }

    @Test
    fun `fetchSong sends song id quality and key as query parameters`() = runTest {
        server.enqueueJson(200, successBody())

        api.fetchSong(SongId(2034742057), MusicQuality.Standard, credentials)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/163_music", request.url.encodedPath)
        assertEquals("2034742057", request.url.queryParameter("id"))
        assertEquals("standard", request.url.queryParameter("level"))
        assertEquals(API_KEY, request.url.queryParameter("apikey"))
    }

    @Test
    fun `validateKey requests search endpoint with fixed keyword and limit`() = runTest {
        server.enqueueJson(
            code = 200,
            body = """{"code":200,"msg":"ok","data":{"songs":[]}}""",
            headers = quotaHeaders(free = 37, paid = 120, limit = 20),
        )

        val response = api.validateKey(credentials)

        successOf(response)
        assertEquals(
            Quota(freeRemaining = 37, paidRemaining = 120, limitPerMinute = 20),
            quotaOf(response),
        )

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/163_search", request.url.encodedPath)
        assertEquals("music", request.url.queryParameter("keyword"))
        assertEquals("1", request.url.queryParameter("limit"))
        assertEquals(API_KEY, request.url.queryParameter("apikey"))
    }

    @Test
    fun `validateKey accepts artists returned as a string`() = runTest {
        server.enqueueJson(
            code = 200,
            body = """
                {
                  "code": 200,
                  "msg": "success",
                  "data": {
                    "songs": [
                      {
                        "id": 1315196858,
                        "name": "Party Music",
                        "artists": "Deep Side",
                        "album": "Deep Side"
                      }
                    ]
                  }
                }
            """.trimIndent(),
        )

        successOf(api.validateKey(credentials))
    }

    @Test
    fun `unauthorized response maps to invalid key failure`() = runTest {
        val message = "apikey 无效或已失效"
        server.enqueueJson(401, errorBody(401, message))

        val failure = failureOf(api.fetchSong(SongId(42), MusicQuality.Exhigh, credentials))

        assertEquals(ApiFailure.InvalidKey(message), failure)
    }

    @Test
    fun `payment required response maps to quota exhausted failure`() = runTest {
        val message = "免费和付费额度均已用尽"
        server.enqueueJson(402, errorBody(402, message))

        val failure = failureOf(api.fetchSong(SongId(42), MusicQuality.Exhigh, credentials))

        assertEquals(ApiFailure.QuotaExhausted(message), failure)
    }

    @Test
    fun `forbidden response maps to forbidden failure`() = runTest {
        val message = "用户、Key 或 IP 被封禁"
        server.enqueueJson(403, errorBody(403, message))

        val failure = failureOf(api.fetchSong(SongId(42), MusicQuality.Exhigh, credentials))

        assertEquals(ApiFailure.Forbidden(message), failure)
    }

    @Test
    fun `bad request response maps to invalid request failure`() = runTest {
        val message = "参数缺失或格式错误"
        server.enqueueJson(400, errorBody(400, message))

        val failure = failureOf(api.fetchSong(SongId(42), MusicQuality.Exhigh, credentials))

        assertEquals(ApiFailure.InvalidRequest(message), failure)
    }

    @Test
    fun `missing music url response maps to quality unavailable failure`() = runTest {
        val message = "Music URL not found, song may be unavailable at this quality level"
        server.enqueueJson(404, errorBody(404, message))

        val failure = failureOf(api.fetchSong(SongId(42), MusicQuality.Hires, credentials))

        assertEquals(ApiFailure.QualityUnavailable(message), failure)
    }

    @Test
    fun `other not found response maps to not found failure`() = runTest {
        val message = "资源不存在"
        server.enqueueJson(404, errorBody(404, message))

        val failure = failureOf(api.fetchSong(SongId(42), MusicQuality.Hires, credentials))

        assertEquals(ApiFailure.NotFound(message), failure)
    }

    @Test
    fun `rate limited response maps to rate limited failure with retry after`() = runTest {
        val message = "超过速率限制"
        server.enqueueJson(
            code = 429,
            body = errorBody(429, message),
            headers = mapOf("Retry-After" to "7"),
        )

        val failure = failureOf(api.fetchSong(SongId(42), MusicQuality.Exhigh, credentials))

        assertEquals(ApiFailure.RateLimited(7, message), failure)
    }

    @Test
    fun `rate limited response without retry after maps to null delay`() = runTest {
        val message = "超过速率限制"
        server.enqueueJson(429, errorBody(429, message))

        val failure = failureOf(api.fetchSong(SongId(42), MusicQuality.Exhigh, credentials))

        assertEquals(ApiFailure.RateLimited(null, message), failure)
    }

    @Test
    fun `unavailable response maps to service unavailable failure`() = runTest {
        val message = "API 已被管理员停用"
        server.enqueueJson(503, errorBody(503, message))

        val failure = failureOf(api.fetchSong(SongId(42), MusicQuality.Exhigh, credentials))

        assertEquals(ApiFailure.ServiceUnavailable(message), failure)
    }

    @Test
    fun `non json body maps to malformed failure`() = runTest {
        server.enqueueJson(200, "not a json document")

        val failure = failureOf(api.fetchSong(SongId(42), MusicQuality.Exhigh, credentials))

        assertTrue(failure is ApiFailure.Malformed)
    }

    @Test
    fun `successful response includes parsed quota headers`() = runTest {
        server.enqueueJson(
            code = 200,
            body = successBody(),
            headers = quotaHeaders(free = 37, paid = 120, limit = 20),
        )

        val response = api.fetchSong(SongId(42), MusicQuality.Exhigh, credentials)

        assertEquals(Quota(freeRemaining = 37, paidRemaining = 120, limitPerMinute = 20), quotaOf(response))
    }

    @Test
    fun `failed response includes parsed quota headers`() = runTest {
        server.enqueueJson(
            code = 402,
            body = errorBody(402, "额度已用尽"),
            headers = quotaHeaders(free = 0, paid = 0, limit = 20),
        )

        val response = api.fetchSong(SongId(42), MusicQuality.Exhigh, credentials)

        assertEquals(Quota(freeRemaining = 0, paidRemaining = 0, limitPerMinute = 20), quotaOf(response))
    }

    @Test
    fun `network failure message does not contain the api key`() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                throw IOException("request failed for ${chain.request().url}")
            }
            .build()
        val failingApi = DefaultChkszApi(
            client = client,
            json = Json { ignoreUnknownKeys = true },
        )
        val remote = ApiCredentials(baseUrl = "https://api.test", apiKey = API_KEY)

        val failure = failureOf(failingApi.fetchSong(SongId(42), MusicQuality.Exhigh, remote))

        assertTrue(failure is ApiFailure.Network)
        assertFalse(failure.message.contains(API_KEY))
        assertFalse(
            "toString must not leak the api key",
            failure.toString().contains(API_KEY),
        )
    }

    private fun MockWebServer.enqueueJson(
        code: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        val response = MockResponse.Builder()
            .code(code)
            .body(body)
            .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
            .build()
        enqueue(response)
    }

    private fun quotaHeaders(free: Int, paid: Int, limit: Int): Map<String, String> = mapOf(
        "X-Quota-Free-Remaining" to free.toString(),
        "X-Quota-Paid-Remaining" to paid.toString(),
        "X-RateLimit-Limit" to limit.toString(),
    )

    private fun <T> successOf(response: ApiResponse<T>): T = when (response) {
        is ApiResponse.Success -> response.value
        is ApiResponse.Failure -> throw AssertionError("expected success but got ${response.failure}")
    }

    private fun failureOf(response: ApiResponse<ResolvedTrack>): ApiFailure = when (response) {
        is ApiResponse.Success -> throw AssertionError("expected failure but got ${response.value}")
        is ApiResponse.Failure -> response.failure
    }

    private fun <T> quotaOf(response: ApiResponse<T>): Quota? = when (response) {
        is ApiResponse.Success -> response.quota
        is ApiResponse.Failure -> response.quota
    }

    private fun successBody(
        name: String = "夏霞",
        artist: String = "あたらよ",
        level: String = "lossless",
        url: String = "https://cdn.example.com/audio/song.flac?token=abc",
        size: Long = 30_546_482L,
        md5: String = "f1e2d3c4b5a6",
    ): String = """
        {
          "code": 200,
          "msg": "ok",
          "data": {
            "id": 1867150097,
            "url": "$url",
            "br": 999000,
            "level": "$level",
            "size": $size,
            "md5": "$md5",
            "name": "$name",
            "artist": "$artist",
            "album": "夏霞",
            "picUrl": "https://img.example.com/cover.jpg"
          }
        }
    """.trimIndent()

    private fun errorBody(code: Int, message: String): String =
        """{"code":$code,"msg":"$message"}"""

    private companion object {
        const val API_KEY = "chksz_test_secret_key_123"
    }
}
