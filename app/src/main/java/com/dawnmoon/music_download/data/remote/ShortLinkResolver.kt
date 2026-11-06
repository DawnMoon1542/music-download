package com.dawnmoon.music_download.data.remote

import com.dawnmoon.music_download.domain.LinkExtractor
import com.dawnmoon.music_download.domain.model.SongId
import com.dawnmoon.music_download.domain.model.SongLink
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** 把短链解析为歌曲 ID；实现不跟随跳转，直接读取 301/302 的 `Location`。 */
interface LinkResolver {

    /** 成功返回 `Location` 中直链的歌曲 ID；无法取得时返回 [ApiFailure.LinkUnsupported]。 */
    suspend fun resolve(link: SongLink.ShortLink): ApiResponse<SongId>
}

/**
 * 基于 OkHttp 的 [LinkResolver]。
 *
 * 跳转必须由本类读取 `Location`：交给 OkHttp 跟随后响应只剩最终页面，原始歌曲参数会丢失，
 * 所以这里从注入的 client 派生一个 `followRedirects(false)` 的独立实例，不修改调用方的配置。
 */
class HttpShortLinkResolver(client: OkHttpClient) : LinkResolver {

    private val client = client.newBuilder().followRedirects(false).build()
    private val linkExtractor = LinkExtractor()

    override suspend fun resolve(link: SongLink.ShortLink): ApiResponse<SongId> = try {
        val url = BASE_URL.newBuilder().addPathSegment(link.code).build()
        val request = Request.Builder().url(url).get().build()
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                when {
                    response.code in REDIRECT_CODES -> resolveLocation(response.header(LOCATION_HEADER))
                    response.isSuccessful -> unsupported("短链未返回跳转")
                    else -> ApiResponse.Failure(
                        ApiFailure.NotFound("短链返回 ${response.code}"),
                        quota = null,
                    )
                }
            }
        }
    } catch (e: IOException) {
        ApiResponse.Failure(ApiFailure.Network(e), quota = null)
    }

    /** 复用 [LinkExtractor] 的直链规则从 `Location` 中取歌曲 ID，短链或其他形式均视为不支持。 */
    private fun resolveLocation(location: String?): ApiResponse<SongId> {
        val songId = location
            ?.let { linkExtractor.extract(it).links.filterIsInstance<SongLink.Direct>().firstOrNull() }
            ?.songId
        return if (songId != null) {
            ApiResponse.Success(songId, quota = null)
        } else {
            unsupported("短链跳转地址不是歌曲直链")
        }
    }

    private fun unsupported(message: String): ApiResponse<SongId> =
        ApiResponse.Failure(ApiFailure.LinkUnsupported(message), quota = null)

    private companion object {
        val BASE_URL = "https://163cn.tv".toHttpUrl()
        const val LOCATION_HEADER = "Location"
        val REDIRECT_CODES = setOf(301, 302)
    }
}
