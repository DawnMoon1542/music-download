package com.dawnmoon.music_download.domain

import com.dawnmoon.music_download.domain.model.MusicQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicQualityTest {

    @Test
    fun `jymaster downgrades through every quality down to standard`() {
        assertEquals(
            listOf(
                MusicQuality.Jymaster,
                MusicQuality.Sky,
                MusicQuality.Jyeffect,
                MusicQuality.Hires,
                MusicQuality.Lossless,
                MusicQuality.Exhigh,
                MusicQuality.Standard,
            ),
            MusicQuality.Jymaster.downgradePath(),
        )
    }

    @Test
    fun `lossless downgrades through the lossy qualities down to standard`() {
        assertEquals(
            listOf(
                MusicQuality.Lossless,
                MusicQuality.Exhigh,
                MusicQuality.Standard,
            ),
            MusicQuality.Lossless.downgradePath(),
        )
    }

    @Test
    fun `api value maps back to its quality`() {
        MusicQuality.entries.forEach { quality ->
            assertEquals(quality, MusicQuality.fromApiValue(quality.apiValue))
        }
    }

    @Test
    fun `unrecognized or missing api value maps to null`() {
        assertNull(MusicQuality.fromApiValue("unknown"))
        assertNull(MusicQuality.fromApiValue(null))
    }
}
