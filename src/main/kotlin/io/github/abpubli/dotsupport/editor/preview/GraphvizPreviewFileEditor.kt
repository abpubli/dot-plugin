package io.github.abpubli.dotsupport.editor.preview

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * A FileEditor implementation that displays the Graphviz preview using a [GraphvizPreviewPanel].
 *
 * @param project The current project.
 * @param virtualFile The virtual file being edited or previewed.
 * @param previewPanel The UI component responsible for rendering the preview.
 */
class GraphvizPreviewFileEditor(
    private val project: Project, // Keep project reference if needed by the panel or future logic
    private val virtualFile: VirtualFile,
    /** The preview panel component. Exposed to allow external interaction, e.g., triggering updates. */
    val previewPanel: GraphvizPreviewPanel
) : UserDataHolderBase(), FileEditor {

    // Logger instance for diagnostic messages
    private companion object {
        private val LOG = Logger.getInstance(GraphvizPreviewFileEditor::class.java)
    }

    override fun getComponent(): JComponent = previewPanel
    override fun getPreferredFocusedComponent(): JComponent? = previewPanel
    override fun getName(): String = "Graphviz Preview" // Editor tab title
    override fun setState(state: FileEditorState) { /* No state to restore */
    }

    override fun isModified(): Boolean = false // Preview is read-only
    override fun isValid(): Boolean = virtualFile.isValid // Check if the backing file is still valid

    // Property change listeners are not used in this basic implementation
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun getCurrentLocation(): FileEditorLocation? = null // No specific location tracking needed for preview

    override fun dispose() {
        // This method is called by the IntelliJ Platform when the editor is disposed.
        // Note: Manual cleanup of previewPanel (e.g., using Disposer.dispose()) is NOT needed here.
        // Cleanup for the previewPanel is handled automatically by the Disposer mechanism
        // because it was registered as a child disposable (likely via Disposer.register(this, previewPanel) in the init block).
        // This method should only contain cleanup logic for resources managed *directly* by the GraphvizPreviewFileEditor class itself, if any were added in the future.
        LOG.debug("Disposing GraphvizPreviewFileEditor for ${virtualFile.name}")
    }

    override fun getFile(): VirtualFile = virtualFile

    /**
     * Triggers an update of the preview panel with the provided DOT source text.
     * This method is typically called when the content of the associated DOT file changes.
     *
     * @param dotText The new DOT source text to render in the preview.
     * @param force If true, indicates that the update should be performed even if optimizations might otherwise skip it (e.g., if text content appears unchanged). The panel implementation determines how this flag is used.
     */
    fun triggerPreviewUpdate(dotText: String, force: Boolean = false) {
        // Delegate the rendering logic to the preview panel
        previewPanel.triggerUpdate(dotText, force)
    }

    init {
        // Register the preview panel as a child disposable.
        // This ensures that previewPanel.dispose() is called automatically
        // when this FileEditor (the parent) is disposed, managing its lifecycle.
        Disposer.register(this, previewPanel)
        LOG.debug("GraphvizPreviewPanel registered as disposable child of GraphvizPreviewFileEditor")
    }

}