package com.dawnmoon.music_download.data.remote

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 全局限速器：串行执行调用，并保证相邻调用的起点至少间隔 [minIntervalMillis] 毫秒。
 *
 * 串行化由 [Mutex] 保证，因此 3.2 秒的间隔同时把任意 60 秒窗口内的调用数限制在 19 次以内。
 * [clock] 与 [sleeper] 可注入，测试中使用虚拟时间即可验证限速行为而无需真实等待。
 */
class RateLimiter(
    private val minIntervalMillis: Long = 3_200,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {

    private val mutex = Mutex()

    /** 下一次允许执行的时间点；从 0 开始，使首次调用无需等待。 */
    private var nextAllowedAt = 0L

    /** 在限速器内执行 [block]，必要时先等待到允许时间。 */
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        executeLocked(block)
    }

    /**
     * 在限速器内执行 [block]；若首次结果需要惩罚，则在仍持有互斥锁时应用惩罚并重新执行
     * [block]，最后返回重试结果。
     *
     * 惩罚与重试必须与首次调用处于同一次持锁区间：否则释放锁后，已在互斥锁上排队的调用会
     * 先获得锁并按旧的允许时间发出请求，使本次 429 的 Retry-After 对全局队列失效一次。
     *
     * @param penaltyMillis 返回非 null 的惩罚毫秒数表示需要重试，null 表示无需重试。
     * @return 首次结果与可选的重试结果，便于调用方为每个响应写入额度。
     */
    suspend fun <T> runWithRetry(
        block: suspend () -> T,
        penaltyMillis: (T) -> Long?,
    ): RetryResult<T> = mutex.withLock {
        val first = executeLocked(block)
        val penalty = penaltyMillis(first) ?: return@withLock RetryResult(first, null)
        nextAllowedAt = maxOf(nextAllowedAt, clock() + penalty)
        RetryResult(first, executeLocked(block))
    }

    /** 将下一次允许执行的时间点至少推后 [retryAfterMillis] 毫秒，用于服务端 429 惩罚。 */
    suspend fun penalize(retryAfterMillis: Long) {
        mutex.withLock {
            nextAllowedAt = maxOf(nextAllowedAt, clock() + retryAfterMillis)
        }
    }

    /** 调用方必须已持有 [mutex]。 */
    private suspend fun <T> executeLocked(block: suspend () -> T): T {
        // 循环等待，避免 sleeper 提前返回导致调用起点早于允许时间。
        while (true) {
            val waitMillis = nextAllowedAt - clock()
            if (waitMillis <= 0) break
            sleeper(waitMillis)
        }
        return try {
            block()
        } finally {
            // 以执行结束时刻为基准顺延，保证窗口计数不因 block 自身耗时被低估。
            nextAllowedAt = maxOf(clock(), nextAllowedAt) + minIntervalMillis
        }
    }
}

/** [RateLimiter.runWithRetry] 的结果；[retried] 为 null 表示首次结果已经返回给调用方。 */
data class RetryResult<T>(val first: T, val retried: T?)
