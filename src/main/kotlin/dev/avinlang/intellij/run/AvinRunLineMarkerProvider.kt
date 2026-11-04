package dev.avinlang.intellij.run

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.awt.RelativePoint
import java.io.File
import javax.swing.Icon

/** What a gutter icon starts: `avin run` on a program, or `avin test` on it with a filter. */
data class Launch(val kind: AvinCommandKind, val program: File, val testFilter: String, val name: String)

/** Finds the run configuration for [launch], or makes one, temporary like any made from context. */
fun configurationFor(project: Project, launch: Launch): RunnerAndConfigurationSettings {
    val manager = RunManager.getInstance(project)
    val type = AvinConfigurationType.getInstance()
    manager.getConfigurationSettingsList(type).firstOrNull {
        val c = it.configuration as? AvinRunConfiguration ?: return@firstOrNull false
        c.kind == launch.kind && c.programFile() == launch.program &&
            (launch.kind == AvinCommandKind.RUN || c.testFilter == launch.testFilter)
    }?.let { return it }
    val settings = manager.createConfiguration(launch.name, type.factory)
    val configuration = settings.configuration as AvinRunConfiguration
    configuration.kind = launch.kind
    configuration.program = launch.program.path
    configuration.testFilter = launch.testFilter
    manager.setTemporaryConfiguration(settings)
    return settings
}

/** Runs [launch] with the Run executor, reusing its configuration when there is one. */
fun start(project: Project, launch: Launch) {
    val settings = configurationFor(project, launch)
    RunManager.getInstance(project).selectedConfiguration = settings
    ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
}

/**
 * What the gutter of [file] offers, from its text ([scanRunTargets]): ▶ on `main` runs the program,
 * ▶ on a test runs that test, and on the first line, a ▶ that runs the file's tests (and, in a
 * package or directory program, all of the program's).
 */
fun gutterLaunches(file: File, text: CharSequence): List<Pair<Int, List<Launch>>> {
    val targets = scanRunTargets(file.name, text)
    val program = programFor(file)
    val programName = program.name
    val result = mutableListOf<Pair<Int, List<Launch>>>()
    val tests = targets.filterIsInstance<TestTarget>()
    if (tests.isNotEmpty()) {
        val all = Launch(AvinCommandKind.TEST, program, "", "Tests in $programName")
        result += 0 to if (program.isDirectory) {
            val shown = shownFile(file, programRoot(program))
            listOf(Launch(AvinCommandKind.TEST, program, shown, "Tests in $shown"), all)
        } else {
            listOf(all)
        }
    }
    for (target in targets) {
        result += target.line to listOf(
            when (target) {
                is MainTarget -> Launch(AvinCommandKind.RUN, program, "", programName)
                is TestTarget -> Launch(AvinCommandKind.TEST, program, target.name, target.name)
            },
        )
    }
    return result
}

/**
 * Puts the run icons in the gutter of `.avin` and `.lavin` files. The files have no syntax tree
 * finer than the whole text, so the icons are placed on lines found from the text, all attached
 * to that one element.
 */
class AvinRunLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: MutableList<out PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        for (element in elements) {
            if (element.firstChild != null || element.parent !is PsiFile) continue
            val psiFile = element.containingFile ?: continue
            val vf = psiFile.virtualFile ?: continue
            if (!isAvinSource(vf.name)) continue
            val document = PsiDocumentManager.getInstance(psiFile.project).getDocument(psiFile) ?: continue
            for ((line, launches) in gutterLaunches(File(vf.path), document.charsSequence)) {
                markerFor(element, document, line, launches)?.let(result::add)
            }
        }
    }

    private fun markerFor(element: PsiElement, document: Document, line: Int, launches: List<Launch>): LineMarkerInfo<PsiElement>? {
        if (line >= document.lineCount) return null
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        val elementRange = element.textRange
        val range = TextRange(start, maxOf(start, end)).intersection(elementRange) ?: return null
        val icon: Icon = if (launches.size > 1 || launches[0].testFilter.isEmpty() && launches[0].kind == AvinCommandKind.TEST) {
            AllIcons.RunConfigurations.TestState.Run_run
        } else {
            AllIcons.RunConfigurations.TestState.Run
        }
        val tooltip = launches.joinToString("\n") { "Run '${it.name}'" }
        val handler = GutterIconNavigationHandler<PsiElement> { event, elt ->
            val project = elt.project
            if (launches.size == 1) {
                start(project, launches[0])
            } else {
                JBPopupFactory.getInstance()
                    .createPopupChooserBuilder(launches)
                    .setRenderer(textListCellRenderer { "Run '${it?.name}'" })
                    .setItemChosenCallback { start(project, it) }
                    .createPopup()
                    .show(RelativePoint(event))
            }
        }
        return LineMarkerInfo(element, range, icon, { tooltip }, handler, GutterIconRenderer.Alignment.CENTER, { tooltip })
    }
}
