package dev.avinlang.intellij.run

import java.io.File

/**
 * A place in an Avin source file named in the compiler's or a program's output, `<file>:<line>:<col>`
 * (both counted from 1), spanning [start] to [end] in the line it was found in.
 */
data class SourceReference(val start: Int, val end: Int, val file: String, val line: Int, val column: Int)

private val REFERENCE = Regex("""(?<![\w./-])((?:[^\s:]*/)?[^\s:/]+\.l?avin):(\d+):(\d+)""")

/**
 * The places named in [text]: a trap's `at clamp.avin:13:5`, which names the file alone, and a
 * diagnostic's `--> /path/to/clamp.avin:4:5`, which names it in full.
 */
fun findSourceReferences(text: String): List<SourceReference> =
    REFERENCE.findAll(text).mapNotNull { m ->
        val line = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
        val column = m.groupValues[3].toIntOrNull() ?: return@mapNotNull null
        SourceReference(m.range.first, m.range.last + 1, m.groupValues[1], line, column)
    }.toList()

/** Directories never searched for a source file: the build cache and hidden directories. */
private fun searched(dir: File): Boolean = !dir.name.startsWith(".")

/**
 * The file [name] means, for a program whose root directory is [root]. A trap names its file
 * without a directory, so a bare name is taken to be [preferred] when that has the name (the file
 * whose test failed), then the file of that name in [root], then the first one found below [root].
 * A path is taken as it is, or against [root] when relative. Null when there is no such file.
 */
fun resolveSourceFile(name: String, root: File?, preferred: File? = null): File? {
    val given = File(name)
    if (given.isAbsolute) return given.takeIf { it.isFile }
    if (preferred != null && preferred.isFile && (preferred.name == name || preferred.path.endsWith(File.separator + name))) {
        return preferred
    }
    if (root == null || !root.isDirectory) return null
    File(root, name).takeIf { it.isFile }?.let { return it }
    if (name.contains('/')) return null
    return root.walkTopDown()
        .onEnter { it == root || searched(it) }
        .firstOrNull { it.isFile && it.name == name }
}
