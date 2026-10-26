package com.namelens.action

import com.google.gson.JsonObject
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.namelens.NameLensNotifications
import com.namelens.PromptLoader
import com.namelens.cache.NameLensCache
import com.namelens.llm.LlmClient
import com.namelens.llm.LlmException
import com.namelens.settings.NameLensSettings
import com.namelens.ui.CardPopup
import com.namelens.util.Htmls
import com.namelens.util.Jsons
import com.namelens.util.PsiUtils

/** 在 ReadAction 里一次性抽取好的待解释方法数据（离开读锁后只碰纯字符串） */
internal data class ExplainData(
    val methodName: String,
    val signature: String,
    val body: String,
    val doc: String,
    val callContext: String,
    val locationText: String,
    val locationLink: String?,
    val cacheKey: String,
)

/**
 * 功能 A（核心）：光标/选中一个方法调用（或方法定义）→ LLM 中文解释卡片：
 * 命名逐词拆解、入参、返回值、作用，来源可点击跳转，命中缓存秒出。
 */
class ExplainAction : AnAction() {

    override fun update(e: AnActionEvent) {
        val has = e.getData(CommonDataKeys.EDITOR) != null && e.getData(CommonDataKeys.PSI_FILE) != null
        e.presentation.isEnabledAndVisible = has
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        if (!NameLensSettings.getInstance().isConfigured()) {
            NameLensNotifications.warn(
                project, "NameLens 未配置",
                "请先在 Settings → Tools → NameLens 填写 API Key（智谱 GLM / DeepSeek / Ollama 等 OpenAI 兼容服务）"
            )
            return
        }

        val data = ReadAction.compute<ExplainData?, RuntimeException> { locate(editor, psiFile) }
        if (data == null) {
            NameLensNotifications.warn(project, "NameLens 未找到方法", "请把光标放在方法调用或方法定义上再试（也可先选中一段调用代码）")
            return
        }

        object : Task.Backgroundable(project, "NameLens 正在解释 ${data.methodName} ...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val cache = NameLensCache.getInstance(project)
                try {
                    val html: String = cache.get(KIND, data.cacheKey)?.let { cached ->
                        render(data, LlmClient.extractJsonObject(cached), fromCache = true)
                    } ?: run {
                        val answer = LlmClient().chat(PromptLoader.load(PROMPT), buildUserPrompt(data))
                        try {
                            val json = LlmClient.extractJsonObject(answer)
                            cache.put(KIND, data.cacheKey, json.toString())
                            render(data, json, fromCache = false)
                        } catch (ex: LlmException) {
                            Htmls.wrapRaw(answer)
                        }
                    }
                    CardPopup.showHtml(project, editor, "NameLens · ${data.methodName}", html)
                } catch (ex: Exception) {
                    NameLensNotifications.error(project, "NameLens 解释失败", ex.message ?: ex.toString())
                }
            }
        }.queue()
    }

    private fun locate(editor: Editor, psiFile: PsiFile): ExplainData? {
        val sm = editor.selectionModel
        val baseOffset = if (sm.hasSelection()) sm.selectionStart else editor.caretModel.offset
        val el = psiFile.findElementAt(baseOffset)
            ?: (if (baseOffset > 0) psiFile.findElementAt(baseOffset - 1) else null)
            ?: return null
        val fallbackEl = psiFile.findElementAt(editor.caretModel.offset) ?: el
        val method: PsiMethod = PsiUtils.targetMethod(el, fallbackEl) ?: return null

        val signature = PsiUtils.methodSignature(method)
        val body = PsiUtils.methodBody(method)
        return ExplainData(
            methodName = method.name,
            signature = signature,
            body = body,
            doc = PsiUtils.docComment(method),
            callContext = callContext(editor),
            locationText = PsiUtils.location(method),
            locationLink = CardPopup.encodeLink(method.containingFile?.virtualFile, method),
            cacheKey = NameLensCache.sha256("$signature|${method.containingClass?.qualifiedName}|${body.hashCode()}")
        )
    }

    private fun callContext(editor: Editor): String = try {
        val doc = editor.document
        val line = doc.getLineNumber(editor.caretModel.offset)
        val from = maxOf(0, line - 3)
        val to = minOf(doc.lineCount - 1, line + 3)
        (from..to).joinToString("\n") {
            doc.getText(TextRange(doc.getLineStartOffset(it), doc.getLineEndOffset(it)))
        }
    } catch (e: Exception) {
        ""
    }

    private fun buildUserPrompt(d: ExplainData): String = buildString {
        append("【调用处上下文（用户光标附近的代码）】\n")
        append(d.callContext.ifBlank { "（无）" })
        append("\n\n【方法签名】\n").append(d.signature)
        append("\n\n【已有注释】\n").append(d.doc)
        append("\n\n【方法体】\n").append(d.body)
    }

    private fun render(d: ExplainData, o: JsonObject, fromCache: Boolean): String {
        val b = StringBuilder()
        b.append("<b><font size=\"4\" color=\"#1a4fa0\">").append(Htmls.esc(d.methodName)).append("</font></b><br>")
        b.append("<font color=\"#42526E\">").append(Htmls.esc(Jsons.str(o, "purpose"))).append("</font><br>")

        b.append("<br>").append(Htmls.section("命名拆解")).append("<br>")
        val words = Jsons.arr(o, "nameWords")
        if (words.size() == 0) {
            b.append("<font color=\"#6b778c\">（无）</font><br>")
        } else {
            for (el in words) {
                val w = el.asJsonObject
                b.append("<code><b>").append(Htmls.esc(Jsons.str(w, "word")))
                    .append("</b></code>  ").append(Htmls.esc(Jsons.str(w, "meaning"))).append("<br>")
            }
        }

        b.append("<br>").append(Htmls.section("入参")).append("<br>")
        val params = Jsons.arr(o, "params")
        if (params.size() == 0) {
            b.append("无参数<br>")
        } else {
            for (el in params) {
                val p = el.asJsonObject
                b.append("<code><b>").append(Htmls.esc(Jsons.str(p, "name"))).append("</b></code>")
                    .append(" <i>").append(Htmls.esc(Jsons.str(p, "type"))).append("</i>  ")
                    .append(Htmls.esc(Jsons.str(p, "meaning"))).append("<br>")
            }
        }

        b.append("<br>").append(Htmls.section("返回值")).append("<br>")
        b.append(Htmls.esc(Jsons.str(o, "returns")).ifBlank { "（无说明）" }).append("<br>")

        val steps = Jsons.arr(o, "steps")
        if (steps.size() > 0) {
            b.append("<br>").append(Htmls.section("实现要点")).append("<ol>")
            for (el in steps) b.append("<li>").append(Htmls.esc(el.asJsonPrimitive.asString)).append("</li>")
            b.append("</ol>")
        }

        val notes = Jsons.str(o, "notes")
        if (notes.isNotBlank()) {
            b.append("<br>").append(Htmls.section("备注")).append("<br>").append(Htmls.esc(notes)).append("<br>")
        }

        b.append("<br><font size=\"2\" color=\"#6b778c\">来源: ")
        if (d.locationLink != null) {
            b.append("<a href='").append(d.locationLink).append("'>").append(Htmls.esc(d.locationText)).append("</a>")
        } else {
            b.append(Htmls.esc(d.locationText))
        }
        if (fromCache) b.append(" <font color=\"#006644\">[缓存]</font>")
        b.append("</font>")
        return Htmls.doc(b.toString())
    }

    companion object {
        private const val KIND = "explain"
        private const val PROMPT = "/explain-method.md"
    }
}
