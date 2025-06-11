package io.github.abpubli.dotsupport.settings

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.abpubli.dotsupport.settings.DotSettings.Companion.findDotExecutable
import java.awt.Font
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import javax.swing.JComponent
import javax.swing.JTextField

class DotSettingsConfigurable : Configurable {
    private var dotPathField: JTextField? = null

    override fun getDisplayName() = "Dot Support"

    private fun findNearestExistingFile(path: String): VirtualFile? {
        var file = File(path.trim())

        while (!file.exists()) {
            file = file.parentFile ?: break
        }

        return if (file.exists())
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        else null
    }

    override fun createComponent(): JComponent {
        val settings = DotSettings.getInstance()

        if (settings.dotPath.isBlank()) {
            settings.dotPath = findDotExecutable() ?: ""
        }

        dotPathField = JTextField(settings.dotPath)
        val editorFont = EditorColorsManager.getInstance().globalScheme.editorFontName
        dotPathField!!.font = Font(editorFont, Font.PLAIN, dotPathField!!.font.size)

        return panel {
            group("Graphviz Settings") {
                row("Path to dot:") {
                    cell(dotPathField!!).align(AlignX.FILL)
                    button("Browse...") {
                        var currentPath = dotPathField!!.text.trim()
                        if (currentPath.isBlank())
                            currentPath = findDotExecutable() ?: ""
                        val startDirVirtual = findNearestExistingFile(currentPath)

                        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
                            .withTitle("Select Graphviz 'dot' Executable")

                        val selectedFile = FileChooser.chooseFile(
                            descriptor,
                            null,
                            startDirVirtual
                        )

                        selectedFile?.let {
                            dotPathField!!.text = it.path
                        }
                    }
                }
            }
        }
    }

    override fun isModified(): Boolean {
        return dotPathField!!.text != DotSettings.getInstance().dotPath
    }

    override fun apply() {
        DotSettings.getInstance().dotPath = dotPathField!!.text
    }

    override fun reset() {
        dotPathField!!.text = DotSettings.getInstance().dotPath
    }

    override fun disposeUIResources() {
        dotPathField = null
    }
}
