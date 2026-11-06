package com.dawnmoon.music_download.data.repository

import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/** 用户设置；读取为 Flow，写入为挂起函数。 */
interface AppSettings {
    /** 已保存的 API Key；从未保存时为 null。 */
    val apiKey: Flow<String?>

    /** 新任务的默认音质。 */
    val defaultQuality: Flow<MusicQuality>

    /** 同时下载的任务数。 */
    val maxConcurrentDownloads: Flow<Int>

    /** 主题模式。 */
    val themeMode: Flow<ThemeMode>

    /** 是否已经询问过通知权限。 */
    val notificationAsked: Flow<Boolean>

    suspend fun setApiKey(value: String)

    suspend fun setDefaultQuality(value: MusicQuality)

    suspend fun setMaxConcurrentDownloads(value: Int)

    suspend fun setThemeMode(value: ThemeMode)

    suspend fun markNotificationAsked()
}
