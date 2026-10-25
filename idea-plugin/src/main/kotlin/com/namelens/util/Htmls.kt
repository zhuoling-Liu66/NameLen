package com.namelens.util

object Htmls {

    private const val CSS = "body{font-family:'Microsoft YaHei',sans-serif;font-size:13px;color:#172B4D;margin:10px;}" +
        ".title{font-size:16px;font-weight:bold;color:#1a4fa0;margin-bottom:4px;}" +
        ".purpose{color:#42526E;margin-bottom:8px;}" +
        ".sec{font-weight:bold;color:#505F79;margin:8px 0 4px;border-left:3px solid #4c9aff;padding-left:6px;}" +
        "table{border-collapse:collapse;margin:2px 0;}" +
        "td,th{border:1px solid #dfe1e6;padding:3px 8px;text-align:left;}" +
        "td.w{font-family:Consolas,monospace;font-weight:bold;white-space:nowrap;}" +
        "ol,ul{margin:4px 0;padding-left:22px;}" +
        ".src{margin-top:10px;color:#6b778c;font-size:12px;}" +
        ".badge{background:#e3fcef;color:#006644;border-radius:3px;padding:1px 4px;font-size:11px;}" +
        ".muted{color:#6b778c;}" +
        ".hi{color:#006644;font-weight:bold;}.mid{color:#ff991f;font-weight:bold;}.lo{color:#ff5630;font-weight:bold;}"

    fun doc(bodyHtml: String): String = "<html><head><style>$CSS</style></head><body>$bodyHtml</body></html>"

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
        b.append("<div class='sec'>模型输出（未按 JSON 约定，原文展示）</div>")
        b.append("<pre style='white-space:pre-wrap'>").append(esc(text)).append("</pre>")
        return doc(b.toString())
    }
}
