package com.dawnmoon.music_download.domain.model

/** 下载文件的音频格式。 */
enum class AudioFormat(val extension: String, val mimeType: String) {
    Mp3("mp3", "audio/mpeg"),
    Flac("flac", "audio/flac"),
    Unknown("bin", "application/octet-stream");

    companion object {
        /**
         * 优先按 URL 路径的扩展名识别；查询参数与 fragment 不参与识别。
         *
         * 扩展名缺失或无法识别时，按 [fallbackQuality] 推断：
         * 无损及以上为 flac，其余为 mp3。
         */
        fun fromUrl(url: String, fallbackQuality: MusicQuality): AudioFormat {
            val path = url.substringBefore('?').substringBefore('#')
            val extension = path.substringAfterLast('/')
                .substringAfterLast('.', missingDelimiterValue = "")
            if (extension.isEmpty()) {
                return formatForQuality(fallbackQuality)
            }
            return fromExtension(extension).takeUnless { it == Unknown }
                ?: formatForQuality(fallbackQuality)
        }

        /** 按扩展名识别格式，忽略大小写；null 或未知扩展名返回 [Unknown]。 */
        fun fromExtension(ext: String?): AudioFormat =
            when (ext?.lowercase()) {
                "mp3" -> Mp3
                "flac" -> Flac
                else -> Unknown
            }

        private fun formatForQuality(quality: MusicQuality): AudioFormat =
            when (quality) {
                MusicQuality.Jymaster,
                MusicQuality.Sky,
                MusicQuality.Jyeffect,
                MusicQuality.Hires,
                MusicQuality.Lossless,
                -> Flac

                MusicQuality.Exhigh,
                MusicQuality.Standard,
                -> Mp3
            }
    }
}
