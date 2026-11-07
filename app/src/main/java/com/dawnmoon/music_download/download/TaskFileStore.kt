package com.dawnmoon.music_download.download

import java.io.OutputStream

/**
 * 下载文件在公共存储中的读写入口。
 *
 * 实现负责把 [CreatedFile] 解析为具体存储位置；调用方只持有可持久化的媒体标识，
 * 不持有 URI 或流，避免进程重启后句柄失效。
 */
interface TaskFileStore {

    /** 在公共 Music 目录创建隐藏条目（`IS_PENDING=1`），完成后需调用 [complete]。 */
    suspend fun createPending(displayName: String, mimeType: String): CreatedFile

    /** 以追加模式打开，用于断点续传；已有字节保留。 */
    suspend fun openAppend(file: CreatedFile): OutputStream

    /** 以截断模式打开，用于从头重写。 */
    suspend fun openTruncate(file: CreatedFile): OutputStream

    /** 清除 pending 标记，使文件对其他应用可见。 */
    suspend fun complete(file: CreatedFile)

    /** 删除条目；条目已不存在不视为错误。 */
    suspend fun delete(file: CreatedFile)

    /** 返回当前字节数；条目不存在时返回 -1，供续传校准判断文件是否丢失。 */
    suspend fun sizeOf(file: CreatedFile): Long

    /** 计算文件内容的 MD5 十六进制串。 */
    suspend fun md5Of(file: CreatedFile): String

    /** 按媒体标识删除。true = 已删除或本就不存在；false = 删除失败（调用方必须保留记录）。 */
    suspend fun deleteById(mediaStoreId: Long): Boolean
}

/** 文件句柄只含持久化标识；URI 由 [TaskFileStore] 实现内部按 [mediaStoreId] 解析。 */
data class CreatedFile(val mediaStoreId: Long, val displayName: String)
