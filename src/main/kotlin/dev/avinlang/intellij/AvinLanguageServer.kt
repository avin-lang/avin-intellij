package dev.avinlang.intellij

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.EnvironmentUtil
import com.redhat.devtools.lsp4ij.LanguageServerFactory
import com.redhat.devtools.lsp4ij.LanguageServerManager
import com.redhat.devtools.lsp4ij.ServerStatus
import com.redhat.devtools.lsp4ij.client.features.LSPClientFeatures
import com.redhat.devtools.lsp4ij.server.CannotStartProcessException
import com.redhat.devtools.lsp4ij.server.OSProcessStreamConnectionProvider
import com.redhat.devtools.lsp4ij.server.StreamConnectionProvider
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** The id of the language server definition in plugin.xml. */
const val AVIN_SERVER_ID = "avinLanguageServer"

/** Builds the server's command line from the current settings and the user's shell environment. */
internal fun currentServerCommand(): ServerCommand {
    val settings = AvinSettings.getInstance()
    return serverCommand(settings.serverPath, settings.repository, EnvironmentUtil.getEnvironmentMap())
}

/**
 * Says once that the server could not be started. It is said again only after the settings change,
 * so an unavailable server costs one notification rather than one per file opened.
 */
internal object ServerWarning {
    private val warned = AtomicBoolean(false)

    fun warnOnce(project: Project?, message: String) {
        if (!warned.compareAndSet(false, true)) return
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Avin")
            .createNotification(message, NotificationType.WARNING)
            .notify(project)
    }

    fun reset() {
        warned.set(false)
    }
}

/** Starts `avin lsp --stdio`, or `sysl run <repository>/compiler -- lsp --stdio`, for Avin files. */
class AvinLanguageServerFactory : LanguageServerFactory {
    override fun createConnectionProvider(project: Project): StreamConnectionProvider = AvinConnectionProvider(project)

    override fun createClientFeatures(): LSPClientFeatures = AvinClientFeatures()
}

/**
 * Leaves the server off, with a single notification, when its executable cannot be found; the
 * TextMate highlighting does not depend on it.
 */
class AvinClientFeatures : LSPClientFeatures() {
    override fun isEnabled(file: VirtualFile): Boolean {
        val server = currentServerCommand()
        if (findExecutable(server.command, server.env, server.cwd) == null) {
            ServerWarning.warnOnce(project, missingServerMessage(server, "not found"))
            return false
        }
        return super.isEnabled(file)
    }
}

class AvinConnectionProvider(private val project: Project) : OSProcessStreamConnectionProvider() {
    override fun start() {
        val server = currentServerCommand()
        val commandLine = GeneralCommandLine(server.commandLine)
            .withEnvironment(server.env)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
        server.cwd?.let { commandLine.withWorkDirectory(File(it)) }
        setCommandLine(commandLine)
        try {
            super.start()
        } catch (e: CannotStartProcessException) {
            ServerWarning.warnOnce(project, missingServerMessage(server, e.message ?: e.toString()))
            throw e
        }
    }
}

/**
 * Restarts the server after the settings changed: in every open project where it is running, and
 * in every one with an Avin file open, so a server that could not be found before starts now.
 */
internal fun restartAvinServers() {
    ServerWarning.reset()
    for (project in ProjectManager.getInstance().openProjects) {
        if (project.isDisposed) continue
        val manager = LanguageServerManager.getInstance(project)
        val running = manager.getServerStatus(AVIN_SERVER_ID).let { it == ServerStatus.starting || it == ServerStatus.started }
        if (running) manager.stop(AVIN_SERVER_ID)
        val avinOpen = FileEditorManager.getInstance(project).openFiles.any { it.extension == "avin" }
        if (running || avinOpen) manager.start(AVIN_SERVER_ID)
    }
}
