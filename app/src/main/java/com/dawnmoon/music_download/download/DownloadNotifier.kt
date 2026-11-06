package com.dawnmoon.music_download.download

/** 下载进度通知的发布者；由引擎调用，实现可替换为测试 Fake。 */
interface DownloadNotifier {
    /** 发布或更新汇总通知。 */
    fun update(snapshot: EngineSnapshot, paused: Boolean)

    /** 取消汇总通知。 */
    fun clear()
}
