package com.dawnmoon.music_download.smoke

import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeStackTest {
    @Test
    fun mockWebServerAndSerializationWork() {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"code":200,"msg":"success","data":{"level":"lossless","size":"1"}}""")
                .build(),
        )
        val client = OkHttpClient()
        val body = client.newCall(Request.Builder().url(server.url("/x")).build()).execute().use { it.body.string() }
        val probe = Json { ignoreUnknownKeys = true }.decodeFromString<SmokeProbe>(body)
        assertEquals("lossless", probe.data?.level)
        assertEquals("/x", server.takeRequest().url.encodedPath)
        server.close()
    }
}
