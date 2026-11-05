package dev.avinlang.intellij.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.util.EnvironmentUtil
import dev.avinlang.intellij.AvinIcons
import dev.avinlang.intellij.AvinSettings
import java.io.File
import javax.swing.Icon

/** The process for `avin <kind> <targets>`, with the settings' server path or repository. */
fun toolCommandLine(kind: AvinToolKind, targets: List<File>): GeneralCommandLine {
    val settings = AvinSettings.getInstance()
    val process = toolProcess(settings.serverPath, settings.repository, EnvironmentUtil.getEnvironmentMap(), kind, targets)
    val line = GeneralCommandLine(process.commandLine)
        .withEnvironment(process.env)
        .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
    process.cwd?.let { line.withWorkDirectory(File(it)) }
    return line
}

/** The console tab's title: `avin check probe`, `avin fmt a.avin b`. */
fun toolTitle(kind: AvinToolKind, targets: List<File>): String = "avin ${kind.word} " + targets.joinToString(" ") { it.name }

/**
 * `avin build`, `check` or `fmt` on [targets], run in the Run tool window: its output in a console
 * with the places it names linked to the source. A build's or a check's diagnostics go to the
 * Problems view when it ends; a format refreshes the files it rewrote.
 */
class AvinToolProfile(val project: Project, val kind: AvinToolKind, val targets: List<File>) : RunProfile {
    override fun getName(): String = toolTitle(kind, targets)

    override fun getIcon(): Icon = AvinIcons.FILE

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = AvinToolState(this, environment)
}

class AvinToolState(private val profile: AvinToolProfile, environment: ExecutionEnvironment) : CommandLineState(environment) {
    private val root = programRoot(profile.targets.first())

    init {
        addConsoleFilters(AvinSourceFilter(profile.project, root))
    }

    @Throws(ExecutionException::class)
    override fun startProcess(): ProcessHandler {
        val handler = KillableColoredProcessHandler(toolCommandLine(profile.kind, profile.targets))
        ProcessTerminatedListener.attach(handler)
        handler.addProcessListener(AvinToolListener(profile.project, profile.kind, profile.targets, root))
        return handler
    }
}

/** Collects a tool run's output and, when it ends, reports its diagnostics or refreshes its files. */
class AvinToolListener(
    private val project: Project,
    private val kind: AvinToolKind,
    private val targets: List<File>,
    private val root: File,
) : ProcessListener {
    private val output = StringBuilder()

    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        if (outputType == ProcessOutputType.SYSTEM) return
        synchronized(output) { output.append(event.text) }
    }

    override fun processTerminated(event: ProcessEvent) {
        if (project.isDisposed) return
        // A build or a check runs on one package, whose problems its diagnostics replace.
        if (kind.reportsProblems) {
            val diagnostics = parseDiagnostics(synchronized(output) { output.toString() }, root)
            AvinProblems.getInstance(project).report(targets.first(), diagnostics)
        }
        if (kind == AvinToolKind.FMT) refresh(targets)
    }
}

/** Reloads [targets] from disk, so the editor shows what `avin fmt` wrote. */
fun refresh(targets: List<File>) {
    val files = targets.mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) }
    if (files.isNotEmpty()) VfsUtil.markDirtyAndRefresh(true, true, true, *files.toTypedArray())
}

/**
 * Runs [kind] on [targets]: `avin test` as a run of the package's tests (the test runner shows the
 * report), the others as a [AvinToolProfile], one run for each package (`fmt` formats all its paths
 * in one run). The editor's changes are saved first, so the command sees them.
 */
fun runTool(project: Project, kind: AvinToolKind, targets: List<File>) {
    if (targets.isEmpty()) return
    FileDocumentManager.getInstance().saveAllDocuments()
    val executor = DefaultRunExecutor.getRunExecutorInstance()
    val runs = if (kind == AvinToolKind.FMT) listOf(targets) else targets.map(::listOf)
    for (run in runs) {
        if (kind == AvinToolKind.TEST) {
            val program = run.single()
            start(project, Launch(AvinCommandKind.TEST, program, "", "Tests in ${program.name}"))
        } else {
            ExecutionEnvironmentBuilder.create(project, executor, AvinToolProfile(project, kind, run)).buildAndExecute()
        }
    }
}
