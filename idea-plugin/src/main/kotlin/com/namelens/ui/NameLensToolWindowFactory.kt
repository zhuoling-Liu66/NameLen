package com.namelens.ui

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.namelens.cache.NameLensCache
import com.namelens.util.Jsons
import java.awt.BorderLayout
import java.util.regex.Pattern
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.RowFilter
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter

class NameLensToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = GlossaryPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "术语表", false)
        toolWindow.contentManager.addContent(content)
        // "链路图" 内容由 FlowDiagramAction 在生成时动态添加
    }
}

/** 术语表面板：读取 .namelens-cache 里 glossary 生成结果，支持过滤搜索 */
class GlossaryPanel(project: Project) : JPanel(BorderLayout()) {

    private val cache = NameLensCache.getInstance(project)

    private val tableModel = object : DefaultTableModel(
        arrayOf("标识符", "类型", "中文", "说明"), 0
    ) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val sorter = TableRowSorter<DefaultTableModel>(tableModel)
    private val table = JBTable(tableModel)
    private val status = JBLabel()

    init {
        Latest = this
        table.rowSorter = sorter
        table.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS

        val search = JTextField().apply {
            toolTipText = "搜索标识符 / 中文 / 说明"
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = applyFilter(text)
                override fun removeUpdate(e: DocumentEvent) = applyFilter(text)
                override fun changedUpdate(e: DocumentEvent) = applyFilter(text)
            })
        }
        val top = JPanel(BorderLayout(8, 0)).apply {
            add(JBLabel("搜索:"), BorderLayout.WEST)
            add(search, BorderLayout.CENTER)
            border = JBUI.Borders.empty(4)
        }
        add(top, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        reload()
    }

    private fun applyFilter(text: String) {
        sorter.rowFilter = if (text.isBlank()) null
        else RowFilter.regexFilter("(?i)" + Pattern.quote(text))
    }

    fun reload() {
        val cached = cache.get("glossary", "all")
        tableModel.setRowCount(0)
        if (cached == null) {
            status.text = "尚未生成。入口：菜单 Tools → NameLens 见名知意 → 生成仓库术语表（中英对照）"
            return
        }
        try {
            val arr = Jsons.arr(JsonParser.parseString(cached).asJsonObject, "entries")
            for (el in arr) {
                val o = el.asJsonObject
                tableModel.addRow(
                    arrayOf(
                        Jsons.str(o, "symbol"),
                        Jsons.str(o, "kind"),
                        Jsons.str(o, "chinese"),
                        Jsons.str(o, "note")
                    )
                )
            }
            status.text = "共 ${arr.size()} 条 · 同步写入 docs/namelens-glossary.md"
        } catch (e: Exception) {
            status.text = "缓存解析失败: ${e.message}"
        }
    }

    companion object {
        @Volatile
        private var Latest: GlossaryPanel? = null

        fun refreshLatest() {
            ApplicationManager.getApplication().invokeLater { Latest?.reload() }
        }
    }
}
