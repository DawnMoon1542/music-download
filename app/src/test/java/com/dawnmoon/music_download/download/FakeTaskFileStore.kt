package com.dawnmoon.music_download.download

import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * 基于临时目录的 [TaskFileStore] 测试替身。
 *
 * 每个 [createPending] 条目对应一个真实文件，使大小与 MD5 校验走真实字节而不是内存计数；
 * 同时以 [isCompleted] 反映 pending 标记是否被清除，便于断言文件已对其他应用可见。
 */
class FakeTaskFileStore(private val directory: File) : TaskFileStore {

    private val files = mutableMapOf<Long, File>()
    private val displayNames = mutableMapOf<Long, String>()
    private val completed = mutableSetOf<Long>()
    private var nextId = 1L

    /**
     * 模拟写入完成后条目被系统清理：为 true 时，[openTruncate] 返回的流关闭后移除该条目，
     * 使后续 [sizeOf] 返回 -1。
     */
    var dropEntryOnWriteClose: Boolean = false

    /** 返回 [mediaStoreId] 对应的内容；条目不存在或已删除时为 null。 */
    fun contentOf(mediaStoreId: Long): ByteArray? =
        files[mediaStoreId]?.takeIf { it.exists() }?.readBytes()

    /** 条目是否仍存在。 */
    fun exists(mediaStoreId: Long): Boolean =
        files[mediaStoreId]?.exists() == true

    /** 条目是否已通过 [complete] 清除 pending 标记。 */
    fun isCompleted(mediaStoreId: Long): Boolean = mediaStoreId in completed

    fun displayNameOf(mediaStoreId: Long): String? = displayNames[mediaStoreId]

    override suspend fun createPending(displayName: String, mimeType: String): CreatedFile {
        val mediaStoreId = nextId++
        val file = File(directory, "${mediaStoreId}_$displayName").apply { writeBytes(ByteArray(0)) }
        files[mediaStoreId] = file
        displayNames[mediaStoreId] = displayName
        return CreatedFile(mediaStoreId = mediaStoreId, displayName = displayName)
    }

    override suspend fun openAppend(file: CreatedFile): OutputStream =
        FileOutputStream(fileOf(file), true)

    override suspend fun openTruncate(file: CreatedFile): OutputStream {
        val output = FileOutputStream(fileOf(file), false)
        if (!dropEntryOnWriteClose) return output
        return object : FilterOutputStream(output) {
            override fun close() {
                super.close()
                files.remove(file.mediaStoreId)?.delete()
            }
        }
    }

    override suspend fun complete(file: CreatedFile) {
        completed += file.mediaStoreId
    }

    override suspend fun delete(file: CreatedFile) {
        files.remove(file.mediaStoreId)?.delete()
        completed -= file.mediaStoreId
    }

    override suspend fun sizeOf(file: CreatedFile): Long =
        files[file.mediaStoreId]?.takeIf { it.exists() }?.length() ?: -1L

    override suspend fun md5Of(file: CreatedFile): String {
        val digest = MessageDigest.getInstance("MD5")
        fileOf(file).inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    override suspend fun deleteById(mediaStoreId: Long): Boolean {
        val file = files.remove(mediaStoreId)
        completed -= mediaStoreId
        return file?.delete() ?: true
    }

    private fun fileOf(file: CreatedFile): File =
        requireNotNull(files[file.mediaStoreId]) { "no file created for media id ${file.mediaStoreId}" }
}
