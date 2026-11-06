package com.dawnmoon.music_download.data.repository

import com.dawnmoon.music_download.core.AppClock
import com.dawnmoon.music_download.data.local.QuotaDao
import com.dawnmoon.music_download.data.local.QuotaSnapshotEntity
import com.dawnmoon.music_download.domain.model.Quota
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 最近一次 API 响应中的额度快照。 */
interface QuotaStore {
    /** 最近一次快照；从未写入过时为 null。 */
    val quota: Flow<Quota?>

    /** 记录一次快照；快照中为 null 的字段沿用上一次的值。 */
    suspend fun record(quota: Quota)
}

/**
 * 基于 Room 单行表的 [QuotaStore]。
 *
 * [record] 是读取后再合并写入的复合操作，用 [mutex] 串行化以免并发写入互相覆盖。
 */
class RoomQuotaStore(
    private val dao: QuotaDao,
    private val clock: AppClock,
) : QuotaStore {

    private val mutex = Mutex()

    override val quota: Flow<Quota?> = dao.observe().map { entity ->
        entity?.let { Quota(it.freeRemaining, it.paidRemaining, it.limitPerMinute) }
    }

    override suspend fun record(quota: Quota) {
        mutex.withLock {
            val previous = dao.current()
            dao.upsert(
                QuotaSnapshotEntity(
                    freeRemaining = quota.freeRemaining ?: previous?.freeRemaining,
                    paidRemaining = quota.paidRemaining ?: previous?.paidRemaining,
                    limitPerMinute = quota.limitPerMinute ?: previous?.limitPerMinute,
                    updatedAt = clock.nowMillis(),
                ),
            )
        }
    }
}
