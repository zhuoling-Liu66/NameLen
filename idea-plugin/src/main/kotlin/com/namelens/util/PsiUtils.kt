package com.namelens.util

import com.intellij.psi.PsiCall
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil

object PsiUtils {

    /**
     * 找目标方法：优先把光标/选区元素当"引用"解析到定义（调用处场景），
     * 解析不到再回退为"光标所在的方法定义"（读源码场景）。
     */
    fun targetMethod(element: PsiElement?, fallbackElement: PsiElement?): PsiMethod? {
        resolveMethodAt(element)?.let { return it }
        return fallbackElement?.let { PsiTreeUtil.getParentOfType(it, PsiMethod::class.java, false) }
    }

    private fun resolveMethodAt(start: PsiElement?): PsiMethod? {
        var cur: PsiElement? = start ?: return null
        var hops = 0
        while (cur != null && hops < 10) {
            when (cur) {
                is PsiMethod -> return cur
                is PsiCall -> cur.resolveMethod()?.let { return it }
                is PsiReferenceExpression -> (cur.resolve() as? PsiMethod)?.let { return it }
            }
            cur = cur.parent
            hops++
        }
        return null
    }

    fun methodSignature(m: PsiMethod): String {
        val sb = StringBuilder()
        m.containingClass?.let { sb.append("类: ").append(it.qualifiedName ?: it.name).append('\n') }
        m.modifierList?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { sb.append(it).append(' ') }
        sb.append(m.returnType?.presentableText ?: "void").append(' ').append(m.name).append('(')
        sb.append(m.parameterList.parameters.joinToString(", ") { p -> "${p.type.presentableText} ${p.name}" })
        sb.append(')')
        val throws = m.throwsList.referencedTypes
        if (throws.isNotEmpty()) sb.append(" throws ").append(throws.joinToString(", ") { it.presentableText })
        return sb.toString()
    }

    fun methodBody(m: PsiMethod, maxLines: Int = 200): String {
        val body = m.body?.text ?: return "无源码（来自依赖库或接口声明，仅凭签名与命名推断）"
        val lines = body.lines()
        return if (lines.size <= maxLines) body
        else lines.take(maxLines).joinToString("\n") + "\n// ...（已截断，共 ${lines.size} 行）"
    }

    fun docComment(m: PsiMethod): String {
        val doc = m.docComment?.text ?: return "无"
        return if (doc.length > 1500) doc.take(1500) + "\n*/（截断）" else doc
    }

    fun location(m: PsiMethod): String {
        return try {
            val doc = PsiDocumentManager.getInstance(m.project).getDocument(m.containingFile)
            val line = doc?.let { it.getLineNumber(m.textOffset) + 1 } ?: -1
            if (line > 0) "${m.containingFile.name}:$line" else m.containingFile.name
        } catch (e: Exception) {
            m.containingFile?.name ?: "?"
        }
    }

    fun fqName(c: PsiClass?): String = c?.qualifiedName ?: c?.name ?: "?"

    /** 目标 2024.1 的 PsiClass 没有 getOwnMethods/getOwnFields（后续版本才引入），用 containingClass 过滤出本类声明 */
    fun ownMethods(c: PsiClass): List<PsiMethod> = try {
        c.methods.filter { it.containingClass === c }
    } catch (e: Exception) {
        emptyList()
    }

    fun ownFields(c: PsiClass): List<PsiField> = try {
        c.fields.filter { it.containingClass === c }
    } catch (e: Exception) {
        emptyList()
    }
}
