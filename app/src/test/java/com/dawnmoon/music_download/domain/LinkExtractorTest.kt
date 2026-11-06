package com.dawnmoon.music_download.domain

import com.dawnmoon.music_download.domain.model.SongId
import com.dawnmoon.music_download.domain.model.SongLink
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkExtractorTest {

    @Test
    fun `extracts a short link from a share message`() {
        val result = LinkExtractor().extract(
            "分享あたらよ的单曲《夏霞》: https://163cn.tv/bkp6m2as (来自@网易云音乐)",
        )

        assertEquals(
            listOf(SongLink.ShortLink(sourceUrl = "https://163cn.tv/bkp6m2as", code = "bkp6m2as")),
            result.links,
        )
        assertEquals(0, result.ignoredUrlCount)
        assertEquals(0, result.duplicateCount)
    }

    @Test
    fun `extracts the song id from a mobile direct link with extra query parameters`() {
        val url = "https://y.music.163.com/m/song?id=1867150097&uct2=abc"

        assertEquals(
            listOf(SongLink.Direct(sourceUrl = url, songId = SongId(1867150097))),
            LinkExtractor().extract(url).links,
        )
    }

    @Test
    fun `extracts the song id from a hash route direct link`() {
        val url = "https://music.163.com/#/song?id=123"

        assertEquals(
            listOf(SongLink.Direct(sourceUrl = url, songId = SongId(123))),
            LinkExtractor().extract(url).links,
        )
    }

    @Test
    fun `extracts the song id from an http direct link`() {
        val url = "http://music.163.com/song?id=456"

        assertEquals(
            listOf(SongLink.Direct(sourceUrl = url, songId = SongId(456))),
            LinkExtractor().extract(url).links,
        )
    }

    @Test
    fun `extracts the song id when it is not the first query parameter`() {
        val url = "https://music.163.com/m/song?foo=1&id=789"

        assertEquals(
            listOf(SongLink.Direct(sourceUrl = url, songId = SongId(789))),
            LinkExtractor().extract(url).links,
        )
    }

    @Test
    fun `matches the host and scheme case insensitively`() {
        val url = "HTTPS://MUSIC.163.COM/song?id=9"

        assertEquals(
            listOf(SongLink.Direct(sourceUrl = url, songId = SongId(9))),
            LinkExtractor().extract(url).links,
        )
    }

    @Test
    fun `keeps the appearance order of mixed distinct links`() {
        val result = LinkExtractor().extract(
            "https://music.163.com/song?id=456 " +
                "https://163cn.tv/abc123 " +
                "https://y.music.163.com/m/song?id=789",
        )

        assertEquals(
            listOf(
                SongLink.Direct(sourceUrl = "https://music.163.com/song?id=456", songId = SongId(456)),
                SongLink.ShortLink(sourceUrl = "https://163cn.tv/abc123", code = "abc123"),
                SongLink.Direct(sourceUrl = "https://y.music.163.com/m/song?id=789", songId = SongId(789)),
            ),
            result.links,
        )
    }

    @Test
    fun `keeps only the first occurrence of a repeated short link`() {
        val result = LinkExtractor().extract(
            "https://163cn.tv/bkp6m2as 分享 https://163cn.tv/bkp6m2as",
        )

        assertEquals(
            listOf(SongLink.ShortLink(sourceUrl = "https://163cn.tv/bkp6m2as", code = "bkp6m2as")),
            result.links,
        )
        assertEquals(1, result.duplicateCount)
    }

    @Test
    fun `counts a non music url as ignored`() {
        val result = LinkExtractor().extract("https://example.com/x")

        assertEquals(emptyList<SongLink>(), result.links)
        assertEquals(1, result.ignoredUrlCount)
    }

    @Test
    fun `returns an empty result when the text contains no url`() {
        val result = LinkExtractor().extract("这里没有链接")

        assertEquals(emptyList<SongLink>(), result.links)
        assertEquals(0, result.ignoredUrlCount)
        assertEquals(0, result.duplicateCount)
    }

    @Test
    fun `rejects a non numeric song id and counts it as ignored`() {
        val result = LinkExtractor().extract("https://music.163.com/song?id=abc")

        assertEquals(emptyList<SongLink>(), result.links)
        assertEquals(1, result.ignoredUrlCount)
    }
}
