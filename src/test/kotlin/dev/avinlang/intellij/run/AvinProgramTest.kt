package dev.avinlang.intellij.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AvinProgramTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val env = mapOf("PATH" to "/usr/bin:/bin")

    @Test
    fun `a file in a package is the package's program, found by walking up`() {
        val pkg = temp.newFolder("hello")
        File(pkg, MANIFEST).writeText("package { name = \"hello\" }")
        val file = File(pkg, "src/deep/main.avin").apply { parentFile.mkdirs(); writeText("") }
        assertEquals(pkg, programFor(file))
        assertEquals(pkg, programFor(file.parentFile))
        assertEquals(pkg, programRoot(file))
        assertEquals("src/deep/main.avin", shownFile(file, pkg))
    }

    @Test
    fun `outside a package a file is its own program, rooted in its directory`() {
        val dir = temp.newFolder("examples")
        val file = File(dir, "hello.avin").apply { writeText("") }
        assertEquals(file, programFor(file))
        assertEquals(dir, programRoot(file))
        assertEquals(dir, programRoot(dir))
        assertNull(packageRoot(file))
    }

    @Test
    fun `run passes the optimization and the program's arguments after a double dash`() {
        assertEquals(listOf("run", "/p"), avinArguments(AvinRequest(AvinCommandKind.RUN, "/p")))
        assertEquals(
            listOf("run", "/p", "-O0", "--", "a b", "--flag"),
            avinArguments(AvinRequest(AvinCommandKind.RUN, "/p", "-O0", listOf("a b", "--flag"), testFilter = "ignored")),
        )
    }

    @Test
    fun `test passes the filter before the optimization, and an unknown optimization is left out`() {
        assertEquals(listOf("test", "/p"), avinArguments(AvinRequest(AvinCommandKind.TEST, "/p", "-O3")))
        assertEquals(
            listOf("test", "/p", "low bound", "-O2"),
            avinArguments(AvinRequest(AvinCommandKind.TEST, "/p", " -O2 ", listOf("ignored"), "low bound")),
        )
    }

    @Test
    fun `the process is the installed avin, in the program's directory unless one is chosen`() {
        val dir = temp.newFolder("ex")
        val file = File(dir, "hello.avin").apply { writeText("") }
        val process = avinProcess("  /opt/bin/avin ", "", env, AvinRequest(AvinCommandKind.RUN, file.path))
        assertEquals(listOf("/opt/bin/avin", "run", file.path), process.commandLine)
        assertEquals(dir.path, process.cwd)
        assertEquals(env, process.env)
        assertEquals("/tmp/w", avinProcess(null, null, env, AvinRequest(AvinCommandKind.RUN, file.path, workingDirectory = " /tmp/w ")).cwd)
        assertEquals("avin", avinProcess(null, null, env, AvinRequest(AvinCommandKind.RUN, file.path)).command)
    }

    @Test
    fun `a repository runs the compiler through sysl with AVIN_ROOT, in the program's directory`() {
        val dir = temp.newFolder("ex")
        val file = File(dir, "hello.avin").apply { writeText("") }
        val process = avinProcess("avin", "/src/avin", env + ("AVIN_ROOT" to "/old"), AvinRequest(AvinCommandKind.TEST, file.path, "-O0", testFilter = "x"))
        assertEquals(listOf("sysl", "run", "/src/avin/compiler", "--", "test", file.path, "x", "-O0"), process.commandLine)
        assertEquals("/src/avin", process.env["AVIN_ROOT"])
        assertEquals(dir.path, process.cwd)
    }
}
