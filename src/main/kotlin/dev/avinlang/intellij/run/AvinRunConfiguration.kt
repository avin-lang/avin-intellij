package dev.avinlang.intellij.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil
import com.intellij.util.execution.ParametersListUtil
import dev.avinlang.intellij.AvinIcons
import dev.avinlang.intellij.AvinSettings
import java.io.File

/** The "Avin" run configuration type: runs a program, or its tests, with `avin run` or `avin test`. */
class AvinConfigurationType : ConfigurationTypeBase(
    ID,
    "Avin",
    "Run an Avin program with avin run, or its tests with avin test",
    AvinIcons.FILE,
) {
    init {
        addFactory(AvinConfigurationFactory(this))
    }

    val factory: ConfigurationFactory get() = configurationFactories.single()

    companion object {
        const val ID = "AvinRunConfiguration"

        fun getInstance(): AvinConfigurationType = ConfigurationTypeUtil.findConfigurationType(AvinConfigurationType::class.java)
    }
}

class AvinConfigurationFactory(type: AvinConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = "Avin"

    override fun createTemplateConfiguration(project: Project): RunConfiguration = AvinRunConfiguration(project, this, "Avin")

    override fun getOptionsClass(): Class<out BaseState> = AvinRunOptions::class.java
}

class AvinRunOptions : LocatableRunConfigurationOptions() {
    /** `run` or `test`. */
    var command by string(AvinCommandKind.RUN.word)

    /** A source file, a directory, or a path in a package. */
    var program by string("")

    /** `-O0`, `-O2`, or empty for the compiler's default. */
    var optimization by string("")

    /** The program's arguments, as a command line. */
    var programArguments by string("")

    /** Keeps the tests whose name or file contains it. */
    var testFilter by string("")

    /** Empty runs in the program's root directory. */
    var workingDirectory by string("")

    var environment by map<String, String>()

    var passParentEnvironment by property(true)
}

class AvinRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<AvinRunOptions>(project, factory, name) {

    public override fun getOptions(): AvinRunOptions = super.getOptions() as AvinRunOptions

    var kind: AvinCommandKind
        get() = AvinCommandKind.entries.firstOrNull { it.word == options.command } ?: AvinCommandKind.RUN
        set(value) {
            options.command = value.word
        }

    var program: String
        get() = options.program.orEmpty()
        set(value) {
            options.program = value
        }

    var optimization: String
        get() = options.optimization.orEmpty()
        set(value) {
            options.optimization = value
        }

    var programArguments: String
        get() = options.programArguments.orEmpty()
        set(value) {
            options.programArguments = value
        }

    var testFilter: String
        get() = options.testFilter.orEmpty()
        set(value) {
            options.testFilter = value
        }

    var workingDirectory: String
        get() = options.workingDirectory.orEmpty()
        set(value) {
            options.workingDirectory = value
        }

    var environment: Map<String, String>
        get() = options.environment
        set(value) {
            options.environment = LinkedHashMap(value)
        }

    var passParentEnvironment: Boolean
        get() = options.passParentEnvironment
        set(value) {
            options.passParentEnvironment = value
        }

    /** The program as an absolute path, a relative one taken from the project's directory. */
    fun programFile(): File {
        val file = File(program.trim())
        if (file.isAbsolute) return file
        return project.basePath?.let { File(it, file.path) } ?: file.absoluteFile
    }

    fun request(): AvinRequest = AvinRequest(
        kind = kind,
        program = programFile().path,
        optimization = optimization,
        programArguments = ParametersListUtil.parse(programArguments),
        testFilter = testFilter,
        workingDirectory = workingDirectory,
    )

    /** The process to start, invoked as the language server is (Settings | Languages & Frameworks | Avin). */
    fun commandLine(): GeneralCommandLine {
        val settings = AvinSettings.getInstance()
        val base = if (passParentEnvironment) EnvironmentUtil.getEnvironmentMap() else emptyMap()
        val process = avinProcess(settings.serverPath, settings.repository, base + environment, request())
        val line = GeneralCommandLine(process.commandLine)
            .withEnvironment(process.env)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
        process.cwd?.let { line.withWorkDirectory(File(it)) }
        return line
    }

    override fun checkConfiguration() {
        if (program.isBlank()) throw RuntimeConfigurationError("Choose the program: an Avin file, a directory or a package.")
        if (!programFile().exists()) throw RuntimeConfigurationError("There is no ${programFile().path}.")
    }

    override fun suggestedName(): String {
        val name = programFile().name.ifEmpty { "Avin" }
        return when (kind) {
            AvinCommandKind.RUN -> name
            AvinCommandKind.TEST -> if (testFilter.isEmpty()) "Tests in $name" else "$testFilter ($name)"
        }
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = AvinSettingsEditor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        if (kind == AvinCommandKind.TEST) AvinTestState(this, environment) else AvinRunState(this, environment)
}

/** `avin run`: the program's output in a console, its traps and diagnostics linked to the source. */
class AvinRunState(private val configuration: AvinRunConfiguration, environment: ExecutionEnvironment) :
    CommandLineState(environment) {
    init {
        addConsoleFilters(AvinSourceFilter(configuration.project, programRoot(configuration.programFile())))
    }

    @Throws(ExecutionException::class)
    override fun startProcess(): ProcessHandler {
        val handler = KillableColoredProcessHandler(configuration.commandLine())
        ProcessTerminatedListener.attach(handler)
        return handler
    }
}

/** `avin test`: the report shown in the test tree. */
class AvinTestState(private val configuration: AvinRunConfiguration, environment: ExecutionEnvironment) :
    CommandLineState(environment) {

    @Throws(ExecutionException::class)
    override fun startProcess(): ProcessHandler {
        val handler = KillableColoredProcessHandler(configuration.commandLine())
        ProcessTerminatedListener.attach(handler)
        return handler
    }

    @Throws(ExecutionException::class)
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val handler = startProcess()
        val properties = AvinTestConsoleProperties(configuration, executor, programRoot(configuration.programFile()))
        val console = SMTestRunnerConnectionUtil.createAndAttachConsole(TEST_FRAMEWORK, handler, properties)
        return DefaultExecutionResult(console, handler)
    }
}
