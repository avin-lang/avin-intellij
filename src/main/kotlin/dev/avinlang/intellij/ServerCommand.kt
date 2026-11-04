package dev.avinlang.intellij

import java.io.File

/** The process that runs the Avin language server on stdio. */
data class ServerCommand(
    val command: String,
    val args: List<String>,
    val env: Map<String, String>,
    val cwd: String? = null,
) {
    /** The command followed by its arguments. */
    val commandLine: List<String> get() = listOf(command) + args
}

const val DEFAULT_SERVER_PATH = "avin"

/**
 * The command line that runs `avin` with [avinArgs]. With [repository] set, the compiler is run
 * from that checkout through `sysl run`, with `AVIN_ROOT` naming the checkout so it finds its
 * library and runtime; otherwise the installed `avin` (or [configured]) is run as it is. The
 * working directory is [cwd].
 */
fun avinCommand(
    configured: String?,
    repository: String?,
    base: Map<String, String>,
    avinArgs: List<String>,
    cwd: String? = null,
): ServerCommand {
    val repo = repository?.trim().orEmpty()
    if (repo.isNotEmpty()) {
        return ServerCommand(
            command = "sysl",
            args = listOf("run", File(repo, "compiler").path, "--") + avinArgs,
            env = base + ("AVIN_ROOT" to repo),
            cwd = cwd,
        )
    }
    val command = configured?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_SERVER_PATH
    return ServerCommand(command, avinArgs, base, cwd)
}

/**
 * The command line that runs the language server on stdio: [avinCommand] with `lsp --stdio`, and
 * for a [repository] the checkout as the working directory.
 */
fun serverCommand(configured: String?, repository: String?, base: Map<String, String>): ServerCommand {
    val repo = repository?.trim().orEmpty()
    return avinCommand(configured, repository, base, listOf("lsp", "--stdio"), repo.ifEmpty { null })
}

/**
 * Where [command] would be run from: itself when it names a path (resolved against [cwd] when
 * relative), otherwise the first match on the `PATH` in [env]; null when there is no executable
 * file there. Checking first keeps a missing server to one message from the plugin.
 */
fun findExecutable(command: String, env: Map<String, String>, cwd: String? = null): File? {
    fun runnable(file: File): Boolean = file.isFile && file.canExecute()
    if (command.contains('/') || command.contains(File.separatorChar)) {
        val file = File(command).let { if (it.isAbsolute || cwd == null) it else File(cwd, command) }
        return file.takeIf(::runnable)
    }
    val searchPath = env["PATH"] ?: return null
    for (dir in searchPath.split(File.pathSeparatorChar)) {
        if (dir.isEmpty()) continue
        val file = File(dir, command)
        if (runnable(file)) return file
    }
    return null
}

/** The message shown once when the server cannot be started. Highlighting keeps working without it. */
fun missingServerMessage(server: ServerCommand, reason: String): String {
    val what = if (server.cwd != null) "'sysl run' on the checkout at ${server.cwd}" else "'${server.command}'"
    return "Could not start the Avin language server ($what: $reason). Syntax highlighting still works. " +
        "Put avin on your PATH, or set the server path or the repository in Settings | Languages & Frameworks | Avin."
}
