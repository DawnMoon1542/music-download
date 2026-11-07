package com.dawnmoon.music_download.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dawnmoon.music_download.domain.model.ApiCredentials
import com.dawnmoon.music_download.data.repository.DataStoreSettings
import com.dawnmoon.music_download.domain.model.MusicQuality
import com.dawnmoon.music_download.domain.model.ThemeMode
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataStoreSettingsTest {

    private lateinit var scope: CoroutineScope
    private lateinit var dataStoreFile: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settings: DataStoreSettings

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStoreFile = File(context.cacheDir, "settings-${UUID.randomUUID()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { dataStoreFile }
        settings = DataStoreSettings(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
        runBlocking { scope.coroutineContext[Job]?.join() }
        dataStoreFile.delete()
    }

    @Test
    fun defaultsAreExposedBeforeAnySettingIsWritten() = runBlocking {
        assertEquals(MusicQuality.Exhigh, settings.defaultQuality.first())
        assertEquals(3, settings.maxConcurrentDownloads.first())
        assertEquals(ThemeMode.System, settings.themeMode.first())
        assertNull(settings.apiCredentials.first())
        assertFalse(settings.notificationAsked.first())
    }

    @Test
    fun aKeyWithoutABaseUrlIsNotTreatedAsConfigured() = runBlocking {
        dataStore.edit { it[stringPreferencesKey("api_key")] = "chksz_legacy_key" }

        assertNull(settings.apiCredentials.first())
    }

    @Test
    fun maxConcurrentDownloadsClampsValuesBelowRangeToOne() = runBlocking {
        settings.setMaxConcurrentDownloads(0)

        assertEquals(1, settings.maxConcurrentDownloads.first())
    }

    @Test
    fun maxConcurrentDownloadsClampsValuesAboveRangeToFive() = runBlocking {
        settings.setMaxConcurrentDownloads(9)

        assertEquals(5, settings.maxConcurrentDownloads.first())
    }

    @Test
    fun maxConcurrentDownloadsKeepsValuesInsideRange() = runBlocking {
        settings.setMaxConcurrentDownloads(3)

        assertEquals(3, settings.maxConcurrentDownloads.first())
    }

    @Test
    fun writtenSettingsAreExposedByTheirFlows() = runBlocking {
        settings.setApiCredentials(ApiCredentials("https://api.test", "chksz_test-key_123"))
        settings.setDefaultQuality(MusicQuality.Lossless)
        settings.setMaxConcurrentDownloads(4)
        settings.setThemeMode(ThemeMode.Dark)
        settings.markNotificationAsked()

        assertEquals(
            ApiCredentials("https://api.test", "chksz_test-key_123"),
            settings.apiCredentials.first(),
        )
        assertEquals(MusicQuality.Lossless, settings.defaultQuality.first())
        assertEquals(4, settings.maxConcurrentDownloads.first())
        assertEquals(ThemeMode.Dark, settings.themeMode.first())
        assertTrue(settings.notificationAsked.first())
    }
}
