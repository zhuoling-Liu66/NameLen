package com.namelens.action

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.namelens.NameLensNotifications
import com.namelens.PromptLoader
import com.namelens.cache.NameLensCache
import com.namelens.llm.LlmClient
import com.namelens.settings.NameLensSettings
import com.namelens.spring.FlowGraph
import com.namelens.spring.FlowHtml
import com.namelens.spring.FlowNode
import com.namelens.spring.SpringScanner
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * 功能 C：静态扫描 Spring 组件 → 结构图（确定性，无幻觉）；LLM 只给节点补一句语义 → Mermaid 渲染。
 * JCEF 可用则内嵌展示，否则导出 HTML 用系统浏览器打开。
 */
class FlowDiagramAction : AnAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (DumbService.getInstance(project).isDumb) {
            NameLensNotifications.warn(project, "NameLens", "IDE 正在建立索引，请等索引完成后再生成链路图")
            return
        }

        object : Task.Backgroundable(project, "NameLens 正在生成请求链路图 ...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                try {
                    indicator.text = "扫描 Spring 组件 ..."
                    val graph = ReadAction.compute<FlowGraph?, RuntimeException> { SpringScanner.scan(project, indicator) }
                    if (graph == null) {
                        NameLensNotifications.warn(
                            project, "NameLens",
                            "未识别出 Spring 组件（Controller/Filter/Interceptor/Service 均未发现）。可能不是 Spring 项目，或依赖未导入。"
                        )
                        return
                    }

                    val cache = NameLensCache.getInstance(project)
                    val settings = NameLensSettings.getInstance()
                    val cacheKey = graph.allNodes().joinToString("|") { it.id + it.label + it.detail }

                    // LLM 语义注释（可选）：结构已确定，失败不影响出图
                    var notes = HashMap<String, String>()
                    var notesCached = false
                    var model: String? = null
                    if (settings.isConfigured()) {
                        model = settings.model
                        val cached = cache.get(KIND_NOTES, cacheKey)
                        if (cached != null) {
                            notesCached = true
                            notes = parseNotes(cached)
                        } else {
                            try {
                                val user = graph.allNodes().joinToString("\n") { "- ${it.id} | ${it.kind} | ${it.label} | ${it.detail}" }
                                val answer = LlmClient().chat(PromptLoader.load(PROMPT), user)
                                val o = LlmClient.extractJsonObject(answer)
                                val result = JsonObject()
                                o.getAsJsonObject("notes")?.let { n ->
                                    for ((k, v) in n.entrySet()) {
                                        notes[k] = v.asString
                                        result.addProperty(k, v.asString)
                                    }
                                }
                                cache.put(KIND_NOTES, cacheKey, result.toString())
                            } catch (ex: Exception) {
                                NameLensNotifications.warn(project, "NameLens", "语义注释失败（不影响结构图）：${ex.message}")
                            }
                        }
                    }

                    val mermaid = SpringScanner.toMermaid(graph, notes)
                    val localMermaid = extractMermaid(cache)
                    val html = FlowHtml.build(graph, mermaid, model, notesCached, localMermaid)
                    val htmlFile = cache.writeRaw("flow.html", html)
                    val nodes = graph.allNodes()

                    ApplicationManager.getApplication().invokeLater {
                        if (project.isDisposed) return@invokeLater
                        showInToolWindow(project, htmlFile, nodes)
                        if (htmlFile != null && !JBCefApp.isSupported()) {
                            BrowserUtil.open(htmlFile.toUri().toString())
                        }
                    }
                } catch (ex: Exception) {
                    NameLensNotifications.error(project, "NameLens 链路图失败", ex.message ?: ex.toString())
                }
            }
        }.queue()
    }

    private fun parseNotes(json: String): HashMap<String, String> {
        val map = HashMap<String, String>()
        try {
            val o = JsonParser.parseString(json).asJsonObject
            for ((k, v) in o.entrySet()) map[k] = v.asString
        } catch (ignored: Exception) {
        }
        return map
    }

    private fun extractMermaid(cache: NameLensCache): Boolean = try {
        val bytes = javaClass.classLoader.getResourceAsStream("namelens/mermaid.min.js")?.readBytes()
        bytes != null && cache.writeRaw("mermaid.min.js", String(bytes, Charsets.UTF_8)) != null
    } catch (e: Exception) {
        false
    }

    private fun showInToolWindow(project: Project, htmlFile: Path?, nodes: List<FlowNode>) {
        val tw = ToolWindowManager.getInstance(project).getToolWindow("NameLens")
        if (tw == null) {
            if (htmlFile != null) BrowserUtil.open(htmlFile.toUri().toString())
            return
        }
        val cm = tw.contentManager
        cm.contents.filter { it.displayName == "链路图" }.forEach { cm.removeContent(it, true) }
        val panel = buildPanel(project, htmlFile, nodes)
        val content = ContentFactory.getInstance().createContent(panel, "链路图", false)
        cm.addContent(content)
        cm.setSelectedContent(content)
        tw.show()
    }

    private fun buildPanel(project: Project, htmlFile: Path?, nodes: List<FlowNode>): JPanel {
        val browser: JPanel = if (htmlFile != null && JBCefApp.isSupported()) {
            try {
                val jb = JBCefBrowser()
                jb.loadURL(htmlFile.toUri().toString())
                JPanel(BorderLayout()).apply { add(jb.component, BorderLayout.CENTER) }
            } catch (e: Exception) {
                fallbackPanel(htmlFile)
            }
        } else {
            fallbackPanel(htmlFile)
        }

        val listModel = DefaultListModel<String>()
        nodes.forEach { n -> listModel.addElement("${n.label}  —  ${n.file ?: "（内置/聚合节点）"}") }
        val list = JBList(listModel)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val idx = list.selectedIndex
                if (idx in nodes.indices) {
                    val n = nodes[idx]
                    n.file?.let { f ->
                        VirtualFileManager.getInstance().findFileByNioPath(Path.of(f))?.let { vf ->
                            OpenFileDescriptor(project, vf, n.offset).navigate(true)
                        }
                    }
                }
                return true
            }
        }.installOn(list)

        val south = JPanel(BorderLayout(0, 4)).apply {
            add(JBLabel("组件列表（双击跳转源码）"), BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }
        return JPanel(BorderLayout()).apply {
            add(OnePixelSplitter(false, 0.72f).apply {
                firstComponent = browser
                secondComponent = south
            }, BorderLayout.CENTER)
        }
    }

    private fun fallbackPanel(htmlFile: Path?): JPanel = JPanel(BorderLayout()).apply {
        add(JBLabel("当前 IDE 不支持内嵌浏览器（JCEF），已用系统浏览器打开"), BorderLayout.CENTER)
        if (htmlFile != null) {
            add(JButton("再次在浏览器打开").apply {
                addActionListener { BrowserUtil.open(htmlFile.toUri().toString()) }
            }, BorderLayout.SOUTH)
        }
    }

    companion object {
        private const val KIND_NOTES = "flownotes"
        private const val PROMPT = "/flow-note.md"
    }
}
