package dev.avinlang.intellij.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import java.io.File

/**
 * Run from the context menu of an Avin file, or of a directory holding a package or Avin files:
 * the configuration runs the program the file is in (the package, or else the file itself).
 */
class AvinRunConfigurationProducer : LazyRunConfigurationProducer<AvinRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory = AvinConfigurationType.getInstance().factory

    private fun programOf(context: ConfigurationContext): File? {
        val vf: VirtualFile = context.location?.virtualFile ?: return null
        if (vf.fileSystem.protocol != "file") return null
        val file = File(vf.path)
        val runnable = if (vf.isDirectory) {
            packageRoot(file) != null || vf.children.any { !it.isDirectory && isAvinSource(it.name) }
        } else {
            isAvinSource(vf.name)
        }
        return if (runnable) programFor(file) else null
    }

    override fun setupConfigurationFromContext(
        configuration: AvinRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val program = programOf(context) ?: return false
        configuration.kind = AvinCommandKind.RUN
        configuration.program = program.path
        configuration.setGeneratedName()
        return true
    }

    override fun isConfigurationFromContext(configuration: AvinRunConfiguration, context: ConfigurationContext): Boolean {
        val program = programOf(context) ?: return false
        return configuration.kind == AvinCommandKind.RUN && configuration.programFile() == program
    }
}
