package com.dawnmoon.music_download.domain

import com.dawnmoon.music_download.domain.model.AudioFormat
import com.dawnmoon.music_download.domain.model.MusicQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFormatTest {

    @Test
    fun `url extension is detected before query parameters`() {
        assertEquals(
            AudioFormat.Flac,
            AudioFormat.fromUrl("https://x/y.flac?t=1", MusicQuality.Exhigh),
        )
    }

    @Test
    fun `url extension is detected before fragment`() {
        assertEquals(
            AudioFormat.Flac,
            AudioFormat.fromUrl("https://x/y.flac#frag", MusicQuality.Standard),
        )
    }

    @Test
    fun `url with mp3 extension resolves to mp3`() {
        assertEquals(
            AudioFormat.Mp3,
            AudioFormat.fromUrl("https://x/y.mp3", MusicQuality.Hires),
        )
    }

    @Test
    fun `url extension matching ignores letter case`() {
        assertEquals(
            AudioFormat.Flac,
            AudioFormat.fromUrl("https://x/y.FLAC", MusicQuality.Standard),
        )
    }

    @Test
    fun `url without extension falls back to the format implied by quality`() {
        listOf(
            MusicQuality.Jymaster,
            MusicQuality.Sky,
            MusicQuality.Jyeffect,
            MusicQuality.Hires,
            MusicQuality.Lossless,
        ).forEach { quality ->
            assertEquals(
                "expected flac for fallback quality $quality",
                AudioFormat.Flac,
                AudioFormat.fromUrl("https://x/y", quality),
            )
        }
        listOf(MusicQuality.Exhigh, MusicQuality.Standard).forEach { quality ->
            assertEquals(
                "expected mp3 for fallback quality $quality",
                AudioFormat.Mp3,
                AudioFormat.fromUrl("https://x/y", quality),
            )
        }
    }

    @Test
    fun `url with unrecognized extension falls back to the format implied by quality`() {
        assertEquals(
            AudioFormat.Flac,
            AudioFormat.fromUrl("https://x/y.wav", MusicQuality.Hires),
        )
    }

    @Test
    fun `flac extension resolves to flac`() {
        assertEquals(AudioFormat.Flac, AudioFormat.fromExtension("flac"))
    }

    @Test
    fun `mp3 extension resolves to mp3`() {
        assertEquals(AudioFormat.Mp3, AudioFormat.fromExtension("mp3"))
    }

    @Test
    fun `missing extension resolves to unknown`() {
        assertEquals(AudioFormat.Unknown, AudioFormat.fromExtension(null))
    }
}
