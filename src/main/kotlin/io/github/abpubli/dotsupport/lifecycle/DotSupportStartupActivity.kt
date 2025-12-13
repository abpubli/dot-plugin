package io.github.abpubli.dotsupport.lifecycle

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.MessageBusConnection
import io.github.abpubli.dotsupport.editor.DotSplitEditor
import io.github.abpubli.dotsupport.editor.preview.GraphvizPreviewPanel
import io.github.abpubli.dotsupport.upgrade.DotPlusNotificationSettings
import io.github.abpubli.dotsupport.upgrade.DotPlusNotifier
import io.github.abpubli.dotsupport.upgrade.MarketplaceChecker
import io.github.abpubli.dotsupport.upgrade.NotificationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dot Support plugin startup activity.
 *
 * Performs:
 * 1. Subscribe to file changes for live preview updates
 * 2. Check if Dot Plus is installed (then only info)
 * 3. Check Dot Plus availability in Marketplace (once daily)
 * 4. Display notification if conditions are met
 */
class DotSupportStartupActivity : ProjectActivity {

    private val log = Logger.getInstance(DotSupportStartupActivity::class.java)

    override suspend fun execute(project: Project) {
        // Setup file change listener for live preview
        setupFileChangeListener(project)

        // Check for Dot Plus availability
        checkDotPlusAvailability(project)
    }

    private fun setupFileChangeListener(project: Project) {
        val connection: MessageBusConnection = project.messageBus.connect()

        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                for (event in events) {
                    val file = event.file ?: continue
                    if (file.extension != "dot" && file.extension != "gv") continue
                    if (event !is VFileContentChangeEvent) continue

                    ApplicationManager.getApplication().invokeLater {
                        val editorManager = FileEditorManager.getInstance(project)
                        val editors = editorManager.getEditors(file)
                        for (editor in editors) {
                            if (editor is DotSplitEditor) {
                                val text = editor.textEditor.editor.document.text
                                (editor.previewComponent as? GraphvizPreviewPanel)?.triggerUpdate(text, force = true)
                            }
                        }
                    }
                }
            }
        })
    }

    private suspend fun checkDotPlusAvailability(project: Project) {
        // Check if Dot Plus is already installed
        if (MarketplaceChecker.isDotPlusInstalled()) {
            log.info("Dot Plus is already installed, skipping update check")
            return
        }

        val settings = DotPlusNotificationSettings.getInstance()

        // Check if we should check today
        if (!settings.shouldCheckToday()) {
            log.info("Already checked today or notifications disabled, skipping")
            return
        }

        // Execute check in background
        withContext(Dispatchers.IO) {
            checkAndNotify(project, settings)
        }
    }

    private fun checkAndNotify(project: Project, settings: DotPlusNotificationSettings) {
        log.info("Checking Dot Plus availability in Marketplace...")

        val result = MarketplaceChecker.checkDotPlusAvailability()

        // Save check result
        settings.recordCheck(result.version, result.isAvailable)

        if (!result.isAvailable) {
            log.info("Dot Plus not available in Marketplace yet")
            return
        }

        if (!result.isCompatible) {
            log.info("Dot Plus available but not compatible with current IDE build")
            return
        }

        // Check if we should show notification
        val shouldShow = settings.shouldShowNotification(result.version, result.isAvailable)

        if (shouldShow) {
            val marketplaceUrl = result.pluginUrl ?: MarketplaceChecker.getMarketplaceUrl()
            
            when (settings.notificationMode) {
                NotificationMode.DAILY -> {
                    log.info("Showing daily notification about Dot Plus")
                    DotPlusNotifier.showDotPlusAvailableNotification(
                        project,
                        result.version,
                        marketplaceUrl
                    )
                }
                NotificationMode.ON_NEW_VERSION -> {
                    val oldVersion = settings.lastKnownVersion
                    if (result.version != null && oldVersion.isNotEmpty() && result.version != oldVersion) {
                        log.info("Showing new version notification: $oldVersion -> ${result.version}")
                        DotPlusNotifier.showNewVersionNotification(
                            project,
                            oldVersion,
                            result.version,
                            marketplaceUrl
                        )
                    } else if (!settings.dotPlusWasAvailable) {
                        // First detection - show standard notification
                        log.info("Showing first-time Dot Plus available notification")
                        DotPlusNotifier.showDotPlusAvailableNotification(
                            project,
                            result.version,
                            marketplaceUrl
                        )
                    }
                }
                NotificationMode.NEVER -> {
                    // Don't show
                }
            }
        }
    }
}
