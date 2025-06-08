package io.github.abpubli.dotsupport.editor

import com.intellij.codeHighlighting.BackgroundEditorHighlighter
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.impl.EditorHeaderComponent
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBSplitter
import io.github.abpubli.dotsupport.editor.preview.GraphvizPreviewPanel
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.beans.PropertyChangeListener
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

private class DotEditorHeader(panel: JComponent) : EditorHeaderComponent() {
    init {
        layout = BorderLayout()
        add(panel, BorderLayout.CENTER)
    }
}

class DotSplitEditor(
    private val textEditor: TextEditor,
    private val previewComponent: JComponent
) : FileEditor, Disposable {

    private val splitter = JBSplitter(
        com.intellij.ide.util.PropertiesComponent.getInstance().getBoolean("dot.orientation", false),
        0.5f
    )
    private val mainPanel = JPanel(BorderLayout())
    private var disposed = false

    private enum class ViewMode { EDITOR_ONLY, PREVIEW_ONLY, SPLIT }
    private var viewMode = ViewMode.valueOf(
        com.intellij.ide.util.PropertiesComponent.getInstance().getValue("dot.viewMode", ViewMode.SPLIT.name)
    )

    private fun applyZoom(scalePercent: Float) {
        if (previewComponent is GraphvizPreviewPanel && previewComponent.isDisplayable) {
            previewComponent.showStatus("Zoom set to $scalePercent% (placeholder)")
        }
    }

    init {
        splitter.firstComponent = textEditor.component
        splitter.secondComponent = previewComponent

        val toolbar = createToolbar()
        val headerPanel = JPanel(BorderLayout())

        val zoomField = JTextField("100", 4)
        zoomField.maximumSize = Dimension(50, zoomField.preferredSize.height)
        zoomField.toolTipText = "Zoom % — press Enter to apply"

        zoomField.addActionListener {
            val text = zoomField.text.trim()
            val value = text.toFloatOrNull()
            if (value != null && value > 0f) {
                applyZoom(value)
            } else {
                zoomField.text = "100"
            }
        }

        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)
        panel.add(toolbar.component)
        panel.add(Box.createHorizontalStrut(8))
        panel.add(JLabel("Zoom:"))
        panel.add(Box.createHorizontalStrut(4))
        panel.add(zoomField)

        headerPanel.add(panel, BorderLayout.EAST)

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
                    if (!previewComponent.isDisplayable || isDisposed()) return
                    previewComponent.triggerUpdate(document.text)
                }
            }, this)

            previewComponent.triggerUpdate(document.text, force = true)
        }
        applyViewMode() // initial
    }

    override fun getBackgroundHighlighter(): BackgroundEditorHighlighter? {
        return textEditor.backgroundHighlighter
    }

    override fun getCurrentLocation(): com.intellij.openapi.fileEditor.FileEditorLocation? {
        return textEditor.currentLocation
    }

    private fun createToolbar(): ActionToolbar {
        val group = DefaultActionGroup().apply {
            add(object : DumbAwareAction("Editor Only", "Show only DOT editor", AllIcons.Actions.Edit) {
                override fun actionPerformed(e: AnActionEvent) {
                    viewMode = ViewMode.EDITOR_ONLY
                    applyViewMode()
                }
            })
            add(object : DumbAwareAction("Split View", "Show editor and preview side-by-side", AllIcons.Actions.SplitHorizontally) {
                override fun actionPerformed(e: AnActionEvent) {
                    viewMode = ViewMode.SPLIT
                    applyViewMode()
                }
            })
            add(object : DumbAwareAction("Preview Only", "Show only Graphviz preview", AllIcons.Actions.Preview) {
                override fun actionPerformed(e: AnActionEvent) {
                    viewMode = ViewMode.PREVIEW_ONLY
                    applyViewMode()
                }
            })
            addSeparator()
            add(ToggleSplitOrientationAction(splitter))
        }

        val toolbar = ActionManager.getInstance().createActionToolbar("DOT.Toolbar", group, true)
        toolbar.targetComponent = mainPanel
        toolbar.component.alignmentX = Component.RIGHT_ALIGNMENT
        return toolbar
    }

    private fun applyViewMode() {
        com.intellij.ide.util.PropertiesComponent.getInstance().setValue("dot.viewMode", viewMode.name)
        com.intellij.ide.util.PropertiesComponent.getInstance().setValue("dot.orientation", splitter.orientation)
        when (viewMode) {
            ViewMode.EDITOR_ONLY -> {
                splitter.firstComponent = textEditor.component
                splitter.secondComponent = null
            }
            ViewMode.PREVIEW_ONLY -> {
                splitter.firstComponent = null
                splitter.secondComponent = previewComponent
            }
            ViewMode.SPLIT -> {
                splitter.firstComponent = textEditor.component
                splitter.secondComponent = previewComponent
            }
        }
    }

    override fun getComponent(): JComponent = mainPanel
    override fun getPreferredFocusedComponent(): JComponent? = textEditor.preferredFocusedComponent
    override fun getName(): String = "Dot"
    override fun setState(state: FileEditorState) {
    }

    override fun dispose() {
        disposed = true
        Disposer.dispose(textEditor)
    }

    fun isDisposed(): Boolean = disposed

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

private class ToggleSplitOrientationAction (
    private val splitter: JBSplitter
) : DumbAwareAction("Toggle Orientation", "Switch preview/editor layout", AllIcons.Actions.SplitHorizontally) {

    override fun actionPerformed(e: AnActionEvent) {
        splitter.orientation = !splitter.orientation
        com.intellij.ide.util.PropertiesComponent.getInstance().setValue("dot.orientation", splitter.orientation)
    }
}
