package io.github.abpubli.dotsupport.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.text.isNotBlank

@State(name = "DotSettings", storages = [Storage("dot_support.xml")])
@Service
class DotSettings : PersistentStateComponent<DotSettings.State> {
    data class State(var dotPath: String = "")

    private var state = State()

    override fun getState() = state
    override fun loadState(state: State) {
        this.state = state
    }

    var dotPath: String
        get() = state.dotPath
        set(value) { state.dotPath = value }

    companion object {
        fun getInstance(): DotSettings = service()

        fun findDotExecutable(): String? {
            try {
                val command = if (SystemInfo.isWindows) "where dot" else "which dot"
                val process = ProcessBuilder(command.split(" "))
                    .redirectErrorStream(true)
                    .start()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val path = reader.readLine()
                reader.close()

                if (path != null && path.isNotBlank()) {
                    return path
                }
            } catch (_: Exception) {
                // Ignored
            }

            // Fallbacks per OS
            return when {
                SystemInfo.isWindows -> "C:\\Program Files\\Graphviz\\bin\\dot.exe"
                SystemInfo.isMac -> {
                    val homebrew = "/opt/homebrew/bin/dot"
                    val usrLocal = "/usr/local/bin/dot"
                    when {
                        File(homebrew).exists() -> homebrew
                        File(usrLocal).exists() -> usrLocal
                        else -> "/usr/local/bin/dot" // Default macOS fallback
                    }
                }
                SystemInfo.isLinux -> "/usr/bin/dot"
                else -> null
            }
        }
    }
}
