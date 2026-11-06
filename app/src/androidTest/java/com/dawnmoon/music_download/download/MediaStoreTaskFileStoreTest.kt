package com.dawnmoon.music_download.download

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStoreTaskFileStoreTest {

    private lateinit var resolver: ContentResolver
    private lateinit var store: MediaStoreTaskFileStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        resolver = context.contentResolver
        store = MediaStoreTaskFileStore(context)
    }

    @Test
    fun pendingEntryIsHiddenMusicInPublicMusicDirectoryWithRequestedNameAndType() = runBlocking {
        val file = store.createPending("测试_a.mp3", "audio/mpeg")

        try {
            val entry = readEntry(file.mediaStoreId)

            assertNotNull("createPending 没有创建 MediaStore 行", entry)
            assertEquals("测试_a.mp3", entry?.displayName)
            assertEquals("audio/mpeg", entry?.mimeType)
            assertTrue(
                "RELATIVE_PATH 应位于公共 Music 目录，实际为 ${entry?.relativePath}",
                entry?.relativePath?.contains("Music") == true,
            )
            assertEquals("新建条目应处于 pending 状态", 1, entry?.isPending)
            assertEquals("新建条目应标记为音乐", 1, entry?.isMusic)
        } finally {
            deleteQuietly(file.mediaStoreId)
        }
    }

    @Test
    fun openAppendAddsBytesToExistingContent() = runBlocking {
        val file = store.createPending("append.mp3", "audio/mpeg")

        try {
            store.openAppend(file).use { it.write(ByteArray(1024) { 1 }) }
            assertEquals(1024L, store.sizeOf(file))

            store.openAppend(file).use { it.write(ByteArray(512) { 2 }) }
            assertEquals("第二次追加应保留已有字节", 1536L, store.sizeOf(file))
        } finally {
            deleteQuietly(file.mediaStoreId)
        }
    }

    @Test
    fun openTruncateReplacesExistingContent() = runBlocking {
        val file = store.createPending("truncate.mp3", "audio/mpeg")

        try {
            store.openAppend(file).use { it.write(ByteArray(100) { 3 }) }

            store.openTruncate(file).use { it.write(ByteArray(10) { 4 }) }

            assertEquals("openTruncate 应从空文件开始写入", 10L, store.sizeOf(file))
        } finally {
            deleteQuietly(file.mediaStoreId)
        }
    }

    @Test
    fun md5OfMatchesWrittenContent() = runBlocking {
        val file = store.createPending("md5.mp3", "audio/mpeg")

        try {
            store.openTruncate(file).use { it.write("hello".toByteArray(Charsets.UTF_8)) }

            assertEquals("5d41402abc4b2a76b9719d911017c592", store.md5Of(file))
        } finally {
            deleteQuietly(file.mediaStoreId)
        }
    }

    @Test
    fun completePublishesPendingEntry() = runBlocking {
        val file = store.createPending("complete.mp3", "audio/mpeg")

        try {
            assertEquals(1, readEntry(file.mediaStoreId)?.isPending)

            store.complete(file)

            assertEquals("完成后条目应对其他应用可见", 0, readEntry(file.mediaStoreId)?.isPending)
        } finally {
            deleteQuietly(file.mediaStoreId)
        }
    }

    @Test
    fun deleteRemovesEntryFromMediaStore() = runBlocking {
        val file = store.createPending("delete.mp3", "audio/mpeg")

        try {
            store.delete(file)

            assertFalse("delete 后条目不应再能查询到", exists(file.mediaStoreId))
        } finally {
            deleteQuietly(file.mediaStoreId)
        }
    }

    @Test
    fun deleteByIdRemovesExistingEntryAndReportsSuccess() = runBlocking {
        val file = store.createPending("delete_by_id.mp3", "audio/mpeg")

        try {
            val deleted = store.deleteById(file.mediaStoreId)

            assertTrue("删除成功应返回 true", deleted)
            assertFalse("deleteById 后条目不应再能查询到", exists(file.mediaStoreId))
        } finally {
            deleteQuietly(file.mediaStoreId)
        }
    }

    @Test
    fun deleteByIdReportsSuccessWhenEntryIsAlreadyMissing() = runBlocking {
        val missingId = Long.MAX_VALUE

        assertTrue("条目本就不存在时应返回 true", store.deleteById(missingId))
    }

    @Test
    fun sizeOfReturnsMinusOneWhenEntryIsMissing() = runBlocking {
        val missing = CreatedFile(mediaStoreId = Long.MAX_VALUE, displayName = "missing.mp3")

        assertEquals("行不存在时 sizeOf 应返回 -1，用于续传校准", -1L, store.sizeOf(missing))
    }

    @Test
    fun flacEntryKeepsFlacMimeType() = runBlocking {
        val file = store.createPending("测试_b.flac", "audio/flac")

        try {
            val entry = readEntry(file.mediaStoreId)

            assertNotNull("createPending 没有创建 MediaStore 行", entry)
            assertEquals("audio/flac", entry?.mimeType)
        } finally {
            deleteQuietly(file.mediaStoreId)
        }
    }

    private fun readEntry(mediaStoreId: Long): EntryMetadata? = query(mediaStoreId) { cursor ->
        EntryMetadata(
            displayName = cursor.stringOrNull(MediaStore.MediaColumns.DISPLAY_NAME),
            mimeType = cursor.stringOrNull(MediaStore.MediaColumns.MIME_TYPE),
            relativePath = cursor.stringOrNull(MediaStore.MediaColumns.RELATIVE_PATH),
            isPending = cursor.intValue(MediaStore.MediaColumns.IS_PENDING),
            isMusic = cursor.intValue(MediaStore.Audio.Media.IS_MUSIC),
        )
    }

    private fun <T> query(mediaStoreId: Long, read: (Cursor) -> T): T? {
        return resolver.query(audioUri(mediaStoreId), null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) read(cursor) else null
        }
    }

    private fun exists(mediaStoreId: Long): Boolean {
        return resolver.query(audioUri(mediaStoreId), null, null, null, null)?.use {
            it.moveToFirst()
        } ?: false
    }

    private fun deleteQuietly(mediaStoreId: Long) {
        // MediaProvider 对已被删除的 id 会抛 SecurityException（无法确认所有权），
        // 清理阶段条目不存在即视为成功，忽略异常以免掩盖测试断言。
        runCatching { resolver.delete(audioUri(mediaStoreId), null, null) }
    }

    private fun audioUri(mediaStoreId: Long): Uri = ContentUris.withAppendedId(
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        mediaStoreId,
    )

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.intValue(column: String): Int {
        return getInt(getColumnIndexOrThrow(column))
    }
}

private data class EntryMetadata(
    val displayName: String?,
    val mimeType: String?,
    val relativePath: String?,
    val isPending: Int,
    val isMusic: Int,
)
