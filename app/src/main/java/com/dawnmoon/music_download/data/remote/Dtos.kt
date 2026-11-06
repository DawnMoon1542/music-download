package com.dawnmoon.music_download.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `163_music` 响应；状态码以 HTTP 为准，[code] 仅作为服务端自述。 */
@Serializable
data class TrackResponse(
    val code: Int = 0,
    val msg: String = "",
    val data: TrackDto? = null,
)

/** `163_music` 的 `data` 字段。 */
@Serializable
data class TrackDto(
    val id: Long? = null,
    val url: String? = null,
    val br: Int? = null,
    val level: String? = null,
    val size: Long? = null,
    val md5: String? = null,
    val name: String? = null,
    val artist: String? = null,
    val album: String? = null,
    @SerialName("picUrl") val artworkUrl: String? = null,
)

/** `163_search` 响应；`validateKey` 只关心能否解析，字段用于满足序列化。 */
@Serializable
data class SearchResponse(
    val code: Int = 0,
    val msg: String = "",
    val data: SearchDataDto = SearchDataDto(),
)

@Serializable
data class SearchDataDto(
    val songs: List<SearchSongDto> = emptyList(),
)

@Serializable
data class SearchSongDto(
    val id: Long? = null,
    val name: String? = null,
    val artists: List<SearchArtistDto> = emptyList(),
)

@Serializable
data class SearchArtistDto(
    val id: Long? = null,
    val name: String? = null,
)

/** 错误响应只需 `msg` 原文；其余字段忽略。 */
@Serializable
data class ApiErrorResponse(
    val msg: String = "",
)
