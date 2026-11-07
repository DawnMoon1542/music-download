package com.dawnmoon.music_download.data.repository

import com.dawnmoon.music_download.data.remote.ApiBaseUrl
import com.dawnmoon.music_download.data.remote.ApiCaller
import com.dawnmoon.music_download.data.remote.ApiFailure
import com.dawnmoon.music_download.data.remote.ApiResponse
import com.dawnmoon.music_download.data.remote.ChkszApi
import com.dawnmoon.music_download.domain.model.ApiCredentials
import com.dawnmoon.music_download.domain.model.Quota

/** 保存 API 地址与 Key 前的校验入口。 */
interface KeyValidator {

    /** 校验结果；只有 [Valid] 与 [Indeterminate] 允许保存凭据。 */
    sealed interface Result {

        data class Valid(val quota: Quota?) : Result

        /** 地址或 Key 确定无效，不应保存。 */
        data class InvalidKey(val message: String) : Result

        /** 无法判定凭据是否有效（网络、额度、限流等），凭据可能仍然可用。 */
        data class Indeterminate(val message: String) : Result
    }

    suspend fun validate(credentials: ApiCredentials): Result
}

/**
 * 先做本地格式检查，再通过 [ApiCaller] 联网校验。
 *
 * 本地检查只拦截确定非法的输入，避免无谓请求；长度不做限制，最终以服务端结果为准。
 */
class DefaultKeyValidator(
    private val api: ChkszApi,
    private val apiCaller: ApiCaller,
) : KeyValidator {

    override suspend fun validate(credentials: ApiCredentials): KeyValidator.Result {
        if (ApiBaseUrl.normalize(credentials.baseUrl) == null) {
            return KeyValidator.Result.InvalidKey(INVALID_URL_MESSAGE)
        }
        if (!API_KEY_PATTERN.matches(credentials.apiKey)) {
            return KeyValidator.Result.InvalidKey(INVALID_FORMAT_MESSAGE)
        }

        return when (val response = apiCaller.call { api.validateKey(credentials) }) {
            is ApiResponse.Success -> KeyValidator.Result.Valid(response.quota)
            is ApiResponse.Failure -> {
                val failure = response.failure
                if (failure is ApiFailure.InvalidKey) {
                    KeyValidator.Result.InvalidKey(failure.message)
                } else {
                    // 402/403/网络等错误不能证明凭据无效，因此交给调用方提示而非拒绝保存。
                    KeyValidator.Result.Indeterminate(failure.message)
                }
            }
        }
    }

    private companion object {
        /** `chksz_` 前缀 + RFC 3986 非保留字符，依据 API 文档的密钥格式。 */
        val API_KEY_PATTERN = Regex("^chksz_[A-Za-z0-9._~-]+$")

        const val INVALID_FORMAT_MESSAGE = "API Key 格式不正确"
        const val INVALID_URL_MESSAGE = "API 地址格式不正确"
    }
}
