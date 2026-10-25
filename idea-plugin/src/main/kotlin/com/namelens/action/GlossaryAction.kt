package com.namelens.action

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.namelens.NameLensNotifications
import com.namelens.PromptLoader
import com.namelens.cache.NameLensCache
import com.namelens.llm.LlmClient
import com.namelens.llm.LlmException
import com.namelens.settings.NameLensSettings
import com.namelens.ui.GlossaryPanel
import com.namelens.util.Jsons
import com.namelens.util.PsiUtils
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

internal data class SymbolInfo(val kind: String, val name: String, val hint: String)

/**
 * 功能 B：扫描全仓库类名/接口名/高频方法名/字段名 → LLM 分批生成中英术语表。
 * 结果写入 .namelens-cache/（供工具窗口加载）与 docs/namelens-glossary.md。
 */
class GlossaryAction : AnAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (!NameLensSettings.getInstance().isConfigured()) {
            NameLensNotifications.warn(project, "NameLens 未配置", "请先在 Settings → Tools → NameLens 配置 API Key")
            return
        }
        if (DumbService.getInstance(project).isDumb) {
            NameLensNotifications.warn(project, "NameLens", "IDE 正在建立索引，请等索引完成后再生成术语表")
            return
        }

        object : Task.Backgroundable(project, "NameLens 正在生成仓库术语表 ...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                try {
                    indicator.text = "扫描项目符号 ..."
                    val symbols = ReadAction.compute<List<SymbolInfo>, RuntimeException> { scanSymbols(project, indicator) }
                    if (symbols.isEmpty()) {
                        NameLensNotifications.warn(project, "NameLens", "未扫描到 Java 类（需要项目索引完成）")
                        return
                    }

                    val batches = symbols.chunked(BATCH)
                    val entries = ArrayList<JsonObject>()
                    batches.forEachIndexed { i, batch ->
                        indicator.checkCanceled()
                        indicator.fraction = i.toDouble() / batches.size
                        indicator.text = "第 ${i + 1}/${batches.size} 批（${batch.size} 个标识符）"
                        val user = batch.joinToString("\n") { s ->
                            "- ${s.kind} ${s.name}" + if (s.hint.isBlank()) "" else " | 注释: ${s.hint}"
                        }
                        try {
                            val answer = LlmClient().chat(PromptLoader.load(PROMPT), user)
                            val arr = Jsons.arr(LlmClient.extractJsonObject(answer), "entries")
                            for (el in arr) entries.add(el.asJsonObject)
                        } catch (ex: LlmException) {
                            // 单批失败跳过，不中断整体
                        }
                    }
                    if (entries.isEmpty()) {
                        NameLensNotifications.error(project, "NameLens", "术语表生成失败：所有批次均未返回结果（检查 API Key/模型）")
                        return
                    }

                    val result = JsonObject().apply {
                        add("entries", JsonArray().also { arr -> entries.forEach { arr.add(it) } })
                    }
                    NameLensCache.getInstance(project).put("glossary", "all", result.toString())
                    writeMarkdown(project, entries)

                    ApplicationManager.getApplication().invokeLater {
                        NameLensNotifications.info(
                            project, "术语表已生成",
                            "共 ${entries.size} 条 → docs/namelens-glossary.md；工具窗口 NameLens 可搜索"
                        )
                        GlossaryPanel.refreshLatest()
                    }
                } catch (ex: Exception) {
                    NameLensNotifications.error(project, "NameLens 术语表失败", ex.message ?: ex.toString())
                }
            }
        }.queue()
    }

    private fun scanSymbols(project: Project, indicator: ProgressIndicator): List<SymbolInfo> {
        val classes = LinkedHashMap<String, SymbolInfo>()
        val methods = HashMap<String, Int>()
        val fields = LinkedHashMap<String, SymbolInfo>()

        ProjectFileIndex.getInstance(project).iterateContent { vf ->
            if (indicator.isCanceled) return@iterateContent false
            if (!vf.isValid || vf.isDirectory || !vf.name.endsWith(".java")) return@iterateContent true
            val p = vf.path.replace('\\', '/')
            if ("/build/" in p || "/out/" in p || "/target/" in p) return@iterateContent true
            val psi = try {
                PsiManager.getInstance(project).findFile(vf) as? PsiJavaFile
            } catch (e: Exception) {
                null
            } ?: return@iterateContent true
            for (cls in psi.classes) collect(cls, classes, methods, fields)
            true
        }

        val result = ArrayList<SymbolInfo>()
        result.addAll(classes.values.take(500))
        result.addAll(methods.entries.sortedWith(compareBy({ -it.value }, { it.key })).take(300)
            .map { SymbolInfo("method", it.key, "项目中出现 ${it.value} 次") })
        result.addAll(fields.values.take(200))
        return result
    }

    private fun collect(
        cls: PsiClass,
        classes: MutableMap<String, SymbolInfo>,
        methods: MutableMap<String, Int>,
        fields: MutableMap<String, SymbolInfo>,
    ) {
        val q = cls.qualifiedName ?: cls.name ?: return
        classes.putIfAbsent(q, SymbolInfo(if (cls.isInterface) "interface" else "class", q, classHint(cls)))
        for (m in PsiUtils.ownMethods(cls)) {
            val n = m.name
            if (n.length >= 3) methods.merge(n, 1, Int::plus)
        }
        for (f in PsiUtils.ownFields(cls)) {
            val n = f.name
            if (n.equals("serialVersionUID", true) || n.equals("log", true) || n.equals("logger", true)) continue
            if (n.length >= 3) fields.putIfAbsent(n, SymbolInfo("field", n, f.type.presentableText))
        }
        for (inner in cls.innerClasses) collect(inner, classes, methods, fields)
    }

    private fun classHint(cls: PsiClass): String {
        val doc = cls.docComment?.text ?: return ""
        return doc.lines().map { it.trim('*', '/', ' ') }.firstOrNull { it.isNotBlank() }?.take(80) ?: ""
    }

    private fun writeMarkdown(project: Project, entries: List<JsonObject>) {
        val base = project.basePath ?: return
        val md = buildString {
            append("# 项目术语表（NameLens 生成）\n\n")
            append("> 生成时间: ").append(LocalDateTime.now()).append("  \n")
            append("> 模型: ").append(NameLensSettings.getInstance().model).append("\n\n")
            append("| 标识符 | 类型 | 中文 | 说明 |\n|---|---|---|---|\n")
            entries.sortedWith(compareBy({ Jsons.str(it, "kind") }, { Jsons.str(it, "symbol") })).forEach { e ->
                append("| `").append(Jsons.str(e, "symbol")).append("` | ")
                    .append(Jsons.str(e, "kind")).append(" | ")
                    .append(Jsons.str(e, "chinese")).append(" | ")
                    .append(Jsons.str(e, "note")).append(" |\n")
            }
        }
        try {
            Files.createDirectories(Path.of(base, "docs"))
            Files.writeString(Path.of(base, "docs", "namelens-glossary.md"), md, StandardCharsets.UTF_8)
        } catch (ignored: Exception) {
        }
    }

    companion object {
        private const val PROMPT = "/glossary-batch.md"
        private const val BATCH = 40
    }
}
