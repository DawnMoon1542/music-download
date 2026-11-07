package com.dawnmoon.music_download.data.remote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 用户填写的 API 地址的解析与规范化；设置页、校验器与请求层共用同一规则。 */
object ApiBaseUrl {

    /**
     * 返回去掉首尾空白、查询串、片段与末尾斜杠后的地址；不是 http(s) 地址时返回 null。
     *
     * 末尾斜杠必须去掉：请求层会在地址后追加接口路径，多余的空路径段会产生 `//api/...`。
     */
    fun normalize(raw: String): String? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val normalized = url.newBuilder()
            .query(null)
            .fragment(null)
            .encodedPath("/")
            .apply { segments.forEach { addPathSegment(it) } }
            .build()
            .toString()
        return normalized.trimEnd('/')
    }

    /** 已规范化地址转为 [HttpUrl]；无法解析时返回 null。 */
    fun parse(baseUrl: String): HttpUrl? = normalize(baseUrl)?.toHttpUrlOrNull()
}
