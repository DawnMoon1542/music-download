package com.dawnmoon.music_download.domain

import com.dawnmoon.music_download.domain.model.AudioFormat
import com.dawnmoon.music_download.domain.model.BaseFileName
import com.dawnmoon.music_download.domain.model.SongId

/**
 * 根据曲名、艺术家与音频格式生成可安全落盘的文件名主名。
 *
 * 返回的主名不含目录与重名序号；重名序号由下载层追加，避免与媒体库的其他文件冲突。
 */
object FileNameBuilder {

    /** 文件名（含扩展名）的 UTF-8 字节上限。 */
    const val MAX_NAME_BYTES: Int = 120

    /** 艺术家段最多占用的字节数，避免长艺术家把曲名挤到无法辨认。 */
    private const val MAX_ARTIST_BYTES: Int = 40

    private const val SEPARATOR = '_'
    private const val REPLACEMENT = '_'
    private const val FALLBACK_PREFIX = "163"

    /** Windows 与 POSIX 文件名中的非法字符；控制字符另行处理。 */
    private val ILLEGAL_CHARS = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
    private val CONSECUTIVE_SPACES = Regex(" {2,}")

    /**
     * 生成文件名主名。
     *
     * @param title 原始曲名
     * @param artist 原始艺术家；多个艺术家可用 `/` 连接
     * @param format 音频格式，决定扩展名
     * @param songIdForFallback 曲名清洗后为空时用于兜底的歌曲 ID
     */
    fun build(
        title: String,
        artist: String,
        format: AudioFormat,
        songIdForFallback: SongId,
    ): BaseFileName {
        val extension = ".${format.extension}"
        val cleanTitle = sanitize(title)
        val cleanArtist = sanitize(artist)

        // 曲名是文件名的必要部分；完全没有可用字符时用歌曲 ID 兜底。
        val mainName = if (cleanTitle.isEmpty()) {
            "${FALLBACK_PREFIX}_${songIdForFallback.value}"
        } else {
            compose(cleanTitle, cleanArtist, extension)
        }
        return BaseFileName("$mainName$extension")
    }

    /**
     * 按字节预算拼接曲名与艺术家。
     *
     * 预算先扣除扩展名与分隔符；艺术家最多占 `min(40, budget / 3)` 字节，
     * 剩余预算全部给曲名。截断按 UTF-8 字符边界进行，不拆分多字节字符。
     */
    private fun compose(title: String, artist: String, extension: String): String {
        var budget = MAX_NAME_BYTES - extension.utf8Size() - if (artist.isEmpty()) 0 else 1
        if (artist.isEmpty()) {
            return truncateUtf8(title, budget)
        }

        val truncatedArtist = truncateUtf8(artist, minOf(MAX_ARTIST_BYTES, budget / 3))
        budget -= truncatedArtist.utf8Size()
        val truncatedTitle = truncateUtf8(title, budget)
        return "$truncatedTitle$SEPARATOR$truncatedArtist"
    }

    /**
     * 替换非法字符与控制字符、合并空白，并去掉首尾的空白、点号与下划线。
     *
     * 制表符和换行属于空白，合并为单个空格；连续空白只保留一个。
     */
    private fun sanitize(raw: String): String {
        val replaced = StringBuilder(raw.length)
        for (char in raw) {
            when {
                char.isWhitespace() -> replaced.append(' ')
                char in ILLEGAL_CHARS || char.isISOControl() -> replaced.append(REPLACEMENT)
                else -> replaced.append(char)
            }
        }
        return replaced.toString()
            .replace(CONSECUTIVE_SPACES, " ")
            .trim { it == ' ' || it == '.' || it == '_' }
    }

    /**
     * 将 [value] 截断到不超过 [maxBytes] 个 UTF-8 字节，且不拆分码点。
     *
     * 截断后可能以空格或下划线结尾，需再去除，保证拼接出的文件名安全。
     */
    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (value.utf8Size() <= maxBytes) return value

        val result = StringBuilder()
        var usedBytes = 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val byteCount = codePoint.utf8Size()
            if (usedBytes + byteCount > maxBytes) break
            result.appendCodePoint(codePoint)
            usedBytes += byteCount
            index += Character.charCount(codePoint)
        }
        return result.trimEnd { it == ' ' || it == '_' }.toString()
    }

    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

    private fun Int.utf8Size(): Int = when {
        this <= 0x7F -> 1
        this <= 0x7FF -> 2
        this <= 0xFFFF -> 3
        else -> 4
    }
}
