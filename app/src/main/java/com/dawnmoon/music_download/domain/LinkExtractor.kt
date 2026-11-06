package com.dawnmoon.music_download.domain

import com.dawnmoon.music_download.domain.model.SongId
import com.dawnmoon.music_download.domain.model.SongLink

/**
 * 从任意文本（分享文案、聊天消息等）中提取网易云歌曲链接。
 *
 * 只识别两类链接：`163cn.tv/<code>` 短链，以及 `music.163.com` / `y.music.163.com`
 * 下带数字 `id` 参数的歌曲直链。其余 URL 仅计入忽略数量，不进入结果。
 */
class LinkExtractor {

    /**
     * 按出现顺序提取 [text] 中的歌曲链接。
     *
     * 重复的短链只保留首次出现，并通过 [ExtractionResult.duplicateCount] 报告被跳过的次数；
     * 无法识别的 URL 计入 [ExtractionResult.ignoredUrlCount]。
     */
    fun extract(text: String): ExtractionResult {
        val links = mutableListOf<SongLink>()
        val seenShortCodes = mutableSetOf<String>()
        var ignoredUrlCount = 0
        var duplicateCount = 0

        for (match in URL_CANDIDATE_REGEX.findAll(text)) {
            val sourceUrl = match.value.trimEnd { it in TRAILING_PUNCTUATION }
            when (val link = parse(sourceUrl)) {
                null -> ignoredUrlCount++

                is SongLink.ShortLink -> {
                    // 同一短链（同一 code）只保留首条；直链去重留给仓库层按歌曲 ID 处理。
                    if (seenShortCodes.add(link.code)) {
                        links += link
                    } else {
                        duplicateCount++
                    }
                }

                is SongLink.Direct -> links += link
            }
        }

        return ExtractionResult(
            links = links,
            ignoredUrlCount = ignoredUrlCount,
            duplicateCount = duplicateCount,
        )
    }

    private fun parse(sourceUrl: String): SongLink? {
        val parts = URL_STRUCTURE_REGEX.matchEntire(sourceUrl) ?: return null
        val host = parts.groupValues[1].substringBefore(':').lowercase()
        val path = parts.groupValues[2]
        val query = parts.groupValues[3].ifEmpty { null }
        val fragment = parts.groupValues[4].ifEmpty { null }

        return when (host) {
            SHORT_LINK_HOST -> parseShortLink(sourceUrl, path)
            MUSIC_HOST, MOBILE_MUSIC_HOST -> parseDirectLink(sourceUrl, path, query, fragment)
            else -> null
        }
    }

    private fun parseShortLink(sourceUrl: String, path: String): SongLink? {
        val code = SHORT_CODE_REGEX.matchEntire(path)?.groupValues?.get(1) ?: return null
        return SongLink.ShortLink(sourceUrl = sourceUrl, code = code)
    }

    private fun parseDirectLink(
        sourceUrl: String,
        path: String,
        query: String?,
        fragment: String?,
    ): SongLink? {
        // 直链有两种形式：查询串路由（/song?id=、/m/song?id=）和 hash 路由（/#/song?id=）。
        val hashRoute = fragment?.let(::splitRoute)
        val isSongRoute = SONG_ROUTE_REGEX.matches(path) ||
            (path == "/" || path.isEmpty()) && hashRoute?.path?.let(SONG_ROUTE_REGEX::matches) == true
        if (!isSongRoute) return null

        val songId = numericId(query) ?: numericId(hashRoute?.query) ?: return null
        return SongLink.Direct(sourceUrl = sourceUrl, songId = SongId(songId))
    }

    /** 返回查询参数中名为 `id` 的纯数字值；不存在或格式不符时返回 null。 */
    private fun numericId(query: String?): Long? {
        if (query == null) return null
        for (param in query.split('&')) {
            val name = param.substringBefore('=', missingDelimiterValue = "")
            val value = param.substringAfter('=', missingDelimiterValue = "")
            if (name == "id" && NUMERIC_ID_REGEX.matches(value)) {
                return value.toLongOrNull()
            }
        }
        return null
    }

    private fun splitRoute(route: String): Route {
        val match = ROUTE_STRUCTURE_REGEX.matchEntire(route) ?: return Route(route, null)
        return Route(
            path = match.groupValues[1],
            query = match.groupValues[2].ifEmpty { null },
        )
    }

    private data class Route(val path: String, val query: String?)

    private companion object {
        const val SHORT_LINK_HOST = "163cn.tv"
        const val MUSIC_HOST = "music.163.com"
        const val MOBILE_MUSIC_HOST = "y.music.163.com"

        val URL_CANDIDATE_REGEX = Regex(
            """https?://[^\s<>"'()\[\]{}，。；：！？、（）【】《》「」『』]+""",
            RegexOption.IGNORE_CASE,
        )

        /** 去掉紧贴 URL 的中英文标点，例如中文句号或右括号。 */
        val TRAILING_PUNCTUATION = ".,;:!?)]}>'\"，。；：！？）】》」』".toSet()

        val URL_STRUCTURE_REGEX = Regex(
            """^[A-Za-z][A-Za-z0-9+.-]*://([^/?#]+)([^?#]*)(?:\?([^#]*))?(?:#(.*))?$""",
        )
        val SHORT_CODE_REGEX = Regex("""^/([A-Za-z0-9]+)/?$""")
        val SONG_ROUTE_REGEX = Regex("""^/(?:m/)?song/?$""")
        val ROUTE_STRUCTURE_REGEX = Regex("""^([^?]*)(?:\?(.*))?$""")
        val NUMERIC_ID_REGEX = Regex("""^\d+$""")
    }
}

/**
 * 一次链接提取的结果。
 *
 * [links] 保持链接在原文中的出现顺序；[ignoredUrlCount] 是无法识别为网易云歌曲的 URL 数量；
 * [duplicateCount] 是因短链重复而被跳过的数量。
 */
data class ExtractionResult(
    val links: List<SongLink>,
    val ignoredUrlCount: Int,
    val duplicateCount: Int,
)
