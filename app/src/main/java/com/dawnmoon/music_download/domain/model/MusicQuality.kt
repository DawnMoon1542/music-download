package com.dawnmoon.music_download.domain.model

/**
 * ChKSz API 的音质等级，声明顺序从低到高。
 *
 * 降级顺序依赖声明顺序：请求音质返回 404 时，按从高到低的顺序逐级重试。
 */
enum class MusicQuality(val apiValue: String, val label: String) {
    Standard("standard", "标准"),
    Exhigh("exhigh", "极高"),
    Lossless("lossless", "无损"),
    Hires("hires", "Hi-Res"),
    Jyeffect("jyeffect", "沉浸环绕"),
    Sky("sky", "沉浸天空"),
    Jymaster("jymaster", "超清母带");

    /** 从当前音质到 [Standard] 的降级顺序，包含自身。 */
    fun downgradePath(): List<MusicQuality> =
        entries.reversed().dropWhile { it != this }

    companion object {
        /** 新任务的默认音质。 */
        val default: MusicQuality = Exhigh

        /** 将 API 的 level 取值映射回音质；未知或缺失时返回 null。 */
        fun fromApiValue(value: String?): MusicQuality? =
            entries.firstOrNull { it.apiValue == value }
    }
}
