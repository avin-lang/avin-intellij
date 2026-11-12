package dev.avinlang.intellij.run

import org.junit.Assert.assertEquals
import org.junit.Test

class AvinSourceScanTest {
    @Test
    fun `main and the tests of an avin file, a test named by its sentence or else its function`() {
        val source = """
            |clamp(n: int, low: int, high: int) -> int = if n < low then low elif n > high then high else n
            |
            |main() =
            |    print("clamped")
            |
            |@test("clamps below the low bound")
            |below()
            |    assertEq(clamp(0, 1, 3), 1)
            |
            |@test
            |leavesInRange()
            |    assert(clamp(4, 1, 3) == 4, "four is left as it is")
        """.trimMargin()
        assertEquals(
            listOf(
                MainTarget(2),
                TestTarget(6, "clamps below the low bound", "below"),
                TestTarget(10, "leavesInRange", "leavesInRange"),
            ),
            scanRunTargets("clamp.avin", source),
        )
    }

    @Test
    fun `main with parameters counts, an indented or longer name does not`() {
        val source = "main(args: Vector[string]) =\n    main()\nmainly() = 1\n  main()\n"
        assertEquals(listOf(MainTarget(0)), scanRunTargets("m.avin", source))
    }

    @Test
    fun `def before main or a test function is accepted, after any modifiers`() {
        val source = "def main() =\n    println(1)\n\n@test\ndef first()\n    assert(true)\n\n@test(\"second\")\nprivate def second(n: int)\n    assert(true)\n\ndef mainly() = 1\n  def main()\n"
        assertEquals(
            listOf(MainTarget(0), TestTarget(4, "first", "first"), TestTarget(8, "second", "second")),
            scanRunTargets("d.avin", source),
        )
    }

    @Test
    fun `def main without a parameter list is main`() {
        assertEquals(listOf(MainTarget(0)), scanRunTargets("d.avin", "def main -> unit = println(1)\n"))
    }

    @Test
    fun `def inside a literate file's program is read the same way`() {
        val source = "# T\n\n    def main() =\n        println(1)\n"
        assertEquals(listOf(MainTarget(2)), scanRunTargets("d.lavin", source))
    }

    @Test
    fun `blank lines, comments and other annotations may stand between @test and the declaration`() {
        val source = "@test(\"a \\\"quoted\\\" name\")  // why\n\n// note\n@slow\nquoted()\n    assert(true)\n"
        assertEquals(listOf(TestTarget(4, "a \"quoted\" name", "quoted")), scanRunTargets("t.avin", source))
    }

    @Test
    fun `anything else after @test cancels it`() {
        val source = "@test\nval x = 1\nnotATest()\n    assert(true)\n"
        assertEquals(emptyList<RunTarget>(), scanRunTargets("t.avin", source))
    }

    @Test
    fun `@test inside a string or a body is not a test`() {
        val source = "f()\n    @test\n    g()\nh() = \"@test\"\n"
        assertEquals(emptyList<RunTarget>(), scanRunTargets("t.avin", source))
    }

    @Test
    fun `a literate file's program is the lines indented four columns, outside fences`() {
        val source = """
            |# Digits
            |
            |The program starts here:
            |
            |    main() =
            |        print("digits")
            |
            |A test, with prose between it and the next block:
            |
            |    @test("sums digits")
            |    sums()
            |        assert(true)
            |
            |```avin
            |    main() =
            |@test
            |    notCounted()
            |```
            |
            |main() = prose, not program
        """.trimMargin()
        assertEquals(
            listOf(MainTarget(4), TestTarget(10, "sums digits", "sums")),
            scanRunTargets("digits.lavin", source),
        )
    }

    @Test
    fun `carriage returns are ignored`() {
        assertEquals(listOf(MainTarget(0), TestTarget(3, "t", "t")), scanRunTargets("w.avin", "main() =\r\n    print(\"x\")\r\n@test\r\nt()\r\n"))
    }
}
