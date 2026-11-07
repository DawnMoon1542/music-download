package com.dawnmoon.music_download.data

import com.dawnmoon.music_download.data.remote.ApiBaseUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiBaseUrlTest {

    @Test
    fun `trailing slashes whitespace query and fragment are removed`() {
        assertEquals("https://api.test", ApiBaseUrl.normalize("  https://api.test/  "))
        assertEquals("https://api.test", ApiBaseUrl.normalize("https://api.test/?a=1#top"))
    }

    @Test
    fun `path prefixes are kept without empty segments`() {
        assertEquals("https://api.test/proxy/v1", ApiBaseUrl.normalize("https://api.test//proxy/v1/"))
    }

    @Test
    fun `plain http and custom ports are allowed`() {
        assertEquals("http://192.168.1.2:8080", ApiBaseUrl.normalize("http://192.168.1.2:8080/"))
    }

    @Test
    fun `non http inputs are rejected`() {
        assertNull(ApiBaseUrl.normalize(""))
        assertNull(ApiBaseUrl.normalize("api.test"))
        assertNull(ApiBaseUrl.normalize("ftp://api.test"))
    }

    @Test
    fun `request urls append the endpoint path after the prefix`() {
        val url = ApiBaseUrl.parse("https://api.test/proxy")!!
            .newBuilder()
            .addPathSegments("api/163_music")
            .build()

        assertEquals("https://api.test/proxy/api/163_music", url.toString())
    }
}
