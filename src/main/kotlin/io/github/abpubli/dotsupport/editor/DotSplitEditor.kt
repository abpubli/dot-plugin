package io.github.abpubli.dotsupport.editor

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.impl.EditorHeaderComponent
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBSplitter
import io.github.abpubli.dotsupport.editor.preview.GraphvizPreviewPanel
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

private class DotEditorHeader(panel: JComponent) : EditorHeaderComponent() {
    init {
        layout = BorderLayout()
        add(panel, BorderLayout.CENTER)
    }
}

class DotSplitEditor(
    private val textEditor: TextEditor,
    private val previewComponent: JComponent
) : FileEditor {

    private val splitter = JBSplitter(false, 0.5f)
    private val mainPanel = JPanel(BorderLayout())

    init {
        splitter.firstComponent = textEditor.component
        splitter.secondComponent = previewComponent

        val toolbar = createToolbar()
        val headerPanel = JPanel(BorderLayout())
        headerPanel.add(toolbar.component, BorderLayout.WEST)

        val wrappedHeader = DotEditorHeader(headerPanel)

        wrappedHeader.add(headerPanel, BorderLayout.CENTER)

        val outerPanel = JPanel(BorderLayout())
        outerPanel.add(wrappedHeader, BorderLayout.NORTH)
        outerPanel.add(splitter, BorderLayout.CENTER)

        mainPanel.add(outerPanel, BorderLayout.CENTER)
        if (previewComponent is GraphvizPreviewPanel) {
            val document = textEditor.editor.document

            document.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
                override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                    previewComponent.triggerUpdate(document.text)
                }
            }, this)

            previewComponent.triggerUpdate(document.text, force = true)
        }
    }

    private fun createToolbar(): ActionToolbar {
        val group = DefaultActionGroup().apply {
            add(ToggleSplitOrientationAction(splitter))
            // Możesz dodać więcej akcji, np. przeładowanie preview
        }

        return ActionManager.getInstance()
            .createActionToolbar("DOT.Toolbar", group, true)
            .apply { setTargetComponent(mainPanel) }
    }

    override fun getComponent(): JComponent = mainPanel
    override fun getPreferredFocusedComponent(): JComponent? = textEditor.preferredFocusedComponent
    override fun getName(): String = "DOT Split Editor"
    override fun setState(state: FileEditorState) {
    }

    override fun dispose() {
        Disposer.dispose(textEditor)
    }
    override fun isModified(): Boolean = textEditor.isModified
    override fun isValid(): Boolean = textEditor.isValid
    private val listeners = mutableListOf<PropertyChangeListener>()

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        listeners.add(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        listeners.remove(listener)
    }

    private val userDataHolder = com.intellij.openapi.util.UserDataHolderBase()

    override fun <T : Any?> getUserData(key: Key<T?>): T? {
        return userDataHolder.getUserData(key)
    }

    override fun <T : Any?> putUserData(key: Key<T?>, value: T?) {
        userDataHolder.putUserData(key, value)
    }

    override fun getFile(): VirtualFile = textEditor.file
}
