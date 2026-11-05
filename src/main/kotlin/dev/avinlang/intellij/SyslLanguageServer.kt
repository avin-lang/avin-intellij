package dev.avinlang.intellij

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.EnvironmentUtil
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.CannotStartProcessException
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import java.util.concurrent.atomic.AtomicBoolean

/** The id of the Sysl language server definition in plugin.xml. */
const val SYSL_SERVER_ID = "syslLanguageServer"

/** The process that runs the Sysl language server on stdio: `sysl lsp --stdio`, with `sysl` found on the `PATH`. */
fun syslServerCommand(base: Map<String, String>): ServerCommand =
    ServerCommand("sysl", listOf("lsp", "--stdio"), base)

private val syslWarned = AtomicBoolean(false)

private fun warnSyslOnce(project: Project?, message: String) {
    if (!syslWarned.compareAndSet(false, true)) return
    NotificationGroupManager.getInstance()
        .getNotificationGroup("Avin")
        .createNotification(message, NotificationType.WARNING)
        .notify(project)
}

private fun missingSyslMessage(reason: String) =
    "Could not start the Sysl language server ('sysl': $reason). Syntax highlighting still works. Put sysl on your PATH."

/** Starts `sysl lsp --stdio` for Sysl files. */
class SyslLanguageServerFactory : LanguageServerFactory {
    override fun createConnectionProvider(project: Project): StreamConnectionProvider = SyslConnectionProvider(project)

    override fun createClientFeatures(): LSPClientFeatures = SyslClientFeatures()
}

/** Leaves the server off, with a single notification, when `sysl` cannot be found. */
class SyslClientFeatures : LSPClientFeatures() {
    override fun isEnabled(file: VirtualFile): Boolean {
        val server = syslServerCommand(EnvironmentUtil.getEnvironmentMap())
        if (findExecutable(server.command, server.env) == null) {
            warnSyslOnce(project, missingSyslMessage("not found"))
            return false
        }
        return super.isEnabled(file)
    }
}

class SyslConnectionProvider(private val project: Project) : OSProcessStreamConnectionProvider() {
    override fun start() {
        val server = syslServerCommand(EnvironmentUtil.getEnvironmentMap())
        setCommandLine(
            GeneralCommandLine(server.commandLine)
                .withEnvironment(server.env)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE),
        )
        try {
            super.start()
        } catch (e: CannotStartProcessException) {
            warnSyslOnce(project, missingSyslMessage(e.message ?: e.toString()))
            throw e
        }
    }
}
