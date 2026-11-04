package dev.avinlang.intellij.run

/** A line of a source file with something to run on it. Lines are counted from 0. */
sealed interface RunTarget {
    val line: Int
}

/** A top-level `main`, which `avin run` starts. */
data class MainTarget(override val line: Int) : RunTarget

/**
 * A function marked `@test`, on the line declaring it: [name] is what the test report calls it,
 * the sentence of `@test("…")` or else the function's name.
 */
data class TestTarget(override val line: Int, val name: String, val function: String) : RunTarget

private val MAIN = Regex("""^main\(""")
private val TEST = Regex("""^@test\s*(?:\(\s*"((?:[^"\\]|\\.)*)"\s*\))?\s*(?://.*)?$""")
private val DECLARATION = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
private val FENCE = Regex("""^ {0,3}(```|~~~)""")

/** The column a literate file's program lines are indented to. */
private const val LITERATE_INDENT = 4

/**
 * Finds `main` and the tests in the source of [fileName] by its text, since the editor has no
 * syntax tree for Avin. A line is program text at column 0 in a `.avin` file, and at column 4 in a
 * `.lavin` file outside a fenced block (prose lines are skipped). On program text:
 *
 * - `main(` at the start of a line is the top-level `main`;
 * - a line that is `@test` or `@test("a sentence")` marks the next declaration, `name(` at the
 *   start of a later line, skipping blank lines, comments and other annotations; anything else
 *   between them cancels the mark.
 */
fun scanRunTargets(fileName: String, text: CharSequence): List<RunTarget> {
    val literate = isLiterate(fileName)
    val targets = mutableListOf<RunTarget>()
    var fenced = false
    var pendingSentence: String? = null
    var pending = false
    for ((index, raw) in text.lines().withIndex()) {
        val line = raw.trimEnd('\r')
        val code: String = if (literate) {
            if (line.startsWith(" ".repeat(LITERATE_INDENT)) && !fenced) {
                line.substring(LITERATE_INDENT)
            } else {
                if (FENCE.containsMatchIn(line)) fenced = !fenced
                continue
            }
        } else {
            line
        }
        if (code.isBlank()) continue
        if (MAIN.containsMatchIn(code)) {
            targets += MainTarget(index)
            pending = false
            continue
        }
        val test = TEST.find(code)
        if (test != null) {
            pending = true
            pendingSentence = test.groupValues[1].takeIf { test.groups[1] != null }?.let(::unescape)
            continue
        }
        if (!pending) continue
        if (code.startsWith("//") || code.startsWith("@")) continue
        val declaration = DECLARATION.find(code)
        if (declaration != null) {
            val function = declaration.groupValues[1]
            targets += TestTarget(index, pendingSentence ?: function, function)
        }
        pending = false
        pendingSentence = null
    }
    return targets
}

private fun unescape(s: String): String {
    if (!s.contains('\\')) return s
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            out.append(
                when (val e = s[i + 1]) {
                    'n' -> '\n'
                    't' -> '\t'
                    else -> e
                },
            )
            i += 2
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}
