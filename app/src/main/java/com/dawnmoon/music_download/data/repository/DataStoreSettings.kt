package com.dawnmoon.music_download.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dawnmoon.music_download.domain.model.ApiCredentials
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 基于 Preferences DataStore 的 [AppSettings]。
 *
 * 枚举以名称字符串持久化；读取到无法识别的值时回退到默认值，避免旧版本或损坏数据导致崩溃。
 */
class DataStoreSettings(
    private val dataStore: DataStore<Preferences>,
) : AppSettings {

    override val apiCredentials: Flow<ApiCredentials?> = dataStore.data.map { preferences ->
        val baseUrl = preferences[Keys.ApiBaseUrl]?.takeIf { it.isNotBlank() }
        val apiKey = preferences[Keys.ApiKey]?.takeIf { it.isNotBlank() }
        if (baseUrl != null && apiKey != null) ApiCredentials(baseUrl, apiKey) else null
    }

    override val defaultQuality: Flow<MusicQuality> = dataStore.data.map { preferences ->
        preferences[Keys.DefaultQuality]
            ?.let { stored -> MusicQuality.entries.firstOrNull { it.name == stored } }
            ?: MusicQuality.default
    }

    override val maxConcurrentDownloads: Flow<Int> = dataStore.data.map { preferences ->
        (preferences[Keys.MaxConcurrentDownloads] ?: DEFAULT_MAX_CONCURRENT_DOWNLOADS)
            .coerceIn(MIN_CONCURRENT_DOWNLOADS, MAX_CONCURRENT_DOWNLOADS)
    }

    override val themeMode: Flow<ThemeMode> = dataStore.data.map { preferences ->
        preferences[Keys.ThemeMode]
            ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: ThemeMode.System
    }

    override val notificationAsked: Flow<Boolean> =
        dataStore.data.map { it[Keys.NotificationAsked] ?: false }

    override suspend fun setApiCredentials(value: ApiCredentials) {
        dataStore.edit {
            it[Keys.ApiBaseUrl] = value.baseUrl
            it[Keys.ApiKey] = value.apiKey
        }
    }

    override suspend fun setDefaultQuality(value: MusicQuality) {
        dataStore.edit { it[Keys.DefaultQuality] = value.name }
    }

    override suspend fun setMaxConcurrentDownloads(value: Int) {
        dataStore.edit {
            it[Keys.MaxConcurrentDownloads] = value.coerceIn(
                MIN_CONCURRENT_DOWNLOADS,
                MAX_CONCURRENT_DOWNLOADS,
            )
        }
    }

    override suspend fun setThemeMode(value: ThemeMode) {
        dataStore.edit { it[Keys.ThemeMode] = value.name }
    }

    override suspend fun markNotificationAsked() {
        dataStore.edit { it[Keys.NotificationAsked] = true }
    }

    private object Keys {
        val ApiBaseUrl = stringPreferencesKey("api_base_url")
        val ApiKey = stringPreferencesKey("api_key")
        val DefaultQuality = stringPreferencesKey("default_quality")
        val MaxConcurrentDownloads = intPreferencesKey("max_concurrent_downloads")
        val ThemeMode = stringPreferencesKey("theme_mode")
        val NotificationAsked = booleanPreferencesKey("notification_asked")
    }

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 3
        const val MIN_CONCURRENT_DOWNLOADS = 1
        const val MAX_CONCURRENT_DOWNLOADS = 5
    }
}
