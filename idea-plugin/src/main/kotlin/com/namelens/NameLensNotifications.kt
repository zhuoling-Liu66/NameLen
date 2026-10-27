package com.namelens

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

object NameLensNotifications {

    private val log = Logger.getInstance("NameLens")

    fun info(project: Project?, title: String, content: String) = notify(project, title, content, NotificationType.INFORMATION)
    fun warn(project: Project?, title: String, content: String) = notify(project, title, content, NotificationType.WARNING)
    fun error(project: Project?, title: String, content: String) = notify(project, title, content, NotificationType.ERROR)

    private fun notify(project: Project?, title: String, content: String, type: NotificationType) {
        // 通知可能被用户错过，同步写 idea.log（grep "NameLens" 即可排查）
        val line = "$title | $content"
        when (type) {
            NotificationType.ERROR -> log.error(line)
            NotificationType.WARNING -> log.warn(line)
            else -> log.info(line)
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("NameLens")
            .createNotification(title, content, type)
            .notify(project)
    }
}
