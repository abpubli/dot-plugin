package io.github.abpubli.dotsupport.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

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
    }
}
