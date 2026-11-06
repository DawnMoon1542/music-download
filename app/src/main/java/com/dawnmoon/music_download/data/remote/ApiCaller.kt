package com.dawnmoon.music_download.data.remote

import com.dawnmoon.music_download.data.repository.QuotaStore

/**
 * 统一的 API 调用入口：限速、429 重试与额度快照写入。
 *
 * [ChkszApi] 实现本身不重试；所有对 API 的访问都经这里，保证全局限速与 429 处理一致。
 */
class ApiCaller(
    private val rateLimiter: RateLimiter,
    private val quotaStore: QuotaStore,
) {

    /**
     * 在限速器内执行 [request]；遇到 429 时按 Retry-After 惩罚后重试一次。
     *
     * 惩罚与重试由 [RateLimiter.runWithRetry] 在同一次持锁区间内完成，排队中的调用不会在
     * 惩罚生效前插队。重试仍失败时返回第二次结果，调用方据此把状态标记为 `RateLimited`。
     * 每次响应携带的额度都会写入 [quotaStore]，成功与失败路径一致。
     */
    suspend fun <T> call(request: suspend () -> ApiResponse<T>): ApiResponse<T> {
        val result = rateLimiter.runWithRetry(
            block = request,
            penaltyMillis = { response ->
                val failure = (response as? ApiResponse.Failure)?.failure
                if (failure is ApiFailure.RateLimited) {
                    failure.retryAfterSeconds.toPenaltyMillis()
                } else {
                    null
                }
            },
        )

        recordQuota(result.first)
        result.retried?.let { recordQuota(it) }
        return result.retried ?: result.first
    }

    private suspend fun recordQuota(response: ApiResponse<*>) {
        val quota = when (response) {
            is ApiResponse.Success -> response.quota
            is ApiResponse.Failure -> response.quota
        }
        quota?.let { quotaStore.record(it) }
    }

    private companion object {
        /** 服务端未给出 Retry-After 时的保守惩罚，避免立即重试触发持续限流。 */
        const val DEFAULT_PENALTY_MILLIS = 60_000L
        const val MILLIS_PER_SECOND = 1_000L

        fun Long?.toPenaltyMillis(): Long =
            this?.let { maxOf(it, 0L) * MILLIS_PER_SECOND } ?: DEFAULT_PENALTY_MILLIS
    }
}
