package io.github.abpubli.dotsupport.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.swing.JComponent
import javax.swing.JTextField

class DotSettingsConfigurable : Configurable {
    private var dotPathField: JTextField? = null

    override fun getDisplayName() = "Dot Support"

    override fun createComponent(): JComponent {
        val settings = DotSettings.getInstance()

        if (settings.dotPath.isBlank()) {
            settings.dotPath = findDotExecutable() ?: ""
        }

        dotPathField = JTextField(settings.dotPath)

        return panel {
            group("Graphviz Settings") {
                row("Path to dot:") {
                    cell(dotPathField!!).align(AlignX.FILL)
                    button("Browse...") {
                        val file = FileChooser.chooseFile(
                            FileChooserDescriptorFactory.createSingleFileDescriptor(),
                            null,
                            null
                        )
                        file?.let { dotPathField!!.text = it.path }
                    }
                }
            }
        }
    }

    private fun findDotExecutable(): String? {
        return try {
            val command = if (SystemInfo.isWindows) "where dot" else "which dot"
            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val path = reader.readLine()
            reader.close()
            if (path != null && path.isNotBlank()) path else null
        } catch (e: Exception) {
            null
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
