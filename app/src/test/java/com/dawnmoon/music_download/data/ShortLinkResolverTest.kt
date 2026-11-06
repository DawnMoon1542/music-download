package com.dawnmoon.music_download.data

import com.dawnmoon.music_download.data.remote.ApiFailure
import com.dawnmoon.music_download.data.remote.ApiResponse
import com.dawnmoon.music_download.data.remote.HttpShortLinkResolver
import com.dawnmoon.music_download.data.remote.LinkResolver
import com.dawnmoon.music_download.domain.model.SongId
import com.dawnmoon.music_download.domain.model.SongLink
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShortLinkResolverTest {

    private lateinit var server: MockWebServer
    private lateinit var resolver: LinkResolver

    /** 被测实现实际构造的请求 URL；路由拦截器改写前记录，用于校验 host/scheme。 */
    private var observedRequestUrl: HttpUrl? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        resolver = HttpShortLinkResolver(clientRoutingTo(server))
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `resolves a 302 redirect location to the song id`() = runTest {
        enqueueRedirect(code = 302, location = SONG_LOCATION)

        val songId = successOf(resolver.resolve(shortLink()))

        assertEquals(SongId(1867150097), songId)

        val requestUrl = requireNotNull(observedRequestUrl) { "resolver did not issue a request" }
        assertEquals("https", requestUrl.scheme)
        assertEquals("163cn.tv", requestUrl.host)
        assertEquals("/$SHORT_CODE", requestUrl.encodedPath)
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `resolves a 301 redirect location to the song id`() = runTest {
        enqueueRedirect(code = 301, location = SONG_LOCATION)

        val songId = successOf(resolver.resolve(shortLink()))

        assertEquals(SongId(1867150097), songId)
    }

    @Test
    fun `rejects a redirect without a location header`() = runTest {
        enqueueRedirect(code = 302, location = null)

        val failure = failureOf(resolver.resolve(shortLink()))

        assertTrue("expected LinkUnsupported but got $failure", failure is ApiFailure.LinkUnsupported)
    }

    @Test
    fun `rejects a redirect location without a song id`() = runTest {
        enqueueRedirect(code = 302, location = "https://music.163.com/song?foo=1")

        val failure = failureOf(resolver.resolve(shortLink()))

        assertTrue("expected LinkUnsupported but got $failure", failure is ApiFailure.LinkUnsupported)
    }

    @Test
    fun `rejects a successful response without a redirect`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("<html></html>").build())

        val failure = failureOf(resolver.resolve(shortLink()))

        assertTrue("expected LinkUnsupported but got $failure", failure is ApiFailure.LinkUnsupported)
    }

    @Test
    fun `maps a server error to not found`() = runTest {
        server.enqueue(MockResponse.Builder().code(500).body("").build())

        val failure = failureOf(resolver.resolve(shortLink()))

        assertTrue("expected NotFound but got $failure", failure is ApiFailure.NotFound)
    }

    @Test
    fun `maps a connection failure to a network failure`() = runTest {
        val failingClient = OkHttpClient.Builder()
            .addInterceptor { throw IOException("connect failed") }
            .build()

        val failure = failureOf(HttpShortLinkResolver(failingClient).resolve(shortLink()))

        assertTrue("expected Network but got $failure", failure is ApiFailure.Network)
    }

    @Test
    fun `does not follow the redirect`() = runTest {
        enqueueRedirect(code = 302, location = SONG_LOCATION)

        val songId = successOf(resolver.resolve(shortLink()))

        // 跟随跳转会离开 MockWebServer（第二条请求不经过应用拦截器）并得到 200/网络错误，
        // 而不是从 Location 中提取出的 id；成功值与请求次数共同锁定“未跟随”契约。
        assertEquals(SongId(1867150097), songId)
        assertEquals(1, server.requestCount)
    }

    private fun enqueueRedirect(code: Int, location: String?) {
        val builder = MockResponse.Builder().code(code).body("")
        if (location != null) {
            builder.addHeader("Location", location)
        }
        server.enqueue(builder.build())
    }

    /**
     * 将所有请求导向 MockWebServer：被测实现固定访问 https://163cn.tv，
     * 测试无需真实网络即可观察实际发出的请求。
     *
     * 应用拦截器在改写前保留 [HttpUrl]（scheme/host/path），因为改写后的 URL 只剩
     * MockWebServer 地址。DNS 屏蔽重定向目标：若实现错误地跟随跳转，会立即得到
     * UnknownHostException 而不是访问真实外网。
     */
    private fun clientRoutingTo(server: MockWebServer): OkHttpClient =
        OkHttpClient.Builder()
            .dns(offlineDns)
            .addInterceptor { chain ->
                val original = chain.request()
                observedRequestUrl = original.url
                val query = original.url.encodedQuery?.let { "?$it" } ?: ""
                val target = server.url(original.url.encodedPath + query)
                chain.proceed(original.newBuilder().url(target).build())
            }
            .build()

    private val offlineDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname == REAL_MUSIC_HOST) throw UnknownHostException(hostname)
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    private fun shortLink(code: String = SHORT_CODE): SongLink.ShortLink =
        SongLink.ShortLink(sourceUrl = "https://163cn.tv/$code", code = code)

    private fun successOf(response: ApiResponse<SongId>): SongId = when (response) {
        is ApiResponse.Success -> response.value
        is ApiResponse.Failure -> throw AssertionError("expected success but got ${response.failure}")
    }

    private fun failureOf(response: ApiResponse<SongId>): ApiFailure = when (response) {
        is ApiResponse.Success -> throw AssertionError("expected failure but got ${response.value}")
        is ApiResponse.Failure -> response.failure
    }

    private companion object {
        const val SHORT_CODE = "bkp6m2as"
        const val REAL_MUSIC_HOST = "music.163.com"
        const val SONG_LOCATION = "https://music.163.com/song?id=1867150097"
    }
}
