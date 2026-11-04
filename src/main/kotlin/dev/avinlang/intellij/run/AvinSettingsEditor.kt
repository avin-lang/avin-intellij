package dev.avinlang.intellij.run

import com.intellij.execution.configuration.EnvironmentVariablesTextFieldWithBrowseButton
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.RawCommandLineEditor
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/** The form of an Avin run configuration. */
class AvinSettingsEditor(project: Project) : SettingsEditor<AvinRunConfiguration>() {
    private val command = ComboBox(AvinCommandKind.entries.toTypedArray()).apply {
        renderer = textListCellRenderer { if (it == AvinCommandKind.TEST) "Test (avin test)" else "Run (avin run)" }
    }
    private val program = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFileOrDir().withTitle("Avin Program")
                .withDescription("An .avin or .lavin file, a directory, or a package"),
        )
    }
    private val optimization = ComboBox(arrayOf("", "-O0", "-O2")).apply {
        renderer = textListCellRenderer { if (it.isNullOrEmpty()) "Default (-O2)" else it }
    }
    private val programArguments = RawCommandLineEditor()
    private val testFilter = JBTextField()
    private val workingDirectory = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle("Working Directory"))
    }
    private val environment = EnvironmentVariablesTextFieldWithBrowseButton(project)

    private val form: JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Command:", command)
        .addLabeledComponent("Program:", program)
        .addLabeledComponent("Optimization:", optimization)
        .addLabeledComponent("Program arguments:", programArguments)
        .addLabeledComponent("Test filter:", testFilter)
        .addLabeledComponent("Working directory:", workingDirectory)
        .addLabeledComponent("Environment variables:", environment)
        .addComponentFillVertically(javax.swing.JPanel(), 0)
        .panel

    init {
        command.addActionListener { updateEnabled() }
        updateEnabled()
    }

    private fun updateEnabled() {
        val test = command.item == AvinCommandKind.TEST
        programArguments.isEnabled = !test
        testFilter.isEnabled = test
    }

    override fun resetEditorFrom(configuration: AvinRunConfiguration) {
        command.item = configuration.kind
        program.text = configuration.program
        optimization.item = configuration.optimization.takeIf { it == "-O0" || it == "-O2" } ?: ""
        programArguments.text = configuration.programArguments
        testFilter.text = configuration.testFilter
        workingDirectory.text = configuration.workingDirectory
        environment.data = EnvironmentVariablesData.create(configuration.environment, configuration.passParentEnvironment)
        updateEnabled()
    }

    override fun applyEditorTo(configuration: AvinRunConfiguration) {
        configuration.kind = command.item ?: AvinCommandKind.RUN
        configuration.program = program.text.trim()
        configuration.optimization = optimization.item.orEmpty()
        configuration.programArguments = programArguments.text
        configuration.testFilter = testFilter.text
        configuration.workingDirectory = workingDirectory.text.trim()
        configuration.environment = environment.data.envs
        configuration.passParentEnvironment = environment.data.isPassParentEnvs
    }

    override fun createEditor(): JComponent = form
}
