package com.dawnmoon.music_download.data.repository

import com.dawnmoon.music_download.domain.model.ApiCredentials
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/** 用户设置；读取为 Flow，写入为挂起函数。 */
interface AppSettings {
    /** 已保存的 API 地址与 Key；任一项缺失时为 null。 */
    val apiCredentials: Flow<ApiCredentials?>

    /** 新任务的默认音质。 */
    val defaultQuality: Flow<MusicQuality>

    /** 同时下载的任务数。 */
    val maxConcurrentDownloads: Flow<Int>

    /** 主题模式。 */
    val themeMode: Flow<ThemeMode>

    /** 是否已经询问过通知权限。 */
    val notificationAsked: Flow<Boolean>

    /** 地址与 Key 在同一次写入中保存，避免出现只更新了一半的凭据。 */
    suspend fun setApiCredentials(value: ApiCredentials)

    suspend fun setDefaultQuality(value: MusicQuality)

    suspend fun setMaxConcurrentDownloads(value: Int)

    suspend fun setThemeMode(value: ThemeMode)

    suspend fun markNotificationAsked()
}
