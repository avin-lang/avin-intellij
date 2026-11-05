package dev.avinlang.intellij.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AvinToolTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val env = mapOf("PATH" to "/usr/bin:/bin")

    private fun pkg(parent: File, name: String): File =
        File(parent, name).apply { mkdirs(); File(this, MANIFEST).writeText("package { name = \"p\" }") }

    private fun source(dir: File, path: String): File = File(dir, path).apply { parentFile.mkdirs(); writeText("") }

    // ---- what the commands run on ----

    @Test
    fun `a file nested deep in a package runs the package`() {
        val p = pkg(temp.root, "hello")
        val file = source(p, "a/b/c.avin")
        for (kind in listOf(AvinToolKind.BUILD, AvinToolKind.CHECK, AvinToolKind.TEST)) {
            assertEquals(listOf(p), toolTargets(kind, listOf(file), null))
            assertEquals(listOf(p), toolTargets(kind, listOf(file.parentFile), null))
        }
    }

    @Test
    fun `the manifest and a root-module file run the package`() {
        val p = pkg(temp.root, "hello")
        val main = source(p, "main.avin")
        assertEquals(listOf(p), toolTargets(AvinToolKind.CHECK, listOf(File(p, MANIFEST)), null))
        assertEquals(listOf(p), toolTargets(AvinToolKind.BUILD, listOf(main), null))
        assertEquals(listOf(p), toolTargets(AvinToolKind.CHECK, listOf(p), null))
    }

    @Test
    fun `outside every package there is nothing to build, check or test`() {
        val file = source(temp.root, "loose/hello.avin")
        for (kind in listOf(AvinToolKind.BUILD, AvinToolKind.CHECK, AvinToolKind.TEST)) {
            assertEquals(emptyList<File>(), toolTargets(kind, listOf(file), null))
            assertEquals(emptyList<File>(), toolTargets(kind, emptyList(), temp.root))
        }
    }

    @Test
    fun `a project with no package has nothing to run on`() {
        val empty = temp.newFolder("empty")
        assertEquals(emptyList<File>(), toolTargets(AvinToolKind.CHECK, emptyList(), empty))
        assertEquals(emptyList<File>(), toolTargets(AvinToolKind.FMT, emptyList(), empty))
        assertEquals(emptyList<File>(), toolTargets(AvinToolKind.CHECK, emptyList(), null))
    }

    @Test
    fun `files of several packages run each package once`() {
        val a = pkg(temp.root, "a")
        val b = pkg(temp.root, "b")
        val selected = listOf(source(a, "x.avin"), source(b, "m/y.avin"), source(a, "m/z.avin"))
        assertEquals(listOf(a, b), toolTargets(AvinToolKind.CHECK, selected, null))
        assertEquals(listOf("check", a.path), toolArguments(AvinToolKind.CHECK, listOf(a)))
    }

    @Test
    fun `a selection mixing a package and loose files keeps the package`() {
        val a = pkg(temp.root, "a")
        val loose = source(temp.root, "loose/l.avin")
        assertEquals(listOf(a), toolTargets(AvinToolKind.TEST, listOf(loose, source(a, "x.avin")), null))
    }

    @Test
    fun `with nothing selected the project's packages are found`() {
        assertEquals(emptyList<File>(), projectPackages(File(temp.root, "missing")))
        val root = pkg(temp.root, "root")
        assertEquals(listOf(root), toolTargets(AvinToolKind.BUILD, emptyList(), root))

        val ws = temp.newFolder("workspace")
        val app = pkg(ws, "app")
        val lib = pkg(ws, "libs/core")
        pkg(app, "vendor/inner")
        pkg(ws, ".hidden")
        pkg(ws, "a/b/c/d")
        assertEquals(listOf(app, lib), projectPackages(ws))
        assertEquals(listOf(app, lib), toolTargets(AvinToolKind.CHECK, emptyList(), ws))
    }

    @Test
    fun `a package with a Unicode name is found and passed as it is`() {
        val p = pkg(temp.root, "größe-π")
        val file = source(p, "módulo/ünï.avin")
        assertEquals(listOf(p), toolTargets(AvinToolKind.BUILD, listOf(file), null))
        assertEquals(listOf("build", p.path), toolProcess(null, null, env, AvinToolKind.BUILD, listOf(p)).args)
    }

    @Test
    fun `fmt formats selected files and directories as they are`() {
        val p = pkg(temp.root, "p")
        val file = source(p, "a/x.avin")
        val literate = source(p, "notes.lavin")
        val dir = File(p, "b").apply { mkdirs() }
        assertEquals(listOf(file, literate, dir), toolTargets(AvinToolKind.FMT, listOf(file, literate, dir), null))
        // Any other file stands for its package.
        assertEquals(listOf(p), toolTargets(AvinToolKind.FMT, listOf(File(p, MANIFEST)), null))
        // A loose file formats without a package.
        val loose = source(temp.root, "loose/l.avin")
        assertEquals(listOf(loose), toolTargets(AvinToolKind.FMT, listOf(loose), null))
        assertEquals(emptyList<File>(), toolTargets(AvinToolKind.FMT, listOf(source(temp.root, "loose/readme.md")), null))
    }

    @Test
    fun `fmt leaves out what a selected directory already covers`() {
        val p = pkg(temp.root, "p")
        val file = source(p, "a/x.avin")
        assertEquals(listOf(file.parentFile), toolTargets(AvinToolKind.FMT, listOf(file, file.parentFile), null))
        assertEquals(listOf(p), toolTargets(AvinToolKind.FMT, listOf(File(p, MANIFEST), file), null))
        val sibling = File(p, "a-b").apply { mkdirs() }
        assertEquals(listOf(file.parentFile, sibling), toolTargets(AvinToolKind.FMT, listOf(file.parentFile, sibling), null))
    }

    // ---- the command line ----

    @Test
    fun `the installed avin runs the command in the package`() {
        val p = pkg(temp.root, "p")
        val process = toolProcess("/opt/avin/bin/avin", "", env, AvinToolKind.CHECK, listOf(p))
        assertEquals(listOf("/opt/avin/bin/avin", "check", p.path), process.commandLine)
        assertEquals(p.path, process.cwd)
        assertEquals(env, process.env)
        assertEquals(listOf("avin", "build", p.path), toolProcess(null, null, env, AvinToolKind.BUILD, listOf(p)).commandLine)
        assertEquals(listOf("avin", "test", p.path), toolProcess("  ", null, env, AvinToolKind.TEST, listOf(p)).commandLine)
    }

    @Test
    fun `a repository checkout runs the command through sysl run`() {
        val p = pkg(temp.root, "p")
        val process = toolProcess("avin", "/src/avin", env, AvinToolKind.BUILD, listOf(p))
        assertEquals(listOf("sysl", "run", "/src/avin/compiler", "--", "build", p.path), process.commandLine)
        assertEquals("/src/avin", process.env["AVIN_ROOT"])
        assertEquals(p.path, process.cwd)
    }

    @Test
    fun `fmt takes every path in one command, run from the first one's root`() {
        val p = pkg(temp.root, "p")
        val file = source(p, "a/x.avin")
        val loose = source(temp.root, "loose/l.avin")
        val process = toolProcess(null, null, env, AvinToolKind.FMT, listOf(file, loose))
        assertEquals(listOf("avin", "fmt", file.path, loose.path), process.commandLine)
        assertEquals(p.path, process.cwd)
        assertEquals(loose.parentFile.path, toolProcess(null, null, env, AvinToolKind.FMT, listOf(loose)).cwd)
    }

    @Test
    fun `only build and check report problems`() {
        assertTrue(AvinToolKind.BUILD.reportsProblems)
        assertTrue(AvinToolKind.CHECK.reportsProblems)
        assertFalse(AvinToolKind.TEST.reportsProblems)
        assertFalse(AvinToolKind.FMT.reportsProblems)
        assertEquals("avin check p", toolTitle(AvinToolKind.CHECK, listOf(File("/x/p"))))
        assertEquals("avin fmt a.avin b", toolTitle(AvinToolKind.FMT, listOf(File("/x/a.avin"), File("/y/b"))))
    }

    // ---- diagnostics ----

    @Test
    fun `one diagnostic gives its message and place, read against the root`() {
        val root = File("/work/p")
        val output = """
            |error: `y` is not defined
            | --> ./main.avin:3:5
            |  |
            |3 |     y()
            |  |     ^
            |
        """.trimMargin()
        assertEquals(
            listOf(AvinDiagnostic(AvinSeverity.ERROR, "`y` is not defined", File("/work/p/main.avin"), 3, 5)),
            parseDiagnostics(output, root),
        )
    }

    @Test
    fun `many diagnostics, errors and warnings, keep their order`() {
        val output = """
            |error: `f` yields string, not int
            | --> ./sub/é.avin:3:14
            |  |
            |3 | f() -> int = "x"
            |  |              ^^^
            |warning: `x` is never used
            | --> /abs/p/main.avin:2:9
            |  |
            |2 |     val x: int = 1
            |  |         ^
            |
            |error: expected int, found string
            | --> sub/deep/m.lavin:12:18
        """.trimMargin()
        assertEquals(
            listOf(
                AvinDiagnostic(AvinSeverity.ERROR, "`f` yields string, not int", File("/work/p/sub/é.avin"), 3, 14),
                AvinDiagnostic(AvinSeverity.WARNING, "`x` is never used", File("/abs/p/main.avin"), 2, 9),
                AvinDiagnostic(AvinSeverity.ERROR, "expected int, found string", File("/work/p/sub/deep/m.lavin"), 12, 18),
            ),
            parseDiagnostics(output, File("/work/p")),
        )
    }

    @Test
    fun `a Unicode path, with spaces, is kept whole`() {
        val output = "error: bad\n --> /tmp/ディレクトリ/my dir/größe.avin:1:2\n"
        val d = parseDiagnostics(output, null).single()
        assertEquals(File("/tmp/ディレクトリ/my dir/größe.avin"), d.file)
        assertEquals(1, d.line)
        assertEquals(2, d.column)
    }

    @Test
    fun `a diagnostic with no place is kept without one`() {
        val output = """
            |error: the C library `z` of package `p` was not found: pkg-config failed.
            |error: `y` is not defined
            | --> ./main.avin:3:5
            |error: the header `h.h` was not found
        """.trimMargin()
        val ds = parseDiagnostics(output, File("/p"))
        assertEquals(3, ds.size)
        assertNull(ds[0].file)
        assertEquals("the C library `z` of package `p` was not found: pkg-config failed.", ds[0].message)
        assertEquals(File("/p/main.avin"), ds[1].file)
        assertNull(ds[2].file)
        assertEquals("the header `h.h` was not found", ds[2].message)
    }

    @Test
    fun `a place after a note or other output does not attach to an earlier diagnostic`() {
        val output = "error: no place here\nnote: something else\n --> ./x.avin:1:1\n"
        val d = parseDiagnostics(output, File("/p")).single()
        assertNull(d.file)
    }

    @Test
    fun `the manifest's diagnostics are places too`() {
        val output = "error: `name` is not a key of avin.hocon\n --> /p/avin.hocon:1:1\n"
        assertEquals(File("/p/avin.hocon"), parseDiagnostics(output, File("/p")).single().file)
    }

    @Test
    fun `output without diagnostics gives none`() {
        assertEquals(emptyList<AvinDiagnostic>(), parseDiagnostics("", File("/p")))
        assertEquals(emptyList<AvinDiagnostic>(), parseDiagnostics("built /p/hello\nerrors: none\n --> nowhere:1:1\n", File("/p")))
        assertEquals(emptyList<AvinDiagnostic>(), parseDiagnostics("usage: avin [<program>]\n", File("/p")))
    }

    @Test
    fun `a Windows line ending is not part of the place`() {
        val d = parseDiagnostics("error: x\r\n --> ./a.avin:4:7\r\n", File("/p")).single()
        assertEquals(File("/p/a.avin"), d.file)
        assertEquals(7, d.column)
    }
}
