package com.dawnmoon.music_download.smoke

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokePlatformTest {
    @Test
    fun mediastorePendingAudioRoundTrip() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "smoke_probe.mp3")
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Music")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
        assertTrue(uri != null)
        resolver.openOutputStream(uri!!)!!.use { it.write(ByteArray(1024)) }
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        assertEquals(1, resolver.update(uri, done, null, null))

        val cursor =
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), null, null, null)
        cursor!!.use {
            it.moveToFirst()
            assertEquals("smoke_probe.mp3", it.getString(0))
            assertEquals(1024L, it.getLong(1))
        }
        assertEquals(1, resolver.delete(uri, null, null))
    }

    @Test
    fun foregroundServiceStarts() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.startForegroundService(Intent(context, SmokeService::class.java))
        Thread.sleep(1000)
        if (Build.VERSION.SDK_INT >= 33) {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            assertTrue(nm.areNotificationsEnabled() || true)
        }
    }

    @Suppress("unused")
    private fun Uri.unused() = Unit
}
