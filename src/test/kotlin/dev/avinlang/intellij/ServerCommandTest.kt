package dev.avinlang.intellij

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ServerCommandTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val base = mapOf("PATH" to "/usr/bin:/bin", "HOME" to "/home/someone")

    @Test
    fun `the default runs avin from PATH`() {
        val server = serverCommand(null, null, base)
        assertEquals(listOf("avin", "lsp", "--stdio"), server.commandLine)
        assertEquals(base, server.env)
        assertNull(server.cwd)
    }

    @Test
    fun `a blank server path falls back to avin`() {
        assertEquals("avin", serverCommand("   ", "", base).command)
    }

    @Test
    fun `a configured server path is trimmed and used`() {
        val server = serverCommand("  /opt/avin/bin/avin ", "  ", base)
        assertEquals(listOf("/opt/avin/bin/avin", "lsp", "--stdio"), server.commandLine)
        assertNull(server.cwd)
    }

    @Test
    fun `a repository runs the compiler through sysl with AVIN_ROOT and its working directory`() {
        val server = serverCommand("/opt/avin/bin/avin", " /src/avin ", base)
        assertEquals(listOf("sysl", "run", "/src/avin/compiler", "--", "lsp", "--stdio"), server.commandLine)
        assertEquals("/src/avin", server.env["AVIN_ROOT"])
        assertEquals("/usr/bin:/bin", server.env["PATH"])
        assertEquals("/src/avin", server.cwd)
    }

    @Test
    fun `the repository replaces an inherited AVIN_ROOT`() {
        val server = serverCommand(null, "/src/avin", base + ("AVIN_ROOT" to "/elsewhere"))
        assertEquals("/src/avin", server.env["AVIN_ROOT"])
    }

    @Test
    fun `an executable is found on PATH, skipping empty entries and non-executables`() {
        val first = temp.newFolder("first")
        val second = temp.newFolder("second")
        File(first, "avin").apply { writeText("") }.setExecutable(false)
        val wanted = File(second, "avin").apply { writeText("") }
        wanted.setExecutable(true)
        val env = mapOf("PATH" to listOf("", first.path, second.path).joinToString(File.pathSeparator))
        assertEquals(wanted, findExecutable("avin", env))
    }

    @Test
    fun `a directory on PATH with the command's name is not an executable`() {
        val dir = temp.newFolder("bin")
        File(dir, "avin").mkdir()
        assertNull(findExecutable("avin", mapOf("PATH" to dir.path)))
    }

    @Test
    fun `a missing command or PATH finds nothing`() {
        assertNull(findExecutable("avin", mapOf("PATH" to temp.root.path)))
        assertNull(findExecutable("avin", emptyMap()))
    }

    @Test
    fun `a path is checked as it is, a relative one against the working directory`() {
        val dir = temp.newFolder("checkout")
        val tool = File(dir, "avin").apply { writeText("") }
        tool.setExecutable(true)
        assertEquals(tool, findExecutable(tool.path, emptyMap()))
        assertEquals(File(dir, "./avin"), findExecutable("./avin", emptyMap(), dir.path))
        assertNull(findExecutable("./missing", emptyMap(), dir.path))
    }

    @Test
    fun `the message names the command, or the checkout for sysl run`() {
        val installed = missingServerMessage(serverCommand(null, null, base), "not found")
        assertTrue(installed, installed.startsWith("Could not start the Avin language server ('avin': not found)."))
        val checkout = missingServerMessage(serverCommand(null, "/src/avin", base), "not found")
        assertTrue(checkout, checkout.contains("('sysl run' on the checkout at /src/avin: not found)"))
        assertTrue(checkout, checkout.contains("Syntax highlighting still works."))
    }
}
