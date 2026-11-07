package com.dawnmoon.music_download.download

/**
 * 下载引擎的一次性状态快照，供通知与 UI 展示。
 *
 * @property activeCount 当前处于解析或下载中的任务数。
 * @property completedInSession 本次会话已完成的任务数。
 * @property totalInSession 本次会话提交的任务总数。
 * @property bytesDone 本次会话已写入的字节数。
 * @property bytesTotal 本次会话已知的总字节数；未知时为 null。
 * @property speedBytesPerSecond 当前聚合下载速度，单位字节每秒。
 * @property isPaused 下载池是否处于暂停状态。
 */
data class EngineSnapshot(
    val activeCount: Int,
    val completedInSession: Int,
    val totalInSession: Int,
    val bytesDone: Long,
    val bytesTotal: Long?,
    val speedBytesPerSecond: Long,
    val isPaused: Boolean,
)
