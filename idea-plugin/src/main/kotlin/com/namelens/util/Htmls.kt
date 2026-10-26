package com.namelens.util

import com.intellij.ui.JBColor

/**
 * 卡片 HTML 渲染。
 *
 * 重要约束：不用 <style> 块、不用 style= 内联属性、不用任何 CSS3 属性。
 * Swing 的 CSS 解析器（CSSParser/StyleSheet.addCSSAttribute）在 IDEA 进程内对
 * 某些声明会抛 NPE（CSS$CssValue conv==null，2025.1.3/JBR21 实测，命令行无法复现），
 * 因此卡片只用 HTML3 基础标签（b/i/font/table/ul/ol/li/a/code/br/hr），
 * 彻底绕开 CSS 解析路径。
 *
 * 颜色全部经主题深浅自适应（浅色主题用深色值、深色主题用亮色值），
 * 避免固定色值在另一类主题下与背景混在一起。
 */
object Htmls {

    private val brightTheme: Boolean = try {
        JBColor.isBright()
    } catch (e: Throwable) {
        true
    }

    private fun pick(bright: String, dark: String) = if (brightTheme) bright else dark

    /** 标题/区块色：深浅主题下都与背景有足够对比 */
    fun headingColor(): String = pick("#174ea6", "#7aaaff")

    /** 次要说明文字 */
    fun mutedColor(): String = pick("#6b778c", "#a8adbd")

    /** LLM 推断置信度：高=绿 中=橙 低=红 */
    fun confColor(level: String): String = when (level) {
        "高" -> pick("#2e7d32", "#81c784")
        "中" -> pick("#e65100", "#ffb74d")
        else -> pick("#c62828", "#ff8a80")
    }

    fun doc(bodyHtml: String): String = "<html><body>$bodyHtml</body></html>"

    /** 区块标题（用字符模拟左侧色条） */
    fun section(title: String): String =
        "<b><font color=\"" + headingColor() + "\">▎$title</font></b>"

    /** 次要说明 */
    fun muted(text: String): String = "<font color=\"" + mutedColor() + "\">$text</font>"

    fun esc(s: String?): String {
        if (s == null) return ""
        val sb = StringBuilder(s.length + 16)
        for (c in s) when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&#39;")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    /** LLM 未按约定 JSON 输出时的兜底：原文展示，不缓存 */
    fun wrapRaw(text: String): String {
        val b = StringBuilder()
        b.append(section("模型输出（未按 JSON 约定，原文展示）")).append("<br>")
        b.append("<pre>").append(esc(text)).append("</pre>")
        return doc(b.toString())
    }
}
