package com.dawnmoon.music_download.data.remote

import com.dawnmoon.music_download.core.Redaction
import com.dawnmoon.music_download.domain.model.ApiCredentials
import com.dawnmoon.music_download.domain.model.AudioFormat
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.Quota
import com.dawnmoon.music_download.domain.model.ResolvedTrack
import com.dawnmoon.music_download.domain.model.SongId
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** ChKSz 的两个只读接口；实现本身不重试，429 重试由上层 `ApiCaller` 负责。 */
interface ChkszApi {

    /** 用歌曲 ID 与音质换取音频直链与元数据。 */
    suspend fun fetchSong(
        songId: SongId,
        quality: MusicQuality,
        credentials: ApiCredentials,
    ): ApiResponse<ResolvedTrack>

    /** 凭据校验：`keyword` 固定为 `music`，`limit=1`；成功只表示地址与 Key 可用。 */
    suspend fun validateKey(credentials: ApiCredentials): ApiResponse<Unit>
}

/**
 * 基于 OkHttp 的 [ChkszApi]。
 *
 * 地址来自每次调用的 [ApiCredentials]，应用内不内置任何接口地址。
 * 判定以 HTTP 状态码为准；所有对外消息都经过 [Redaction]，防止 Key 出现在异常或界面中。
 */
class DefaultChkszApi(
    private val client: OkHttpClient,
    private val json: Json,
) : ChkszApi {

    override suspend fun fetchSong(
        songId: SongId,
        quality: MusicQuality,
        credentials: ApiCredentials,
    ): ApiResponse<ResolvedTrack> = execute(
        baseUrl = credentials.baseUrl,
        path = MUSIC_PATH,
        parameters = listOf(
            "id" to songId.value.toString(),
            "level" to quality.apiValue,
            "apikey" to credentials.apiKey,
        ),
    ) { body, quota ->
        decodeTrack(body, quota, songId, quality)
    }

    override suspend fun validateKey(credentials: ApiCredentials): ApiResponse<Unit> = execute(
        baseUrl = credentials.baseUrl,
        path = SEARCH_PATH,
        parameters = listOf(
            "keyword" to SEARCH_KEYWORD,
            "limit" to "1",
            "apikey" to credentials.apiKey,
        ),
    ) { body, quota ->
        decodeSearch(body, quota)
    }

    /**
     * 发起一次 GET 请求，成功时交给 [decode]，失败时按状态码映射。
     *
     * 网络异常统一转为 [ApiFailure.Network]；此时没有响应头，额度为 null。
     * 地址无法解析时不发请求，直接返回 [ApiFailure.InvalidRequest]。
     */
    private suspend fun <T> execute(
        baseUrl: String,
        path: String,
        parameters: List<Pair<String, String>>,
        decode: (body: String, quota: Quota) -> ApiResponse<T>,
    ): ApiResponse<T> = try {
        val base = ApiBaseUrl.parse(baseUrl)
            ?: return ApiResponse.Failure(ApiFailure.InvalidRequest(INVALID_BASE_URL_MESSAGE), quota = null)
        val url = base.newBuilder()
            .addPathSegments(path)
            .apply { parameters.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
        val request = Request.Builder().url(url).get().build()
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val quota = response.readQuota()
                if (response.isSuccessful) {
                    decode(response.body.string(), quota)
                } else {
                    failureFor(response, quota)
                }
            }
        }
    } catch (e: IOException) {
        ApiResponse.Failure(ApiFailure.Network(e), quota = null)
    }

    private fun decodeTrack(
        body: String,
        quota: Quota,
        songId: SongId,
        requestedQuality: MusicQuality,
    ): ApiResponse<ResolvedTrack> = try {
        val track = json.decodeFromString<TrackResponse>(body).data?.toResolvedTrack(songId, requestedQuality)
        if (track == null) {
            ApiResponse.Failure(ApiFailure.Malformed("响应缺少音频地址"), quota)
        } else {
            ApiResponse.Success(track, quota)
        }
    } catch (e: SerializationException) {
        ApiResponse.Failure(ApiFailure.Malformed(Redaction.redactUrl(e.message ?: "响应不是合法 JSON")), quota)
    }

    private fun decodeSearch(body: String, quota: Quota): ApiResponse<Unit> = try {
        json.decodeFromString<SearchResponse>(body)
        ApiResponse.Success(Unit, quota)
    } catch (e: SerializationException) {
        ApiResponse.Failure(ApiFailure.Malformed(Redaction.redactUrl(e.message ?: "响应不是合法 JSON")), quota)
    }

    /** HTTP 状态码到 [ApiFailure] 的映射；错误信息取响应体 `msg` 原文。 */
    private fun failureFor(response: Response, quota: Quota): ApiResponse.Failure {
        val message = readMessage(response.body.string()).ifBlank { response.message }
        val redacted = Redaction.redactUrl(message)
        val failure = when (response.code) {
            400 -> ApiFailure.InvalidRequest(redacted)
            401 -> ApiFailure.InvalidKey(redacted)
            402 -> ApiFailure.QuotaExhausted(redacted)
            403 -> ApiFailure.Forbidden(redacted)
            404 -> if (message.contains(MUSIC_NOT_FOUND_MARKER)) {
                ApiFailure.QualityUnavailable(redacted)
            } else {
                ApiFailure.NotFound(redacted)
            }
            429 -> ApiFailure.RateLimited(response.header(RETRY_AFTER_HEADER)?.trim()?.toLongOrNull(), redacted)
            503 -> ApiFailure.ServiceUnavailable(redacted)
            else -> ApiFailure.Malformed(redacted)
        }
        return ApiResponse.Failure(failure, quota)
    }

    private fun readMessage(body: String): String = try {
        json.decodeFromString<ApiErrorResponse>(body).msg
    } catch (e: SerializationException) {
        ""
    }

    private fun Response.readQuota(): Quota = Quota(
        freeRemaining = header(QUOTA_FREE_HEADER)?.toIntOrNull(),
        paidRemaining = header(QUOTA_PAID_HEADER)?.toIntOrNull(),
        limitPerMinute = header(RATE_LIMIT_LIMIT_HEADER)?.toIntOrNull(),
    )

    private fun TrackDto.toResolvedTrack(songId: SongId, requestedQuality: MusicQuality): ResolvedTrack? {
        val streamUrl = url?.takeIf { it.isNotBlank() } ?: return null
        val actualQuality = MusicQuality.fromApiValue(level) ?: requestedQuality
        return ResolvedTrack(
            songId = songId,
            title = name?.takeIf { it.isNotBlank() } ?: UNKNOWN_TITLE,
            artist = artist?.takeIf { it.isNotBlank() } ?: UNKNOWN_ARTIST,
            album = album?.takeIf { it.isNotBlank() },
            artworkUrl = artworkUrl?.takeIf { it.isNotBlank() },
            requestedQuality = requestedQuality,
            actualQuality = actualQuality,
            format = AudioFormat.fromUrl(streamUrl, actualQuality),
            streamUrl = streamUrl,
            sizeBytes = size,
            md5 = md5?.takeIf { it.isNotBlank() },
            // 解析接口不返回 ETag；它是直链响应的实体标签，只能由下载层在请求后补充。
            etag = null,
        )
    }

    private companion object {
        const val MUSIC_PATH = "api/163_music"
        const val SEARCH_PATH = "api/163_search"
        const val SEARCH_KEYWORD = "music"

        const val QUOTA_FREE_HEADER = "X-Quota-Free-Remaining"
        const val QUOTA_PAID_HEADER = "X-Quota-Paid-Remaining"
        const val RATE_LIMIT_LIMIT_HEADER = "X-RateLimit-Limit"
        const val RETRY_AFTER_HEADER = "Retry-After"

        const val MUSIC_NOT_FOUND_MARKER = "Music URL not found"

        const val UNKNOWN_TITLE = "未知歌曲"
        const val UNKNOWN_ARTIST = "未知艺术家"

        const val INVALID_BASE_URL_MESSAGE = "API 地址无效"
    }
}
