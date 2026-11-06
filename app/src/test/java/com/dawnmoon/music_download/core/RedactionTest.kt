package com.dawnmoon.music_download.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RedactionTest {

    @Test
    fun `redactUrl replaces the api key value with stars`() {
        val key = "chksz_secret123"

        assertEquals(
            "https://api.chksz.com/api/163_music?apikey=***&id=42&level=hires",
            Redaction.redactUrl("https://api.chksz.com/api/163_music?apikey=$key&id=42&level=hires"),
        )
        assertEquals(
            "https://api.chksz.com/api/163_music?id=42&level=hires&apikey=***",
            Redaction.redactUrl("https://api.chksz.com/api/163_music?id=42&level=hires&apikey=$key"),
        )
    }

    @Test
    fun `redactUrl leaves text without an api key unchanged`() {
        val url = "https://api.chksz.com/api/163_music?id=42&level=hires"

        assertEquals(url, Redaction.redactUrl(url))
    }
}
