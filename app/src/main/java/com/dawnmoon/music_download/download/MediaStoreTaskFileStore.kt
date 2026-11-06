package com.dawnmoon.music_download.download

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * 基于 MediaStore 的 [TaskFileStore] 实现。
 *
 * 所有文件写入公共 Music 目录并通过 `IS_PENDING` 隐藏到 [complete] 为止，因此
 * 下载中断不会在媒体库中留下可见的残缺文件。ContentResolver 的阻塞调用统一
 * 派发到 [ioDispatcher]。
 */
class MediaStoreTaskFileStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TaskFileStore {

    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val collectionUri: Uri =
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override suspend fun createPending(displayName: String, mimeType: String): CreatedFile =
        withContext(ioDispatcher) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
            }
            val uri = resolver.insert(collectionUri, values)
                ?: throw IOException("无法在 MediaStore 中创建条目：$displayName")
            CreatedFile(mediaStoreId = ContentUris.parseId(uri), displayName = displayName)
        }

    override suspend fun openAppend(file: CreatedFile): OutputStream = withContext(ioDispatcher) {
        openOutput(file, mode = "wa")
    }

    override suspend fun openTruncate(file: CreatedFile): OutputStream = withContext(ioDispatcher) {
        openOutput(file, mode = "wt")
    }

    override suspend fun complete(file: CreatedFile) {
        withContext(ioDispatcher) {
            val uri = fileUri(file.mediaStoreId)
            // 从未写入过的 pending 条目在磁盘上可能还没有文件；发布时 MediaProvider 找不到
            // 文件会连带删除该行。以追加模式打开再关闭可确保空文件存在，且不影响已有内容。
            val stream = resolver.openOutputStream(uri, "wa")
                ?: throw IOException("无法创建文件：${file.displayName}")
            stream.close()
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            val updated = resolver.update(uri, values, null, null)
            if (updated == 0) {
                throw IOException("无法发布文件：${file.displayName}")
            }
        }
    }

    override suspend fun delete(file: CreatedFile) {
        withContext(ioDispatcher) {
            val uri = fileUri(file.mediaStoreId)
            // MediaProvider 对不存在的 id 执行 delete 会抛 SecurityException，
            // 而已被清理的条目不应视为失败，因此先确认行是否存在。
            if (!exists(uri)) return@withContext
            resolver.delete(uri, null, null)
        }
    }

    override suspend fun sizeOf(file: CreatedFile): Long = withContext(ioDispatcher) {
        try {
            resolver.openFileDescriptor(fileUri(file.mediaStoreId), "r")?.use { it.statSize } ?: -1L
        } catch (_: FileNotFoundException) {
            -1L
        }
    }

    override suspend fun md5Of(file: CreatedFile): String = withContext(ioDispatcher) {
        val stream = resolver.openInputStream(fileUri(file.mediaStoreId))
            ?: throw IOException("无法读取文件：${file.displayName}")
        stream.use { digest(it) }
    }

    override suspend fun deleteById(mediaStoreId: Long): Boolean = withContext(ioDispatcher) {
        val uri = fileUri(mediaStoreId)
        // 行不存在时直接返回成功：既符合约定，也避开 MediaProvider 对未知 id 的权限拒绝。
        if (!exists(uri)) return@withContext true
        val deleted = try {
            resolver.delete(uri, null, null)
        } catch (_: SecurityException) {
            // 系统拒绝删除（例如非本应用创建且未获用户授权）时保留记录，交由调用方处理。
            return@withContext false
        }
        deleted > 0 || !exists(uri)
    }

    private fun openOutput(file: CreatedFile, mode: String): OutputStream =
        resolver.openOutputStream(fileUri(file.mediaStoreId), mode)
            ?: throw IOException("无法写入文件：${file.displayName}")

    private fun exists(uri: Uri): Boolean =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.moveToFirst() }
            ?: false

    private fun fileUri(mediaStoreId: Long): Uri =
        ContentUris.withAppendedId(collectionUri, mediaStoreId)

    private fun digest(stream: InputStream): String {
        val md = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            md.update(buffer, 0, read)
        }
        return md.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
