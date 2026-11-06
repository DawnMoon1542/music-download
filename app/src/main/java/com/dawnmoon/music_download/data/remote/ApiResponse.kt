package com.dawnmoon.music_download.data.remote

import com.dawnmoon.music_download.core.Redaction
import com.dawnmoon.music_download.domain.model.Quota
import java.io.IOException

/**
 * ChKSz 调用的结果封装。
 *
 * [quota] 来自响应头，成功与失败都会携带；网络层未收到响应时为 null。
 */
sealed interface ApiResponse<out T> {

    data class Success<T>(val value: T, val quota: Quota?) : ApiResponse<T>

    data class Failure(val failure: ApiFailure, val quota: Quota?) : ApiResponse<Nothing>
}

/** ChKSz 调用失败原因；[message] 保留服务端 `msg` 原文（已做 Key 脱敏）。 */
sealed interface ApiFailure {

    val message: String

    data class InvalidKey(override val message: String) : ApiFailure

    data class QuotaExhausted(override val message: String) : ApiFailure

    data class Forbidden(override val message: String) : ApiFailure

    data class RateLimited(
        val retryAfterSeconds: Long?,
        override val message: String,
    ) : ApiFailure

    data class InvalidRequest(override val message: String) : ApiFailure

    data class QualityUnavailable(override val message: String) : ApiFailure

    data class NotFound(override val message: String) : ApiFailure

    data class ServiceUnavailable(override val message: String) : ApiFailure

    data class LinkUnsupported(override val message: String) : ApiFailure

    /**
     * 网络层异常。
     *
     * 原始异常信息通常包含完整请求 URL（含 `apikey`），因此这里只保留脱敏文本与异常类型，
     * 不持有 [IOException] 本身，避免 `toString()` 或属性访问把 Key 带出去。
     */
    data class Network(
        val exceptionType: String,
        override val message: String,
    ) : ApiFailure {

        constructor(cause: IOException) : this(
            exceptionType = cause.javaClass.simpleName,
            message = Redaction.redactUrl(cause.message ?: DEFAULT_MESSAGE),
        )

        private companion object {
            const val DEFAULT_MESSAGE = "network"
        }
    }

    data class Malformed(override val message: String) : ApiFailure
}
