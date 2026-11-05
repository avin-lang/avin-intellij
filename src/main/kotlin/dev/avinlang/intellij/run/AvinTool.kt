package dev.avinlang.intellij.run

import dev.avinlang.intellij.ServerCommand
import dev.avinlang.intellij.avinCommand
import java.io.File

/**
 * A command of the Build | Avin menu, run on whole packages: `avin build`, `avin check`, `avin test`,
 * or `avin fmt`, which also takes single files and directories.
 */
enum class AvinToolKind(val word: String, val title: String) {
    BUILD("build", "Build"),
    CHECK("check", "Check"),
    TEST("test", "Test"),
    FMT("fmt", "Format"),
    ;

    /** Whether the command's diagnostics are reported in the Problems view. */
    val reportsProblems: Boolean get() = this == BUILD || this == CHECK
}

/** How deep below the project's directory packages are looked for when nothing is selected. */
private const val PROJECT_ROOT_DEPTH = 3

/**
 * The packages under [base]: [base] itself when it holds `avin.hocon`, otherwise every directory
 * below it holding one, up to three levels down, without looking inside a package or a hidden
 * directory. Sorted by path.
 */
fun projectPackages(base: File): List<File> {
    val root = base.absoluteFile
    if (File(root, MANIFEST).isFile) return listOf(root)
    if (!root.isDirectory) return emptyList()
    val found = mutableListOf<File>()
    fun visit(dir: File, depth: Int) {
        if (depth > PROJECT_ROOT_DEPTH) return
        val children = dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name } ?: return
        for (child in children) {
            if (File(child, MANIFEST).isFile) found += child else visit(child, depth + 1)
        }
    }
    visit(root, 1)
    return found.sortedBy { it.path }
}

/**
 * What [kind] is run on, given the [selected] files (the project view's selection, or the file in
 * the editor) and the project's directory [base] for when nothing is selected.
 *
 * `build`, `check` and `test` run on the package each selected file is in, each package once; a
 * selection outside every package gives nothing, which disables the action. `fmt` formats a
 * selected Avin file or directory as it is, and any other selected file's package. Without a
 * selection, every command runs on the packages of the project ([projectPackages]).
 */
fun toolTargets(kind: AvinToolKind, selected: List<File>, base: File?): List<File> {
    if (selected.isEmpty()) return base?.let(::projectPackages).orEmpty()
    val targets = selected.mapNotNull { file ->
        val absolute = file.absoluteFile
        if (kind == AvinToolKind.FMT && (absolute.isDirectory || isAvinSource(absolute.name))) absolute else packageRoot(absolute)
    }.distinct()
    // A target inside another one is already covered by it.
    return targets.filter { t -> targets.none { other -> other != t && t.path.startsWith(other.path + File.separator) } }
}

/** The arguments after `avin`: `<command> <target>`, or `fmt <path>…`. */
fun toolArguments(kind: AvinToolKind, targets: List<File>): List<String> = listOf(kind.word) + targets.map { it.path }

/**
 * The process running [kind] on [targets], invoked as the language server and the run
 * configurations are ([avinCommand]). The working directory is the first target's root directory
 * ([programRoot]), which is what relative paths in its output are read against.
 */
fun toolProcess(serverPath: String?, repository: String?, env: Map<String, String>, kind: AvinToolKind, targets: List<File>): ServerCommand {
    require(targets.isNotEmpty()) { "no target for avin ${kind.word}" }
    return avinCommand(serverPath, repository, env, toolArguments(kind, targets), programRoot(targets.first()).path)
}

/** How serious a diagnostic is. */
enum class AvinSeverity { ERROR, WARNING }

/**
 * One diagnostic from `avin check` or `avin build`: its [message], and the place its `-->` line
 * names, [line] and [column] counted from 1; [file] is null for a diagnostic without a place (a
 * C library that could not be found, say).
 */
data class AvinDiagnostic(val severity: AvinSeverity, val message: String, val file: File?, val line: Int = 0, val column: Int = 0)

private val HEADER = Regex("""^(error|warning): (.*)$""")
private val LOCATION = Regex("""^\s*-->\s+(.+):(\d+):(\d+)\s*$""")
private val OTHER_HEADER = Regex("""^[a-z][a-z ]*: """)

/**
 * The diagnostics in [output], the compiler's report:
 *
 * ```
 * error: `y` is not defined
 *  --> ./main.avin:3:5
 *   |
 * 3 |     y()
 *   |     ^
 * ```
 *
 * A diagnostic is a line `error: …` or `warning: …`, and its place is the first `-->` line after it,
 * before the next diagnostic. A relative path is read against [root], the directory the command
 * ran in.
 */
fun parseDiagnostics(output: String, root: File?): List<AvinDiagnostic> {
    val out = mutableListOf<AvinDiagnostic>()
    var pending: AvinDiagnostic? = null
    for (raw in output.lineSequence()) {
        val line = raw.trimEnd('\r')
        val header = HEADER.find(line)
        if (header != null) {
            pending?.let(out::add)
            val severity = if (header.groupValues[1] == "warning") AvinSeverity.WARNING else AvinSeverity.ERROR
            pending = AvinDiagnostic(severity, header.groupValues[2].trim(), null)
            continue
        }
        val current = pending ?: continue
        val location = LOCATION.find(line)
        if (location != null && current.file == null) {
            val named = File(location.groupValues[1])
            val file = if (named.isAbsolute || root == null) named else File(root, named.path).normalize()
            out += current.copy(file = file, line = location.groupValues[2].toInt(), column = location.groupValues[3].toInt())
            pending = null
            continue
        }
        // Another kind of line (a note, a program's output) ends a diagnostic that has no place.
        if (OTHER_HEADER.containsMatchIn(line) && !line.startsWith(" ")) {
            out += current
            pending = null
        }
    }
    pending?.let(out::add)
    return out
}
