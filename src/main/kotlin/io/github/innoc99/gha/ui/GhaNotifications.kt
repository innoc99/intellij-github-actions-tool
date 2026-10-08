package io.github.innoc99.gha.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/** plugin.xml의 notificationGroup으로 balloon 알림 표시 */
object GhaNotifications {
    private const val GROUP_ID = "GitHub Actions Tool"

    fun info(project: Project, content: String) = notify(project, content, NotificationType.INFORMATION)

    fun error(project: Project, content: String) = notify(project, content, NotificationType.ERROR)

    private fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
            .createNotification(content, type)
            .notify(project)
    }
}
