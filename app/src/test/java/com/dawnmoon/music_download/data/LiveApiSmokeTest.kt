package com.dawnmoon.music_download.data

import com.dawnmoon.music_download.data.remote.ApiBaseUrl
import com.dawnmoon.music_download.data.remote.ApiCaller
import com.dawnmoon.music_download.data.remote.ApiResponse
import com.dawnmoon.music_download.data.remote.DefaultChkszApi
import com.dawnmoon.music_download.data.remote.RateLimiter
import com.dawnmoon.music_download.data.repository.QuotaStore
import com.dawnmoon.music_download.domain.DefaultQualityResolver
import com.dawnmoon.music_download.domain.model.ApiCredentials
import com.dawnmoon.music_download.domain.model.AudioFormat
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.Quota
import com.dawnmoon.music_download.domain.model.ResolvedTrack
import com.dawnmoon.music_download.domain.model.SongId
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 真实网络的冒烟测试，每次执行都会消耗真实额度，因此默认跳过。
 * 需显式设置环境变量 `LIVE_API=1`，且项目根目录同时存在 `api_url.txt` 与 `api_key.txt`
 * （均被 .gitignore 排除）时才执行，例如：`LIVE_API=1 ./gradlew :app:testDebugUnitTest --tests '*LiveApiSmokeTest'`。
 *
 * 从 `user.dir` 向上查找含 `settings.gradle.kts` 的目录，避免依赖固定工作目录。
 */
class LiveApiSmokeTest {

    @Test
    fun `resolves a real song with a configured api key`() = runBlocking {
        assumeTrue("未设置 LIVE_API=1，跳过联网测试", System.getenv("LIVE_API") == "1")
        val root = findProjectRoot()
        assumeTrue("未找到含 settings.gradle.kts 的项目根目录", root != null)

        val keyFile = File(root, API_KEY_FILE)
        assumeTrue("未找到 $API_KEY_FILE，跳过联网测试", keyFile.isFile)

        val apiKey = keyFile.readText().trim()
        assumeTrue("$API_KEY_FILE 为空，跳过联网测试", apiKey.isNotEmpty())

        val urlFile = File(root, API_URL_FILE)
        assumeTrue("未找到 $API_URL_FILE，跳过联网测试", urlFile.isFile)
        val baseUrl = ApiBaseUrl.normalize(urlFile.readText())
        assumeTrue("$API_URL_FILE 不是有效地址，跳过联网测试", baseUrl != null)

        val resolver = DefaultQualityResolver(
            api = DefaultChkszApi(
                client = OkHttpClient(),
                json = Json { ignoreUnknownKeys = true },
            ),
            apiCaller = ApiCaller(
                // RateLimiter 默认时钟依赖 Android SystemClock，JVM 测试中改用系统单调时钟。
                rateLimiter = RateLimiter(
                    clock = { System.nanoTime() / NANOS_PER_MILLI },
                    sleeper = { delay(it) },
                ),
                quotaStore = NoOpQuotaStore,
            ),
        )

        val response: ApiResponse<ResolvedTrack> =
            resolver.resolve(SongId(SONG_ID), MusicQuality.Exhigh, ApiCredentials(baseUrl!!, apiKey))

        val track = when (response) {
            is ApiResponse.Success -> response.value
            is ApiResponse.Failure -> throw AssertionError("联网解析失败: ${response.failure}")
        }
        assertTrue("title 不应为空", track.title.isNotBlank())
        assertTrue("artist 不应为空", track.artist.isNotBlank())
        assertTrue("format 应识别为已知格式", track.format != AudioFormat.Unknown)
    }

    private fun findProjectRoot(): File? {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            if (File(directory, SETTINGS_FILE).isFile) return directory
            directory = directory.parentFile
        }
        return null
    }

    private object NoOpQuotaStore : QuotaStore {

        override val quota: Flow<Quota?> = flowOf(null)

        override suspend fun record(quota: Quota) = Unit
    }

    private companion object {
        const val SONG_ID = 1867150097L
        const val API_KEY_FILE = "api_key.txt"
        const val API_URL_FILE = "api_url.txt"
        const val SETTINGS_FILE = "settings.gradle.kts"
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
