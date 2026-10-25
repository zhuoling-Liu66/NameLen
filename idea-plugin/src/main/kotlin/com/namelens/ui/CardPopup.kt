package com.namelens.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.ui.awt.RelativePoint
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import java.awt.Dimension
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Path
import javax.swing.JEditorPane
import javax.swing.JScrollPane
import javax.swing.event.HyperlinkEvent

/** 解释卡片的统一展示：HTML Popup + 来源链接点击跳转到定义处 */
object CardPopup {

    fun encodeLink(file: VirtualFile?, element: PsiElement?): String? {
        if (file == null) return null
        val offset = element?.textOffset ?: 0
        return URLEncoder.encode(file.path + ":" + offset, Charsets.UTF_8)
    }

    fun showHtml(project: Project, editor: Editor, title: String, html: String) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || editor.isDisposed) return@invokeLater
            val caret = editor.caretModel.currentCaret
            val pane = JEditorPane("text/html; charset=UTF-8", html)
            pane.isEditable = false
            pane.addHyperlinkListener { e ->
                if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) navigate(project, e.description)
            }
            val scroller = JScrollPane(pane)
            scroller.preferredSize = Dimension(700, 500)
            val popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(scroller, pane)
                .setTitle(title)
                .setResizable(true)
                .setMovable(true)
                .setRequestFocus(true)
                .createPopup()
            val point = editor.visualPositionToXY(caret.visualPosition)
            popup.show(RelativePoint(editor.contentComponent, point))
        }
    }

    private fun navigate(project: Project, href: String) {
        try {
            val decoded = URLDecoder.decode(href, Charsets.UTF_8)
            val idx = decoded.lastIndexOf(':')
            if (idx <= 0) return
            val path = decoded.substring(0, idx)
            val offset = decoded.substring(idx + 1).toIntOrNull() ?: 0
            val vf = VirtualFileManager.getInstance().findFileByNioPath(Path.of(path)) ?: return
            OpenFileDescriptor(project, vf, offset).navigate(true)
        } catch (ignored: Exception) {
        }
    }
}
