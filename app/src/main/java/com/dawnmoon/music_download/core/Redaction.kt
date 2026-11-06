package com.dawnmoon.music_download.core

/**
 * 把 URL 与异常文本中的敏感查询参数替换为占位符，避免 Key 进入日志或界面。
 *
 * 只处理 `apikey` 参数：值一直延伸到下一个 `&`、`#` 或空白字符。
 */
object Redaction {

    private const val MASK = "***"

    private val API_KEY_PATTERN = Regex("([?&]apikey=)[^&#\\s]*", RegexOption.IGNORE_CASE)

    /** 把 [value] 中所有 `apikey` 参数的值替换为 `***`；没有该参数时原样返回。 */
    fun redactUrl(value: String): String =
        API_KEY_PATTERN.replace(value) { match -> match.groupValues[1] + MASK }
}
