package io.github.abpubli.dotsupport.editor

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import io.github.abpubli.dotsupport.editor.preview.GraphvizPreviewPanel
import io.github.abpubli.dotsupport.filetype.DotFileType
import io.github.abpubli.dotsupport.settings.DotSettings
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.SwingUtilities

/**
 * Provides the editor for Graphviz DOT files, integrating a text editor with a preview pane.
 * Implements DumbAware to allow the editor to function during indexing.
 */
class DotFileEditorProvider : FileEditorProvider, DumbAware {

    // No logger needed in this specific class as there's no diagnostic output yet.
    // If needed in the future, uncomment the following line:
    // private static final Logger LOG = Logger.getInstance(DotFileEditorProvider.class);

    /**
     * Checks if this provider can handle the given file.
     * Returns true if the file has the DotFileType.
     */
    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file.fileType == DotFileType
    }

    private fun detectLinuxDistro(): String {
        val file = File("/etc/os-release")
        if (!file.exists()) return "linux"
        val lines = file.readLines()
        val idLine = lines.find { it.startsWith("ID=") } ?: return "linux"
        return idLine.removePrefix("ID=").replace("\"", "").trim().lowercase()
    }

    fun getInstallInstruction(): String {
        return when {
            SystemInfo.isWindows -> "Download Graphviz from:\nhttps://graphviz.org/download/"
            SystemInfo.isMac -> "Install Graphviz via Homebrew:\nbrew install graphviz"
            SystemInfo.isLinux -> when (detectLinuxDistro()) {
                "debian", "ubuntu", "mint" -> "sudo apt install graphviz"
                "fedora" -> "sudo dnf install graphviz"
                "arch", "manjaro" -> "sudo pacman -S graphviz"
                else -> "Use your distribution's package manager to install Graphviz."
            }
            else -> "Unknown OS – install Graphviz manually from https://graphviz.org/download/"
        }
    }

    /**
     * Creates the editor instance for the given DOT file.
     * Returns an instance of DotEditorWithPreview.
     */
    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val textEditor = TextEditorProvider.getInstance().createEditor(project, file) as TextEditor
        val previewPanel = GraphvizPreviewPanel()

        val dotPath = DotSettings.getInstance().dotPath.trim()
        val exists = dotPath.isNotBlank() && File(dotPath).isFile && File(dotPath).canExecute()

        if (!exists) {
            val props = PropertiesComponent.getInstance(project)
            val warningKey = "dotplugin.graphviz.warning.shown"

            if (!props.getBoolean(warningKey, false)) {
                props.setValue(warningKey, true)

                SwingUtilities.invokeLater {
                    val instruction = getInstallInstruction()
                    val result = Messages.showOkCancelDialog(
                        project,
                        "Graphviz is not installed or the path is invalid.\n\n$instruction\n\nCopy this command to your terminal.",
                        "Graphviz Installation",
                        "Copy",
                        "Close",
                        Messages.getInformationIcon()
                    )
                    if (result == Messages.OK) {
                        CopyPasteManager.getInstance().setContents(StringSelection(instruction))
                    }
                }
            }
        }

        return DotSplitEditor(textEditor, previewPanel)
    }


    /**
     * Returns the unique identifier for this editor type.
     */
    override fun getEditorTypeId(): String {
        return "dot-editor-with-preview"
    }

    /**
     * Defines the policy for opening this editor relative to the default text editor.
     * PLACE_BEFORE_DEFAULT_EDITOR makes this editor preferred but allows opening
     * the standard text editor alongside. HIDE_DEFAULT_EDITOR should not be used
     * as TextEditorWithPreview manages its own text editor component.
     */
    override fun getPolicy(): FileEditorPolicy {
        // Use PLACE_BEFORE_DEFAULT_EDITOR to make this editor preferred,
        // but without forcefully hiding other editors like the default text editor.
        // TextEditorWithPreview internally handles the text editor part.
        return FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
        // Alternatively: return FileEditorPolicy.DEFAULT
    }
}