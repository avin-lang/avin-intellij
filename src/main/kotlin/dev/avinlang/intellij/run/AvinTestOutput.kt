package dev.avinlang.intellij.run

import java.io.File

/** What the test tree is told, read from `avin test`'s report. */
sealed interface TestEvent {
    /** How many tests are about to run. */
    data class Count(val count: Int) : TestEvent

    /** A file's tests begin; [location] is the file. */
    data class SuiteStarted(val name: String, val location: String) : TestEvent

    data class SuiteFinished(val name: String) : TestEvent

    /** A test begins; [location] is its declaration, or for a failure that trapped, the trap. */
    data class Started(val name: String, val location: String) : TestEvent

    /** What a failed test printed. */
    data class Output(val name: String, val text: String) : TestEvent

    /** Why the test failed: a trap's reason and place, or how its process ended. */
    data class Failed(val name: String, val message: String) : TestEvent

    data class Finished(val name: String, val durationMs: Long) : TestEvent

    /** A line that is not part of the report, such as a diagnostic, shown as it is. */
    data class Text(val line: String) : TestEvent
}

/** The location protocol of a place in a file: `avin-source://<path>:<line>:<column>`, from 1. */
const val SOURCE_PROTOCOL = "avin-source"

/** The location protocol of a test's declaration: `avin-test://<path>::<test name>`. */
const val TEST_PROTOCOL = "avin-test"

fun sourceLocation(file: File, line: Int, column: Int): String = "$SOURCE_PROTOCOL://${file.path}:$line:$column"

fun testLocation(file: File, name: String): String = "$TEST_PROTOCOL://${file.path}::$name"

private val RUNNING = Regex("""^running (\d+)(?: of \d+)? tests?$""")
private val RESULT = Regex("""^  (ok  |FAIL)  (.*\S)\s+(\d+)ms$""")
private val SUMMARY = Regex("""^\d+ passed, \d+ failed""")
private val REASON_AT = Regex("""^(.*) at (\S+\.l?avin):(\d+):(\d+)$""")

/** How the report indents what it says under a failed test. */
private const val DETAIL = "        "
private const val PRINTED = "        >"

/**
 * Reads `avin test`'s report a line at a time and says what it means for the test tree. The report
 * is `running N tests`; then for each file, a blank line, the file (named from the program's root
 * directory), and a line per test, `  ok    <name>  <ms>ms` or `  FAIL  <name>  <ms>ms`, a failure
 * followed by its reason and each line the test printed after `> `, all indented eight columns;
 * then `P passed, F failed — <ms>ms`. Files are found from [root], the program's root directory.
 * Lines before the report (a build's diagnostics) and after it are passed through as text.
 */
class AvinTestOutputParser(private val root: File) {
    private enum class Phase { BEFORE, REPORT, AFTER }

    private class Failure(val name: String, val durationMs: Long) {
        var reason: String? = null
        val printed = mutableListOf<String>()
    }

    private var phase = Phase.BEFORE
    private var suite: String? = null
    private var suiteFile: File? = null
    private var failure: Failure? = null

    /** The events [rawLine] brings, which may close a failure the lines before it described. */
    fun feed(rawLine: String): List<TestEvent> {
        val line = rawLine.trimEnd('\n', '\r')
        val events = mutableListOf<TestEvent>()
        failure?.let { f ->
            if (line.startsWith(DETAIL) || line.startsWith(PRINTED)) {
                when {
                    line.startsWith(PRINTED) -> f.printed += line.substring(PRINTED.length).removePrefix(" ")
                    f.reason == null -> f.reason = line.substring(DETAIL.length)
                    else -> f.printed += line.substring(DETAIL.length)
                }
                return events
            }
            events += closeFailure(f)
        }
        when (phase) {
            Phase.BEFORE -> {
                val running = RUNNING.find(line)
                if (running != null) {
                    phase = Phase.REPORT
                    events += TestEvent.Count(running.groupValues[1].toInt())
                } else {
                    events += TestEvent.Text(line)
                }
            }
            Phase.REPORT -> when {
                line.isBlank() -> {}
                SUMMARY.containsMatchIn(line) -> {
                    events += closeSuite()
                    phase = Phase.AFTER
                }
                RESULT.matches(line) -> {
                    val m = RESULT.find(line)!!
                    val name = m.groupValues[2]
                    val ms = m.groupValues[3].toLong()
                    if (m.groupValues[1] == "FAIL") {
                        failure = Failure(name, ms)
                    } else {
                        events += TestEvent.Started(name, declarationOf(name))
                        events += TestEvent.Finished(name, ms)
                    }
                }
                !line.startsWith(" ") -> {
                    events += closeSuite()
                    val file = File(root, line)
                    suite = line
                    suiteFile = file
                    events += TestEvent.SuiteStarted(line, sourceLocation(file, 1, 1))
                }
                else -> events += TestEvent.Text(line)
            }
            Phase.AFTER -> events += TestEvent.Text(line)
        }
        return events
    }

    /** Closes what the report left open, as when the process was stopped part way. */
    fun finish(): List<TestEvent> {
        val events = mutableListOf<TestEvent>()
        failure?.let { events += closeFailure(it) }
        events += closeSuite()
        return events
    }

    private fun closeSuite(): List<TestEvent> {
        val name = suite ?: return emptyList()
        suite = null
        suiteFile = null
        return listOf(TestEvent.SuiteFinished(name))
    }

    private fun declarationOf(name: String): String =
        suiteFile?.let { testLocation(it, name) } ?: sourceLocation(root, 1, 1)

    private fun closeFailure(f: Failure): List<TestEvent> {
        failure = null
        val reason = f.reason ?: "failed"
        val trap = REASON_AT.find(reason)
        val location = trap?.let {
            resolveSourceFile(it.groupValues[2], root, suiteFile)?.let { file ->
                sourceLocation(file, it.groupValues[3].toInt(), it.groupValues[4].toInt())
            }
        } ?: declarationOf(f.name)
        val events = mutableListOf<TestEvent>(TestEvent.Started(f.name, location))
        if (f.printed.isNotEmpty()) events += TestEvent.Output(f.name, f.printed.joinToString("\n", postfix = "\n"))
        events += TestEvent.Failed(f.name, reason)
        events += TestEvent.Finished(f.name, f.durationMs)
        return events
    }
}
