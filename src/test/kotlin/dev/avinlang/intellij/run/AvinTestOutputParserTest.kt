package dev.avinlang.intellij.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The parser fed `avin test`'s report exactly as the compiler printed it. */
class AvinTestOutputParserTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun parse(root: File, report: String): List<TestEvent> {
        val parser = AvinTestOutputParser(root)
        return report.lines().flatMap { parser.feed(it + "\n") } + parser.finish()
    }

    // `avin test clamp.avin`, a program of one file.
    private val singleFile = """
        |running 3 tests
        |
        |clamp.avin
        |  ok    clamps below the low bound  3ms
        |  FAIL  leavesInRange               4ms
        |        assertion failed: four is left as it is at clamp.avin:13:5
        |        > clamping 4
        |  FAIL  equality                    4ms
        |        assertion failed: expected 4, found 3 at clamp.avin:19:5
        |        > first line
        |        > second line
        |
        |1 passed, 2 failed — 12ms
    """.trimMargin()

    // `avin test many`, a directory program; a trap names its file without the directory.
    private val directory = """
        |running 3 tests
        |
        |sub/more.avin
        |  FAIL  adds two numbers  4ms
        |        assertion failed: arithmetic at more.avin:5:5
        |  ok    alsoFine          4ms
        |
        |main.avin
        |  ok    mainPasses        4ms
        |
        |2 passed, 1 failed — 12ms
    """.trimMargin()

    @Test
    fun `a program of one file gives a suite of its tests, passes and failures with reasons and output`() {
        val root = temp.newFolder("one")
        val clamp = File(root, "clamp.avin").apply { writeText("") }
        val events = parse(root, singleFile)
        assertEquals(
            listOf(
                TestEvent.Count(3),
                TestEvent.SuiteStarted("clamp.avin", sourceLocation(clamp, 1, 1)),
                TestEvent.Started("clamps below the low bound", testLocation(clamp, "clamps below the low bound")),
                TestEvent.Finished("clamps below the low bound", 3),
                TestEvent.Started("leavesInRange", sourceLocation(clamp, 13, 5)),
                TestEvent.Output("leavesInRange", "clamping 4\n"),
                TestEvent.Failed("leavesInRange", "assertion failed: four is left as it is at clamp.avin:13:5"),
                TestEvent.Finished("leavesInRange", 4),
                TestEvent.Started("equality", sourceLocation(clamp, 19, 5)),
                TestEvent.Output("equality", "first line\nsecond line\n"),
                TestEvent.Failed("equality", "assertion failed: expected 4, found 3 at clamp.avin:19:5"),
                TestEvent.Finished("equality", 4),
                TestEvent.SuiteFinished("clamp.avin"),
            ),
            events,
        )
    }

    @Test
    fun `a directory program gives a suite per file, and a trap's bare file name is the failing test's file`() {
        val root = temp.newFolder("many")
        val more = File(root, "sub/more.avin").apply { parentFile.mkdirs(); writeText("") }
        val main = File(root, "main.avin").apply { writeText("") }
        val events = parse(root, directory)
        assertEquals(
            listOf(
                TestEvent.Count(3),
                TestEvent.SuiteStarted("sub/more.avin", sourceLocation(more, 1, 1)),
                TestEvent.Started("adds two numbers", sourceLocation(more, 5, 5)),
                TestEvent.Failed("adds two numbers", "assertion failed: arithmetic at more.avin:5:5"),
                TestEvent.Finished("adds two numbers", 4),
                TestEvent.Started("alsoFine", testLocation(more, "alsoFine")),
                TestEvent.Finished("alsoFine", 4),
                TestEvent.SuiteFinished("sub/more.avin"),
                TestEvent.SuiteStarted("main.avin", sourceLocation(main, 1, 1)),
                TestEvent.Started("mainPasses", testLocation(main, "mainPasses")),
                TestEvent.Finished("mainPasses", 4),
                TestEvent.SuiteFinished("main.avin"),
            ),
            events,
        )
    }

    @Test
    fun `a filtered run counts the tests it runs`() {
        val root = temp.newFolder("many")
        val events = parse(root, "running 1 of 3 tests\n\nsub/more.avin\n  ok    adds  4ms\n\n1 passed, 0 failed — 4ms")
        assertEquals(TestEvent.Count(1), events.first())
        assertEquals(TestEvent.Finished("adds", 4), events[3])
    }

    @Test
    fun `a failure without a trap is placed at the test's declaration with how it ended`() {
        val root = temp.newFolder("p")
        val file = File(root, "t.avin").apply { writeText("") }
        val report = "running 2 tests\n\nt.avin\n  FAIL  slow  60001ms\n        did not finish within 60 seconds\n" +
            "  FAIL  exits  2ms\n        exited with status 3\n        > partial\n\n0 passed, 2 failed — 60003ms"
        val events = parse(root, report)
        assertTrue(events.contains(TestEvent.Started("slow", testLocation(file, "slow"))))
        assertTrue(events.contains(TestEvent.Failed("slow", "did not finish within 60 seconds")))
        assertTrue(events.contains(TestEvent.Started("exits", testLocation(file, "exits"))))
        assertTrue(events.contains(TestEvent.Output("exits", "partial\n")))
    }

    @Test
    fun `a trap in a file that is not the test's own is found under the root`() {
        val root = temp.newFolder("p")
        val helper = File(root, "lib/helpers.avin").apply { parentFile.mkdirs(); writeText("") }
        File(root, "t.avin").writeText("")
        val events = parse(root, "running 1 test\n\nt.avin\n  FAIL  x  1ms\n        index out of bounds at helpers.avin:7:9\n\n0 passed, 1 failed — 1ms")
        assertTrue(events.toString(), events.contains(TestEvent.Started("x", sourceLocation(helper, 7, 9))))
    }

    @Test
    fun `a name with wide spacing keeps everything up to the duration`() {
        val root = temp.newFolder("p")
        val events = parse(root, "running 1 test\n\nt.avin\n  ok    takes  2ms  apart  15ms\n\n1 passed, 0 failed — 15ms")
        assertTrue(events.toString(), events.contains(TestEvent.Finished("takes  2ms  apart", 15)))
    }

    @Test
    fun `a printed blank line is kept`() {
        val root = temp.newFolder("p")
        val events = parse(root, "running 1 test\n\nt.avin\n  FAIL  x  1ms\n        assertion failed at t.avin:1:1\n        > a\n        > \n        > b\n\n0 passed, 1 failed — 1ms")
        assertTrue(events.toString(), events.contains(TestEvent.Output("x", "a\n\nb\n")))
    }

    @Test
    fun `diagnostics before the report pass through as text, and no test events come of them`() {
        val root = temp.newFolder("p")
        val diagnostics = "error: `println` is not defined\n --> /src/p/clamp.avin:4:5\n  |\n4 |     println(\"x\")\n  |     ^^^^^^^"
        val events = parse(root, diagnostics)
        assertEquals(diagnostics.lines().map { TestEvent.Text(it) }, events)
    }

    @Test
    fun `a report cut off part way closes the open failure and suite`() {
        val root = temp.newFolder("p")
        val parser = AvinTestOutputParser(root)
        val fed = "running 2 tests\n\nt.avin\n  FAIL  x  1ms\n        stopped by signal 9".lines().flatMap { parser.feed(it) }
        assertTrue(fed.none { it is TestEvent.Failed })
        val closed = parser.finish()
        assertEquals(listOf("Started", "Failed", "Finished", "SuiteFinished"), closed.map { it::class.simpleName })
    }

    @Test
    fun `service messages carry the names, places and durations, escaped`() {
        val file = File("/src/p/t.avin")
        assertEquals("##teamcity[testCount count='3']", serviceMessage(TestEvent.Count(3)))
        assertEquals(
            "##teamcity[testStarted name='it|'s |[ok|]' locationHint='avin-test:///src/p/t.avin::it|'s |[ok|]']",
            serviceMessage(TestEvent.Started("it's [ok]", testLocation(file, "it's [ok]"))),
        )
        assertEquals("##teamcity[testStdOut name='x' out='a|nb|n']", serviceMessage(TestEvent.Output("x", "a\nb\n")))
        assertEquals("##teamcity[testFinished name='x' duration='4']", serviceMessage(TestEvent.Finished("x", 4)))
        assertEquals(null, serviceMessage(TestEvent.Text("plain")))
    }
}
