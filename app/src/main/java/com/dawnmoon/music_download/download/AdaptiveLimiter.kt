package com.dawnmoon.music_download.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * 可动态调整容量的并发门闸，供下载池限制同时在途的任务数。
 *
 * 容量由 [setPermits] 调整并始终夹在 [MIN_PERMITS]..[MAX_PERMITS] 内。调大容量会立即唤醒
 * 等待者；调小容量不打断已持有许可的任务，只约束后续获取。
 *
 * [setPermits] 与 [release] 都不是挂起函数，因此共享状态使用普通锁保护，等待方通过
 * [CompletableDeferred] 挂起，而不是使用只能以挂起方式加锁的 [kotlinx.coroutines.sync.Mutex]。
 */
class AdaptiveLimiter(initialPermits: Int) {

    private val lock = Any()

    /** 当前容量；调小后可能暂时小于 [inFlight]。 */
    private var permits = initialPermits.coerceIn(MIN_PERMITS, MAX_PERMITS)

    /** 已持有许可、尚未释放的任务数。 */
    private var inFlight = 0

    /** 按先来先到顺序排队、等待许可的挂起方。 */
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()

    /** 调整容量。调大立即生效，调小只影响后续获取。 */
    fun setPermits(count: Int) {
        synchronized(lock) {
            permits = count.coerceIn(MIN_PERMITS, MAX_PERMITS)
            grantWaitersLocked()
        }
    }

    /** 获取一个许可；容量已满时挂起，直到有任务释放或容量调大。 */
    suspend fun acquire() {
        val waiter = synchronized(lock) {
            if (inFlight < permits) {
                inFlight += 1
                null
            } else {
                CompletableDeferred<Unit>().also { waiters.addLast(it) }
            }
        } ?: return

        try {
            waiter.await()
        } catch (cancelled: CancellationException) {
            // 取消时若仍在排队则移除；若已被授予许可，则归还，避免许可泄漏。
            val stillWaiting = synchronized(lock) { waiters.remove(waiter) }
            if (!stillWaiting) release()
            throw cancelled
        }
    }

    /** 归还许可并唤醒尽可能多的等待者。多余的 release 不会使在途计数为负。 */
    fun release() {
        synchronized(lock) {
            if (inFlight > 0) inFlight -= 1
            grantWaitersLocked()
        }
    }

    /** 调用方必须持有 [lock]；在容量允许时按到达顺序授予许可。 */
    private fun grantWaitersLocked() {
        while (inFlight < permits && waiters.isNotEmpty()) {
            inFlight += 1
            waiters.removeFirst().complete(Unit)
        }
    }

    private companion object {
        const val MIN_PERMITS = 1
        const val MAX_PERMITS = 5
    }
}
