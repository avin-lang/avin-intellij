package dev.avinlang.intellij.run

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/**
 * Links the places named in a run's output to the source: a trap's `avin: trap: <reason> at
 * clamp.avin:13:5`, a failed test's reason, and a diagnostic's `--> /path/clamp.avin:4:5`. A bare
 * file name is looked for under [root], the program's root directory.
 */
class AvinSourceFilter(private val project: Project, private val root: File?) : Filter {
    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val references = findSourceReferences(line)
        if (references.isEmpty()) return null
        val lineStart = entireLength - line.length
        val items = references.mapNotNull { ref ->
            val file = resolveSourceFile(ref.file, root) ?: return@mapNotNull null
            val vf = LocalFileSystem.getInstance().findFileByIoFile(file) ?: return@mapNotNull null
            val link = OpenFileHyperlinkInfo(project, vf, ref.line - 1, ref.column - 1)
            Filter.ResultItem(lineStart + ref.start, lineStart + ref.end, link)
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }
}
