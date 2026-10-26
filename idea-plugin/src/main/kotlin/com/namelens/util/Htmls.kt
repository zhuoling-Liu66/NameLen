package com.namelens.util

/**
 * 卡片 HTML 渲染。
 *
 * 重要约束：不用 <style> 块、不用 style= 内联属性、不用任何 CSS3 属性。
 * Swing 的 CSS 解析器（CSSParser/StyleSheet.addCSSAttribute）在 IDEA 进程内对
 * 某些声明会抛 NPE（CSS$CssValue conv==null，2025.1.3/JBR21 实测，命令行无法复现），
 * 因此卡片只用 HTML3 基础标签（b/i/font/table/ul/ol/li/a/code/br/hr），
 * 彻底绕开 CSS 解析路径。
 */
object Htmls {

    fun doc(bodyHtml: String): String = "<html><body>$bodyHtml</body></html>"

    /** 区块标题（用字符模拟左侧色条） */
    fun section(title: String): String =
        "<b><font color=\"#505F79\">▎$title</font></b>"

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
