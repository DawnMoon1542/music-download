package com.dawnmoon.music_download.ui.common

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * 任务卡片与详情使用的显示格式化。
 *
 * 所有函数都把 `null` 视为“未知”，统一显示占位符；字节按二进制单位换算，
 * 日期按用户时区展示，且只有跨年时才补全年份。
 */
object FileInfoFormatter {

    /** 未知值的占位符。 */
    private const val UNKNOWN = "—"

    private const val BYTES_PER_KB = 1024.0

    private val UNITS = listOf("B", "KB", "MB", "GB", "TB")

    /** 把字节数格式化为二进制单位；小于 1 KB 时保留整数，其余保留一位小数。 */
    fun formatBytes(bytes: Long?): String {
        if (bytes == null) return UNKNOWN
        return formatSize(bytes.toDouble())
    }

    /** 在 [formatBytes] 的基础上追加每秒后缀。 */
    fun formatSpeed(bytesPerSecond: Long?): String {
        if (bytesPerSecond == null) return UNKNOWN
        return "${formatSize(bytesPerSecond.toDouble())}/s"
    }

    /**
     * 格式化任务时间。
     *
     * 日期与年份的排版由调用方从资源传入，避免界面层出现硬编码中文；[sameYearFormat]
     * 接收月、日、时间三个参数，[otherYearFormat] 接收年、月、日、时间四个参数。
     *
     * @param millis 要展示的时间；null 表示未知。
     * @param nowMillis 当前时间，用于判断是否需要展示年份。
     * @param sameYearFormat 同一年时使用的格式，例如 `%1$d月%2$d日 %3$s`。
     * @param otherYearFormat 不同年份时使用的格式，例如 `%1$d年%2$d月%3$d日 %4$s`。
     * @param zone 展示时区，默认取系统时区。
     */
    fun formatDate(
        millis: Long?,
        nowMillis: Long,
        sameYearFormat: String,
        otherYearFormat: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (millis == null) return UNKNOWN
        val date = Instant.ofEpochMilli(millis).atZone(zone)
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val time = String.format(Locale.US, "%02d:%02d", date.hour, date.minute)
        return if (date.year == now.year) {
            String.format(
                Locale.US,
                sameYearFormat,
                date.monthValue,
                date.dayOfMonth,
                time,
            )
        } else {
            String.format(
                Locale.US,
                otherYearFormat,
                date.year,
                date.monthValue,
                date.dayOfMonth,
                time,
            )
        }
    }

    /**
     * 计算下载进度。
     *
     * 总大小未知或非正时不提供进度，避免把“0 / 0”显示成 0%。
     */
    fun formatProgress(bytesDownloaded: Long?, totalBytes: Long?): Float? {
        if (bytesDownloaded == null || totalBytes == null || totalBytes <= 0L) return null
        return (bytesDownloaded.toDouble() / totalBytes.toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
    }

    private fun formatSize(bytes: Double): String {
        if (bytes < BYTES_PER_KB) return "${bytes.toLong()} B"

        var value = bytes
        var unitIndex = 0
        while (value >= BYTES_PER_KB && unitIndex < UNITS.lastIndex) {
            value /= BYTES_PER_KB
            unitIndex += 1
        }
        return String.format(Locale.US, "%.1f %s", value, UNITS[unitIndex])
    }
}
