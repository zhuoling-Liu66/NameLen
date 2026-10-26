package com.namelens.action

import com.google.gson.JsonObject
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.namelens.NameLensNotifications
import com.namelens.PromptLoader
import com.namelens.cache.NameLensCache
import com.namelens.git.GitCli
import com.namelens.llm.LlmClient
import com.namelens.llm.LlmException
import com.namelens.settings.NameLensSettings
import com.namelens.ui.CardPopup
import com.namelens.util.Htmls
import com.namelens.util.Jsons
import java.io.File

/**
 * 功能 D：选中一段代码 → git blame/log 相关提交 → LLM 中文解释"这段代码为什么这样写"。
 * 事实（git 输出）与推断（LLM）分区呈现，防止把编造当历史。
 */
class GitWhyAction : AnAction() {

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null && editor.selectionModel.hasSelection()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        if (!NameLensSettings.getInstance().isConfigured()) {
            NameLensNotifications.warn(project, "NameLens 未配置", "请先在 Settings → Tools → NameLens 配置 API Key")
            return
        }
        val sm = editor.selectionModel
        if (!sm.hasSelection()) {
            NameLensNotifications.warn(project, "NameLens", "请先选中一段代码再使用")
            return
        }
        val vfile = psiFile.virtualFile ?: return
        val doc = editor.document
        val startLine = doc.getLineNumber(sm.selectionStart) + 1
        val endLine = doc.getLineNumber(sm.selectionEnd) + 1
        val selectedText = sm.selectedText ?: return
        val filePath = File(vfile.path)

        object : Task.Backgroundable(project, "NameLens 正在考古这段代码 ...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val repo = GitCli.repoRoot(filePath.parentFile)
                    if (repo == null) {
                        NameLensNotifications.warn(project, "NameLens", "此文件不在任何 git 仓库中")
                        return
                    }
                    val rel = filePath.absolutePath
                        .removePrefix(repo.absolutePath).replace('\\', '/').removePrefix("/")

                    val blame = GitCli.run(repo, "blame", "-L", "$startLine,$endLine", "--date=short", "--", rel)
                        ?: "（git blame 不可用：文件可能未提交或有未保存修改）"
                    val hashes = Regex("\\(([0-9a-f]{7,40}) ")
                        .findAll(blame).map { it.groupValues[1] }.distinct().take(3).toList()

                    val commits = if (hashes.isEmpty()) {
                        "（未识别到相关提交）"
                    } else {
                        hashes.joinToString("\n\n") { h ->
                            val meta = GitCli.run(repo, "show", "-s", "--format=%h | %an | %ad | %s", "--date=short", h) ?: h
                            val patch = GitCli.run(repo, "show", "--format=", "--no-color", h, "--", rel)
                                ?.trim()?.take(3000) ?: "（无补丁信息）"
                            "提交 $meta\n该文件在此提交中的改动:\n$patch"
                        }
                    }

                    val user = buildString {
                        append("【选中的代码（").append(rel).append(" 第 ").append(startLine).append('-').append(endLine).append(" 行）】\n")
                        append(selectedText.take(3000))
                        append("\n\n【git blame 摘要】\n").append(blame.take(2500))
                        append("\n\n【相关提交】\n").append(commits)
                    }
                    val cacheKey = NameLensCache.sha256("$rel#$startLine-$endLine|${hashes.joinToString(",")}|${selectedText.hashCode()}")

                    val cache = NameLensCache.getInstance(project)
                    val html: String = cache.get(KIND, cacheKey)?.let { cached ->
                        render(LlmClient.extractJsonObject(cached), fromCache = true)
                    } ?: run {
                        val answer = LlmClient().chat(PromptLoader.load(PROMPT), user)
                        try {
                            val json = LlmClient.extractJsonObject(answer)
                            cache.put(KIND, cacheKey, json.toString())
                            render(json, fromCache = false)
                        } catch (ex: LlmException) {
                            Htmls.wrapRaw(answer)
                        }
                    }
                    CardPopup.showHtml(project, editor, "NameLens · 为什么这样写", html)
                } catch (ex: Exception) {
                    NameLensNotifications.error(project, "NameLens 考古失败", ex.message ?: ex.toString())
                }
            }
        }.queue()
    }

    private fun render(o: JsonObject, fromCache: Boolean): String {
        val b = StringBuilder()
        b.append("<b><font size=\"4\" color=\"").append(Htmls.headingColor()).append("\">")
            .append(Htmls.esc(Jsons.str(o, "summary"))).append("</font></b><br>")

        // 推断在前（用户最关心"为什么"），git 事实作为核对材料放最后
        b.append("<br>").append(Htmls.section("为什么这样写（LLM 推断）")).append("<br>")
        val inf = Jsons.arr(o, "inference")
        if (inf.size() == 0) {
            b.append(Htmls.muted("（无）")).append("<br>")
        } else {
            b.append("<ul>")
            for (el in inf) {
                val i = el.asJsonObject
                val conf = Jsons.str(i, "confidence")
                val basis = Jsons.str(i, "basis")
                b.append("<li><font color=\"").append(Htmls.confColor(conf)).append("\"><b>[").append(Htmls.esc(conf))
                    .append("]</b></font> ").append(Htmls.esc(Jsons.str(i, "guess")))
                if (basis.isNotBlank()) b.append(" ").append(Htmls.muted("（" + Htmls.esc(basis) + "）"))
                b.append("</li>")
            }
            b.append("</ul>")
            b.append(Htmls.muted("把握程度说明：[高] 有提交记录等明确依据 · [中] 有间接依据 · [低] 主要凭命名/惯例的猜测"))
                .append("<br>")
        }

        b.append("<br>").append(Htmls.section("git 事实（可核对）")).append("<br>")
        val facts = Jsons.arr(o, "facts")
        if (facts.size() == 0) {
            b.append(Htmls.muted("（无）")).append("<br>")
        } else {
            b.append("<ul>")
            for (el in facts) b.append("<li>").append(Htmls.esc(el.asJsonPrimitive.asString)).append("</li>")
            b.append("</ul>")
        }

        b.append("<br><font size=\"2\" color=\"").append(Htmls.mutedColor())
            .append("\">事实来自 git 输出；推断来自 LLM，请自行甄别")
        if (fromCache) b.append(" <font color=\"").append(Htmls.confColor("高")).append("\">[缓存]</font>")
        b.append("</font>")
        return Htmls.doc(b.toString())
    }

    companion object {
        private const val KIND = "gitwhy"
        private const val PROMPT = "/git-why.md"
    }
}
