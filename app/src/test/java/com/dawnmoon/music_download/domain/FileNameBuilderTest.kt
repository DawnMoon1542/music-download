package com.dawnmoon.music_download.domain

import com.dawnmoon.music_download.domain.model.AudioFormat
import com.dawnmoon.music_download.domain.model.SongId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNameBuilderTest {

    @Test
    fun `combines title and artist with the format extension`() {
        assertEquals(
            "夏霞_あたらよ.flac",
            FileNameBuilder.build("夏霞", "あたらよ", AudioFormat.Flac, SongId(1)).value,
        )
    }

    @Test
    fun `replaces illegal characters with underscores`() {
        assertEquals(
            "A_B_C_D_E_F_G_H_I_J_K_The Kid LAROI_Justin Bieber.flac",
            FileNameBuilder.build(
                "A/B:C*D?E\"F<G>H|I\\J\u0001K",
                "The Kid LAROI/Justin Bieber",
                AudioFormat.Flac,
                SongId(1),
            ).value,
        )
    }

    @Test
    fun `omits the artist segment when the artist is empty`() {
        assertEquals(
            "夏霞.flac",
            FileNameBuilder.build("夏霞", "", AudioFormat.Flac, SongId(1)).value,
        )
    }

    @Test
    fun `collapses whitespace and trims spaces and trailing dots`() {
        assertEquals(
            "夏 霞_あ たらよ.flac",
            FileNameBuilder.build(
                "  夏 \t 霞...  ",
                "  あ \t たらよ.  ",
                AudioFormat.Flac,
                SongId(1),
            ).value,
        )
    }

    @Test
    fun `long title and artist are truncated on utf8 boundaries within the byte budget`() {
        val title = "夏".repeat(80)
        val artist = "あ".repeat(50)

        val value = FileNameBuilder.build(title, artist, AudioFormat.Flac, SongId(1)).value

        assertTrue(value.toByteArray(Charsets.UTF_8).size <= 120)
        assertTrue(value.endsWith(".flac"))
        val name = value.removeSuffix(".flac")
        val titlePart = name.substringBefore('_')
        val artistPart = name.substringAfter('_')
        assertTrue(titlePart.isNotEmpty() && titlePart.length < title.length)
        assertTrue(artistPart.isNotEmpty() && artistPart.length < artist.length)
        assertFalse(titlePart.endsWith(" "))
        assertFalse(titlePart.endsWith("_"))
        assertFalse(artistPart.endsWith(" "))
        assertFalse(artistPart.endsWith("_"))
    }

    @Test
    fun `long artist is truncated before the title`() {
        val artist = "A".repeat(60)

        val value = FileNameBuilder.build("夏霞", artist, AudioFormat.Flac, SongId(1)).value

        val name = value.removeSuffix(".flac")
        val titlePart = name.substringBefore('_')
        val artistPart = name.substringAfter('_')
        assertEquals("夏霞", titlePart)
        assertTrue(artistPart.length < artist.length)
        assertTrue(artistPart.toByteArray(Charsets.UTF_8).size <= 40)
        assertTrue(value.toByteArray(Charsets.UTF_8).size <= 120)
    }

    @Test
    fun `falls back to the song id when the title has no usable characters`() {
        assertEquals(
            "163_42.flac",
            FileNameBuilder.build("\\/:*?\"<>|", "", AudioFormat.Flac, SongId(42)).value,
        )
    }

    @Test
    fun `fallback name keeps the format extension`() {
        assertEquals(
            "163_42.mp3",
            FileNameBuilder.build("///", "", AudioFormat.Mp3, SongId(42)).value,
        )
        assertEquals(
            "163_42.bin",
            FileNameBuilder.build("///", "", AudioFormat.Unknown, SongId(42)).value,
        )
    }
}
