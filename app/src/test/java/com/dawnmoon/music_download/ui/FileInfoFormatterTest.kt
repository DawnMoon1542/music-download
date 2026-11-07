package com.dawnmoon.music_download.ui

import com.dawnmoon.music_download.ui.common.FileInfoFormatter
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [FileInfoFormatter] 的显示规则测试。
 *
 * 只断言用户可见的格式化结果，不依赖具体实现细节。时间用例固定到同一时区，
 * 避免测试机默认时区影响日期或跨年判断。
 */
class FileInfoFormatterTest {

    @Test
    fun `bytes use binary units with one decimal place`() {
        assertEquals("0 B", FileInfoFormatter.formatBytes(0L))
        assertEquals("512 B", FileInfoFormatter.formatBytes(512L))
        assertEquals("1.5 KB", FileInfoFormatter.formatBytes(1536L))
        assertEquals("2.0 MB", FileInfoFormatter.formatBytes(2L * 1024 * 1024))
    }

    @Test
    fun `speed appends a per second suffix`() {
        assertEquals("0 B/s", FileInfoFormatter.formatSpeed(0L))
        assertEquals("2.0 MB/s", FileInfoFormatter.formatSpeed(2L * 1024 * 1024))
    }

    @Test
    fun `unknown values render a placeholder`() {
        assertEquals("—", FileInfoFormatter.formatBytes(null))
        assertEquals("—", FileInfoFormatter.formatSpeed(null))
        assertEquals(
            "—",
            FileInfoFormatter.formatDate(
                null,
                NOW_MILLIS,
                SAME_YEAR_FORMAT,
                OTHER_YEAR_FORMAT,
                ZONE,
            ),
        )
    }

    @Test
    fun `date uses month day and time within the current year`() {
        assertEquals(
            "11月7日 12:30",
            FileInfoFormatter.formatDate(
                SAME_YEAR_MILLIS,
                NOW_MILLIS,
                SAME_YEAR_FORMAT,
                OTHER_YEAR_FORMAT,
                ZONE,
            ),
        )
    }

    @Test
    fun `date includes the year when it differs from the current year`() {
        assertEquals(
            "2025年12月31日 12:30",
            FileInfoFormatter.formatDate(
                PREVIOUS_YEAR_MILLIS,
                NOW_MILLIS,
                SAME_YEAR_FORMAT,
                OTHER_YEAR_FORMAT,
                ZONE,
            ),
        )
    }

    @Test
    fun `progress is null when the total size is unknown`() {
        assertNull(FileInfoFormatter.formatProgress(1024L, null))
        assertNull(FileInfoFormatter.formatProgress(1024L, 0L))
        assertNull(FileInfoFormatter.formatProgress(null, 2048L))
    }

    @Test
    fun `progress reports the downloaded fraction`() {
        val progress = FileInfoFormatter.formatProgress(512L, 1024L)
        assertEquals(0.5f, requireNotNull(progress), 0.0001f)
    }

    private companion object {
        /** 2026-11-07 15:00（北京时间），用于判断是否跨年。 */
        val NOW_MILLIS: Long = Instant.parse("2026-11-07T07:00:00Z").toEpochMilli()

        /** 2026-11-07 12:30（北京时间）。 */
        val SAME_YEAR_MILLIS: Long = Instant.parse("2026-11-07T04:30:00Z").toEpochMilli()

        /** 2025-12-31 12:30（北京时间），相对 [NOW_MILLIS] 属于上一年。 */
        val PREVIOUS_YEAR_MILLIS: Long = Instant.parse("2025-12-31T04:30:00Z").toEpochMilli()

        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 与 strings.xml 中的日期资源保持一致，断言用户可见输出不因迁移资源而改变。 */
        const val SAME_YEAR_FORMAT = "%1\$d月%2\$d日 %3\$s"
        const val OTHER_YEAR_FORMAT = "%1\$d年%2\$d月%3\$d日 %4\$s"
    }
}
