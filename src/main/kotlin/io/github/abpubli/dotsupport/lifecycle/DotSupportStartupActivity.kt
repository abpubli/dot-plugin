package io.github.abpubli.dotsupport.lifecycle

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.util.messages.MessageBusConnection
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import io.github.abpubli.dotsupport.editor.DotSplitEditor
import io.github.abpubli.dotsupport.editor.preview.GraphvizPreviewPanel

class DotSupportStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val connection: MessageBusConnection = project.messageBus.connect()

        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                for (event in events) {
                    val file = event.file ?: continue
                    if (file.extension != "dot") continue
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
}
