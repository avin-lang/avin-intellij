package dev.avinlang.intellij

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** The application-wide settings that decide how the Avin language server is started. */
@Service(Service.Level.APP)
@State(name = "AvinSettings", storages = [Storage("avin.xml")])
class AvinSettings : SimplePersistentStateComponent<AvinSettings.State>(State()) {
    class State : BaseState() {
        /** The avin executable, run as `<serverPath> lsp --stdio`. */
        var serverPath by string(DEFAULT_SERVER_PATH)

        /** A checkout of the Avin repository to run the server from instead; empty uses [serverPath]. */
        var repository by string("")
    }

    var serverPath: String
        get() = state.serverPath ?: DEFAULT_SERVER_PATH
        set(value) {
            state.serverPath = value
        }

    var repository: String
        get() = state.repository.orEmpty()
        set(value) {
            state.repository = value
        }

    companion object {
        fun getInstance(): AvinSettings = service()
    }
}
