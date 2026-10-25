package com.namelens.spring

import com.namelens.util.Htmls
import java.time.LocalDateTime

/** 链路图 HTML：mermaid.js 优先用本地资源（离线可用），缺失时回退 CDN */
object FlowHtml {

    fun build(g: FlowGraph, mermaid: String, model: String?, notesCached: Boolean, localMermaid: Boolean): String {
        val scriptTag = if (localMermaid) {
            "<script src=\"mermaid.min.js\"></script>"
        } else {
            "<script src=\"https://cdn.jsdelivr.net/npm/mermaid@10.9.1/dist/mermaid.min.js\"></script>"
        }
        val semantic = if (model != null) "模型 $model" + (if (notesCached) "（注释已缓存）" else "") else "未配置 API Key（仅静态结构，无语义注释）"

        val listItems = g.allNodes().joinToString("") { n ->
            "<li><b>${Htmls.esc(n.label)}</b> <span style='color:#888'>${Htmls.esc(n.kind)} · ${Htmls.esc(n.file ?: "")}</span></li>"
        }

        // mermaid 源码里防止出现 "</script>" 破坏标签（正常不会出现，防御一下）
        val safeMermaid = mermaid.replace("</script", "<\\/script")

        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<title>NameLens 链路图 - ").append(Htmls.esc(g.projectName)).append("</title>")
            append("<style>body{font-family:'Microsoft YaHei',sans-serif;margin:16px;background:#fff;color:#172B4D}")
            append("h1{font-size:18px;margin:0 0 4px}.meta{color:#667;font-size:12px;margin-bottom:10px}")
            append("pre.mermaid{display:flex;justify-content:center;background:#fafbfc;border:1px solid #eee;padding:8px}")
            append("li{margin:2px 0}</style></head><body>")
            append("<h1>NameLens · 请求链路图 — ").append(Htmls.esc(g.projectName)).append("</h1>")
            append("<div class='meta'>").append(Htmls.esc(g.extras))
                .append(" · 生成于 ").append(LocalDateTime.now())
                .append(" · 语义注释: ").append(Htmls.esc(semantic)).append("</div>")
            append("<hr/>")
            append(scriptTag)
            append("<pre class=\"mermaid\">\n").append(safeMermaid).append("\n</pre>")
            append("<details style='margin-top:12px'><summary>组件清单（双击 IDEA 工具窗口 NameLens 里的列表可直接跳转源码）</summary><ol>")
                .append(listItems).append("</ol></details>")
            append("<div style='color:#999;font-size:12px;margin-top:8px'>结构来自静态扫描（无 LLM 幻觉）；反射/AOP/动态代理断链处建议运行时用 Arthas trace 确认。</div>")
            append("<script>mermaid.initialize({startOnLoad:true,theme:'neutral',securityLevel:'strict',flowchart:{useMaxWidth:true}});</script>")
            append("</body></html>")
        }
    }
}
