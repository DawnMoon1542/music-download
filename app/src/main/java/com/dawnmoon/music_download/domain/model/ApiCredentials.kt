package com.dawnmoon.music_download.domain.model

/**
 * 调用解析接口所需的地址与 Key；两者必须成对配置，缺一即视为未配置。
 *
 * [baseUrl] 已经过 [com.dawnmoon.music_download.data.remote.ApiBaseUrl.normalize] 规范化。
 * [toString] 不输出 Key，避免凭据随日志或异常信息泄露。
 */
data class ApiCredentials(
    val baseUrl: String,
    val apiKey: String,
) {
    override fun toString(): String = "ApiCredentials(baseUrl=$baseUrl, apiKey=***)"
}
