package dev.avinlang.intellij.run

import com.intellij.analysis.problemsView.FileProblem
import com.intellij.analysis.problemsView.Problem
import com.intellij.analysis.problemsView.ProblemsCollector
import com.intellij.analysis.problemsView.ProblemsProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import javax.swing.Icon

/** A diagnostic of `avin check` or `avin build` in a file, at its line and column (counted from 0). */
class AvinFileProblem(
    override val provider: ProblemsProvider,
    override val file: VirtualFile,
    override val line: Int,
    override val column: Int,
    override val text: String,
    val severity: AvinSeverity,
) : FileProblem {
    override val icon: Icon get() = iconOf(severity)

    override fun equals(other: Any?): Boolean =
        other is AvinFileProblem && other.provider == provider && other.file == file && other.line == line &&
            other.column == column && other.text == text && other.severity == severity

    override fun hashCode(): Int = listOf(file, line, column, text, severity).hashCode()
}

/** A diagnostic that names no place, shown in the Problems view on its own. */
class AvinProblem(override val provider: ProblemsProvider, override val text: String, val severity: AvinSeverity) : Problem {
    override val icon: Icon get() = iconOf(severity)
}

private fun iconOf(severity: AvinSeverity): Icon =
    if (severity == AvinSeverity.WARNING) AllIcons.General.Warning else AllIcons.General.Error

/**
 * The problems the last `avin check` or `avin build` of each package found, in the Problems view's
 * Project Errors tab. A new run of a package replaces what its last run reported.
 */
@Service(Service.Level.PROJECT)
class AvinProblems(override val project: Project) : ProblemsProvider {
    private val reported = mutableMapOf<File, List<Problem>>()

    /** Replaces the problems reported for the package at [root] with [diagnostics]. */
    fun report(root: File, diagnostics: List<AvinDiagnostic>) {
        val collector = ProblemsCollector.getInstance(project)
        val problems = diagnostics.map(::problemOf)
        synchronized(reported) {
            reported.remove(root)?.forEach(collector::problemDisappeared)
            if (problems.isNotEmpty()) reported[root] = problems
        }
        problems.forEach(collector::problemAppeared)
    }

    /** What the last run of the package at [root] reported. */
    fun problems(root: File): List<Problem> = synchronized(reported) { reported[root].orEmpty() }

    private fun problemOf(d: AvinDiagnostic): Problem {
        val vf = d.file?.let { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) }
        if (vf == null) {
            val text = if (d.file != null) "${d.message} (${d.file.path}:${d.line}:${d.column})" else d.message
            return AvinProblem(this, text, d.severity)
        }
        return AvinFileProblem(this, vf, (d.line - 1).coerceAtLeast(0), (d.column - 1).coerceAtLeast(0), d.message, d.severity)
    }

    /** The project is closing, and the Problems view with it. */
    override fun dispose() {
        synchronized(reported) { reported.clear() }
    }

    companion object {
        fun getInstance(project: Project): AvinProblems = project.getService(AvinProblems::class.java)
    }
}
