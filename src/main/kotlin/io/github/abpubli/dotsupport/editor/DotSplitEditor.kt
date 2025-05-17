package io.github.abpubli.dotsupport.editor

import com.intellij.codeHighlighting.BackgroundEditorHighlighter
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
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
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.toolbarLayout.ToolbarLayoutStrategy
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.JBSplitter
import io.github.abpubli.dotsupport.editor.preview.GraphvizPreviewPanel
import java.awt.BorderLayout
import java.awt.Dimension
import java.beans.PropertyChangeListener
import java.util.concurrent.Future
import javax.swing.Icon
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
) : FileEditor, Disposable {

    private val splitter = JBSplitter(false, 0.5f)
    private val mainPanel = JPanel(BorderLayout())
    private var disposed = false

    private enum class ViewMode { EDITOR_ONLY, PREVIEW_ONLY, SPLIT }
    private var viewMode = ViewMode.SPLIT

    init {
        splitter.firstComponent = textEditor.component
        splitter.secondComponent = previewComponent

        val toolbar = createToolbar()
        val headerPanel = JPanel(BorderLayout())
        headerPanel.add(toolbar.component, BorderLayout.CENTER)

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
        val leftGroup = DefaultActionGroup().apply {
            add(ToggleSplitOrientationAction(splitter))
        }

        val rightGroup = DefaultActionGroup().apply {
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
        }
        val toolbarPanel = JPanel(BorderLayout())
        val leftToolbar = ActionManager.getInstance().createActionToolbar("DOT.LeftToolbar", leftGroup, true)
        val rightToolbar = ActionManager.getInstance().createActionToolbar("DOT.RightToolbar", rightGroup, true)

        leftToolbar.targetComponent = mainPanel
        rightToolbar.targetComponent = mainPanel

        toolbarPanel.add(leftToolbar.component, BorderLayout.WEST)
        toolbarPanel.add(rightToolbar.component, BorderLayout.EAST)

        return object : ActionToolbar {
            override fun getComponent(): JComponent = toolbarPanel
            override fun updateActionsImmediately() {}
            override fun getActions(): MutableList<AnAction> = mutableListOf()
            override fun setTargetComponent(component: JComponent?) {}
            override fun getPlace(): String = "DOT.CompositeToolbar"
            override fun getLayoutStrategy(): ToolbarLayoutStrategy = ToolbarLayoutStrategy.NOWRAP_STRATEGY
            override fun setLayoutStrategy(strategy: ToolbarLayoutStrategy) {}
            override fun setMinimumButtonSize(size: Dimension) {}
            override fun getMinimumButtonSize(): Dimension = Dimension(0, 0)
            override fun setOrientation(orientation: Int) {}
            override fun getOrientation(): Int = 0
            override fun getMaxButtonHeight(): Int = 0
            override fun updateActionsAsync(): java.util.concurrent.Future<*> = java.util.concurrent.CompletableFuture.completedFuture(null)
            override fun hasVisibleActions(): Boolean = true
            override fun getTargetComponent(): JComponent? = mainPanel
            override fun setMiniMode(minimalMode: Boolean) {}
            override fun getToolbarDataContext(): DataContext = DataManager.getInstance().getDataContext(mainPanel)
            override fun setReservePlaceAutoPopupIcon(reserve: Boolean) {}
            override fun isReservePlaceAutoPopupIcon(): Boolean = false
            override fun setSecondaryActionsTooltip(secondaryActionsTooltip: String) {}
            override fun setSecondaryActionsShortcut(secondaryActionsShortcut: String) {}
            override fun setSecondaryActionsIcon(icon: Icon?) {}
            override fun setSecondaryActionsIcon(icon: Icon?, hideDropdownIcon: Boolean) {}
            override fun setLayoutSecondaryActions(`val`: Boolean) {}
            override fun getActionGroup(): ActionGroup = DefaultActionGroup()
        }
    }

    private fun applyViewMode() {
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
    override fun getName(): String = "DOT Split Editor"
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
