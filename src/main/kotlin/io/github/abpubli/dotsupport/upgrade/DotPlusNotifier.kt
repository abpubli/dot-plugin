package io.github.abpubli.dotsupport.upgrade

import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.installAndEnable

/**
 * Manages notifications about Dot Plus availability
 */
object DotPlusNotifier {

    private const val NOTIFICATION_GROUP = "Dot Support Notifications"

    /**
     * Shows notification about Dot Plus availability
     */
    fun showDotPlusAvailableNotification(
        project: Project?,
        version: String?,
        marketplaceUrl: String
    ) {
        val settings = DotPlusNotificationSettings.getInstance()
        
        val versionText = version?.let { " (v$it)" } ?: ""
        val title = "Dot Plus Available$versionText"
        val content = buildString {
            append("<b>Dot Plus</b> — premium edition with advanced features:<br/>")
            append("• Syntax highlighting<br/>")
            append("• Code folding<br/>")
            append("• Find usages<br/>")
            append("• Multiple rendering engines<br/>")
            append("• And more!<br/><br/>")
            append("<i>Free 30-day trial available.</i>")
        }

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, NotificationType.INFORMATION)

        // Action: Open in Marketplace
        notification.addAction(object : NotificationAction("View in Marketplace") {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                BrowserUtil.browse(marketplaceUrl)
                notification.expire()
            }
        })

        // Action: Remind on new version
        if (settings.notificationMode == NotificationMode.DAILY) {
            notification.addAction(object : NotificationAction("Only on New Version") {
                override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                    settings.notificationMode = NotificationMode.ON_NEW_VERSION
                    notification.expire()
                    showConfirmation(project, "You'll be notified only when a new version is released.")
                }
            })
        }

        // Action: Don't show anymore
        notification.addAction(object : NotificationAction("Don't Show Again") {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                settings.notificationMode = NotificationMode.NEVER
                notification.expire()
                showConfirmation(project, "Dot Plus notifications disabled. You can re-enable them in Settings → Tools → Dot Support.")
            }
        })

        notification.notify(project)
    }

    /**
     * Shows notification about new Dot Plus version
     */
    fun showNewVersionNotification(
        project: Project?,
        oldVersion: String,
        newVersion: String,
        marketplaceUrl: String
    ) {
        val title = "Dot Plus Updated to v$newVersion"
        val content = "A new version of <b>Dot Plus</b> is available (was v$oldVersion)."

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, NotificationType.INFORMATION)

        notification.addAction(object : NotificationAction("View Changes") {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                BrowserUtil.browse(marketplaceUrl)
                notification.expire()
            }
        })

        notification.addAction(object : NotificationAction("Don't Show Again") {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                DotPlusNotificationSettings.getInstance().notificationMode = NotificationMode.NEVER
                notification.expire()
            }
        })

        notification.notify(project)
    }

    /**
     * Shows settings change confirmation
     */
    private fun showConfirmation(project: Project?, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Settings Updated", message, NotificationType.INFORMATION)
            .notify(project)
    }

    /**
     * Shows notification that Dot Plus is already installed
     * (for the case when user installed Plus but Dot Support is still running)
     */
    fun showAlreadyInstalledInfo(project: Project?) {
        val content = "You have <b>Dot Plus</b> installed. " +
                "You can disable <b>Dot Support</b> as Dot Plus includes all its features."

        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Dot Plus Detected", content, NotificationType.INFORMATION)
            .notify(project)
    }
}
