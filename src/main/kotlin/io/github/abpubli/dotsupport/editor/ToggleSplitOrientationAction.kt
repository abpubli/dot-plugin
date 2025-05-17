package io.github.abpubli.dotsupport.editor

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.JBSplitter
import com.intellij.icons.AllIcons

class ToggleSplitOrientationAction (
    private val splitter: JBSplitter
) : DumbAwareAction("Toggle Orientation", "Switch preview/editor layout", AllIcons.Actions.SplitHorizontally) {

    override fun actionPerformed(e: AnActionEvent) {
        splitter.orientation = !splitter.orientation
    }
}
