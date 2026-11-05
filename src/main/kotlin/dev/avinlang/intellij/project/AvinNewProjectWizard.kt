package dev.avinlang.intellij.project

import com.intellij.ide.wizard.AbstractNewProjectWizardStep
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.baseData
import com.intellij.ide.wizard.NewProjectWizardStep
import com.intellij.ide.wizard.language.LanguageGeneratorNewProjectWizard
import com.intellij.openapi.module.ModuleTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import dev.avinlang.intellij.AvinIcons
import java.io.File
import javax.swing.Icon

/**
 * Avin in the New Project wizard's list of languages: a program or a library package, written as
 * `avin new` writes one, with a module holding the IDE's own project files.
 */
class AvinNewProjectWizard : LanguageGeneratorNewProjectWizard {
    override val name: String = "Avin"
    override val icon: Icon = AvinIcons.FILE
    override val ordinal: Int = 900

    override fun createStep(parent: NewProjectWizardStep): NewProjectWizardStep = AvinProjectStep(parent)
}

/** The wizard's Avin page: the package's kind, name and description. */
class AvinProjectStep(parent: NewProjectWizardStep) : AbstractNewProjectWizardStep(parent) {
    val kindProperty = propertyGraph.property(AvinPackageKind.PROGRAM)
    val packageNameProperty = propertyGraph.lazyProperty { packageNameFor(baseData?.name ?: "") }
    val descriptionProperty = propertyGraph.property("")

    init {
        baseData?.let { base -> packageNameProperty.dependsOn(base.nameProperty) { packageNameFor(base.name) } }
    }

    override fun setupUI(builder: Panel) {
        with(builder) {
            row("Kind:") {
                comboBox(AvinPackageKind.entries).bindItem(kindProperty)
            }
            row("Package name:") {
                textField().bindText(packageNameProperty).columns(COLUMNS_MEDIUM)
                    .validationOnInput { field -> packageNameError(field.text)?.let { error(it) } }
                    .validationOnApply { field -> packageNameError(field.text)?.let { error(it) } }
            }
            row("Description:") {
                textField().bindText(descriptionProperty).columns(COLUMNS_MEDIUM)
            }
        }
    }

    override fun setupProject(project: Project) {
        val base = baseData ?: return
        val root = File(base.contentEntryPath)
        writeAvinPackage(root, packageNameProperty.get(), kindProperty.get(), descriptionProperty.get())
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root)?.refresh(false, true)
        val builder = ModuleTypeManager.getInstance().findByID(GENERAL_MODULE).createModuleBuilder()
        builder.name = base.name
        builder.contentEntryPath = base.contentEntryPath
        builder.commit(project)
        AvinProjectModel.scheduleConfigure(project)
    }

    private companion object {
        const val GENERAL_MODULE = "GENERAL_MODULE"
    }
}
