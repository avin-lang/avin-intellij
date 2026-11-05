package dev.avinlang.intellij.project

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.ide.fileTemplates.FileTemplate
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.ide.fileTemplates.FileTemplateUtil
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import dev.avinlang.intellij.AvinIcons

/** The file templates the New actions fill: an Avin file, and one holding a sample test. */
const val FILE_TEMPLATE = "Avin File"
const val TEST_FILE_TEMPLATE = "Avin Test File"

/** The template variable holding the module a new file belongs to, empty in a package's root. */
const val MODULE_VARIABLE = "MODULE"

/**
 * Creates `<name>.avin` in [dir] from the template [templateName], its `module` header naming the
 * module [dir] is (none in a package's root, nor outside a package). A name may be given with its
 * `.avin` extension.
 */
fun createAvinFile(dir: PsiDirectory, name: String, templateName: String): PsiFile {
    val project = dir.project
    val manager = FileTemplateManager.getInstance(project)
    val template = manager.getInternalTemplate(templateName)
    val properties = manager.defaultProperties
    properties.setProperty(MODULE_VARIABLE, modulePathOf(dir.virtualFile).orEmpty())
    return FileTemplateUtil.createFromTemplate(template, name.removeSuffix(".avin"), properties, dir).containingFile
}

/**
 * Creates the module [path] (`shapes.solid`) under [dir]: its directories, made where missing, and a
 * first file named after its last name, headed with the module's full name.
 */
fun createAvinModule(dir: PsiDirectory, path: String): PsiFile {
    val segments = path.split('.')
    val target = WriteCommandAction.writeCommandAction(dir.project).withName("Avin Module").compute<PsiDirectory, RuntimeException> {
        segments.fold(dir) { parent, segment -> parent.findSubdirectory(segment) ?: parent.createSubdirectory(segment) }
    }
    return createAvinFile(target, segments.last(), FILE_TEMPLATE)
}

/** Why [name] cannot name a new Avin file, or null when it can. */
fun avinFileNameError(name: String): String? {
    val base = name.trim().removeSuffix(".avin")
    if (base.isEmpty()) return "A file name cannot be empty"
    if (base.any { it == '/' || it == '\\' }) return "A file name cannot hold a directory separator"
    if (base.startsWith(".")) return "An Avin file's name cannot begin with `.`"
    return null
}

private class Validator(private val check: (String) -> String?) : InputValidatorEx {
    override fun getErrorText(inputString: String): String? = check(inputString.trim())
    override fun checkInput(inputString: String): Boolean = check(inputString.trim()) == null
    override fun canClose(inputString: String): Boolean = checkInput(inputString)
}

private fun open(project: Project, file: PsiFile) {
    file.virtualFile?.let { FileEditorManager.getInstance(project).openFile(it, true) }
}

/** New | Avin File: an Avin file, or one holding a sample test, headed with its directory's module. */
class CreateAvinFileAction :
    CreateFileFromTemplateAction("Avin File", "Creates an Avin file headed with its module", AvinIcons.FILE), DumbAware {
    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle("New Avin File")
            .addKind("File", AvinIcons.FILE, FILE_TEMPLATE)
            .addKind("Test file", AvinIcons.FILE, TEST_FILE_TEMPLATE)
            .setValidator(Validator(::avinFileNameError))
    }

    override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?): String = "Avin File"

    override fun createFileFromTemplate(name: String, template: FileTemplate, dir: PsiDirectory): PsiFile? =
        createAvinFile(dir, name.trim(), template.name).also { open(dir.project, it) }
}

/** New | Avin Module: a module's directories under the chosen one, and its first file. */
class CreateAvinModuleAction :
    CreateFileFromTemplateAction("Avin Module", "Creates an Avin module: its directory and a first file", AvinIcons.MODULE), DumbAware {
    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        val under = modulePathOf(directory.virtualFile)?.takeIf { it.isNotEmpty() }
        builder.setTitle(if (under != null) "New Avin Module under $under" else "New Avin Module")
            .addKind("Module", AvinIcons.MODULE, FILE_TEMPLATE)
            .setValidator(Validator(::modulePathError))
    }

    override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?): String = "Avin Module"

    override fun createFileFromTemplate(name: String, template: FileTemplate, dir: PsiDirectory): PsiFile? =
        createAvinModule(dir, name.trim()).also { open(dir.project, it) }
}
