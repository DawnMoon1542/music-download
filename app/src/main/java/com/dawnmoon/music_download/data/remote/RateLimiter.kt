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
        // 循环等待，避免 sleeper 提前返回导致调用起点早于允许时间。
        while (true) {
            val waitMillis = nextAllowedAt - clock()
            if (waitMillis <= 0) break
            sleeper(waitMillis)
        }
        try {
            block()
        } finally {
            // 以执行结束时刻为基准顺延，保证窗口计数不因 block 自身耗时被低估。
            nextAllowedAt = maxOf(clock(), nextAllowedAt) + minIntervalMillis
        }
    }

    /** 将下一次允许执行的时间点至少推后 [retryAfterMillis] 毫秒，用于服务端 429 惩罚。 */
    suspend fun penalize(retryAfterMillis: Long) {
        mutex.withLock {
            nextAllowedAt = maxOf(nextAllowedAt, clock() + retryAfterMillis)
        }
    }
}
