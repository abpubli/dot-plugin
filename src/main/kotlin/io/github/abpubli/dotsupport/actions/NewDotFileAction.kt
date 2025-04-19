package io.github.abpubli.dotsupport.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import io.github.abpubli.dotsupport.editor.preview.GraphvizPreviewPanel
import io.github.abpubli.dotsupport.filetype.DotLanguage
import io.github.abpubli.dotsupport.icons.DotSupportIcons

// Use DumbAwareAction if the action should work during indexing
class NewDotFileAction : DumbAwareAction(
    "DOT File", // Text in the "New" menu
    "Creates a new Graphviz DOT file", // Description
    DotSupportIcons.DOT_ACTION_ICON // Optional icon (define your own)
) {

    private companion object {
        private val LOG = Logger.getInstance(GraphvizPreviewPanel::class.java)
        // Limit message parsing length for performance/log readability
        private const val MAX_STDERR_PARSE_LENGTH = 5000
        private const val MAX_CONCISE_MESSAGE_LENGTH = 150
    }

    override fun actionPerformed(e: AnActionEvent) {
        val dataContext = e.dataContext
        val view = LangDataKeys.IDE_VIEW.getData(dataContext) ?: return
        val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return
        // Get the directory where the action was invoked
        val directory = view.orChooseDirectory ?: return

        // --- Start: Version with a simple file ---
        // Ask the user for the file name
        val fileName = Messages.showInputDialog(
            project,
            "Enter name for the new DOT file:",
            "New DOT File",
            Messages.getQuestionIcon(),
            "new_graph", // Default name
            null // Simple validator (can add checks, e.g., if the name is not empty)
        )

        if (fileName.isNullOrBlank()) {
            return // User cancelled or didn't provide a name
        }

        val finalFileName = if (fileName.endsWith(".dot")) fileName else "$fileName.dot"

        // Sample DOT file content
        val fileContent = """
        digraph G {
            rankdir=LR; // Example attribute
            node [shape=box]; // Default node shape

            // Define nodes
            start [label="Start"];
            process [label="Process Data"];
            end [label="End"];

            // Define edges (connections)
            start -> process;
            process -> end;

            // You can add more nodes, edges, and attributes
        }
        """.trimIndent()

        // Create the file within a write action (important for VFS modifications)
        runWriteAction {
            try {
                val existingFile = directory.findFile(finalFileName)
                if (existingFile != null) {
                    Messages.showErrorDialog(project, "File '$finalFileName' already exists.", "Cannot Create File")
                    return@runWriteAction
                }

                // Create the PSI file
                val psiFile = PsiFileFactory.getInstance(project)
                    .createFileFromText(finalFileName, DotLanguage, fileContent)

                // Add the file to the directory - returns PsiElement
                val addedElement: PsiElement = directory.add(psiFile)

                // Open the file in the editor
                // Check if the added element is a PsiFile and has a non-null virtualFile
                if (addedElement is PsiFile && addedElement.virtualFile != null) {
                    // Inside this block 'addedElement' is already treated as PsiFile (smart cast)
                    FileEditorManager.getInstance(project).openFile(addedElement.virtualFile, true)

                    // Optionally: Set the cursor at a specific position
                    // val editor = FileEditorManager.getInstance(project).selectedTextEditor
                    // editor?.caretModel?.moveToOffset(...)
                } else {
                    // Optionally: Handle the case where adding failed
                    // or the returned element was not a file (unlikely here)
                    LOG.debug("Warning: Could not open added file in editor. Added element is not a PsiFile or has no VirtualFile.")
                }

            } catch (ex: Exception) {
                Messages.showErrorDialog(project, "Error creating file: ${ex.message}", "Error")
            }
        }
    }

    // This method controls when the action is visible and enabled.
    // It should only be active when the user right-clicks
    // on a directory in the project view or selects `File -> New`.
    override fun update(e: AnActionEvent) {
        val dataContext = e.dataContext
        val presentation = e.presentation

        // Check if a project is open and an IDE view context is available
        val project = CommonDataKeys.PROJECT.getData(dataContext)
        val view = LangDataKeys.IDE_VIEW.getData(dataContext)

        // Action is possible only if we have a project and a view context (directory)
        val enabled = project != null && view?.directories?.isNotEmpty() == true

        presentation.isEnabledAndVisible = enabled
    }

    // Required by AnAction in newer API versions
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT // Background Thread - suitable for VFS operations in update
    }
}