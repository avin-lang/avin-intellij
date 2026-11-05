package dev.avinlang.intellij.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** The rules of a package's layout, and the files a new package is made of. */
class AvinLayoutTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `module names follow the rule for names`() {
        assertNull(moduleSegmentError("shapes"))
        assertNull(moduleSegmentError("_private"))
        assertNull(moduleSegmentError("solid2"))
        assertNull(moduleSegmentError("données"))
        assertNull(moduleSegmentError("名前"))
        assertNull(moduleSegmentError("x٣"))
        assertNotNull(moduleSegmentError(""))
        assertNotNull(moduleSegmentError("2d"))
        assertNotNull(moduleSegmentError("٣x"))
        assertNotNull(moduleSegmentError("my-dir"))
        assertNotNull(moduleSegmentError(".avin"))
        assertNotNull(moduleSegmentError("a b"))
        assertNotNull(moduleSegmentError("smile\uD83D\uDE00"))
    }

    @Test
    fun `a module name must be in normalization form C`() {
        val decomposed = "cafe\u0301"
        val error = moduleSegmentError(decomposed)
        assertNotNull(error)
        assertTrue(error!!, error.contains("café"))
        assertNull(moduleSegmentError("café"))
    }

    @Test
    fun `a dotted module path is checked name by name`() {
        assertNull(modulePathError("shapes"))
        assertNull(modulePathError("shapes.solid"))
        assertNull(modulePathError("données.über"))
        assertNotNull(modulePathError(""))
        assertNotNull(modulePathError("shapes."))
        assertNotNull(modulePathError(".shapes"))
        assertNotNull(modulePathError("shapes..solid"))
        assertNotNull(modulePathError("shapes.2d"))
        assertNotNull(modulePathError("shapes/solid"))
    }

    @Test
    fun `package names are lowercase ascii as avin new requires`() {
        assertNull(packageNameError("shapes"))
        assertNull(packageNameError("geom-tools"))
        assertNull(packageNameError("a_1"))
        assertNotNull(packageNameError(""))
        assertNotNull(packageNameError("café"))
        assertNotNull(packageNameError("9lives"))
        assertNotNull(packageNameError("My_App"))
        assertNotNull(packageNameError("-x"))
    }

    @Test
    fun `a package name is made from any project name`() {
        assertEquals("myapp", packageNameFor("MyApp"))
        assertEquals("geom-tools", packageNameFor("geom tools"))
        assertEquals("caf", packageNameFor("café"))
        assertEquals("app-9lives", packageNameFor("9lives"))
        assertEquals("app", packageNameFor(""))
        assertEquals("app", packageNameFor("日本"))
        assertEquals("a-b", packageNameFor("a  --  b"))
        for (name in listOf("MyApp", "geom tools", "café", "9lives", "", "日本", "a  --  b", "x.y.z")) {
            assertNull(name, packageNameError(packageNameFor(name)))
        }
    }

    @Test
    fun `the main module writes dashes as underscores`() {
        assertEquals("geom_tools", mainModuleFor("geom-tools"))
        assertEquals("shapes", mainModuleFor("shapes"))
    }

    @Test
    fun `a manifest declares a package by its package block`() {
        assertTrue(declaresPackage("package {\n  name = \"x\"\n  version = \"0.1.0\"\n}\n"))
        assertTrue(declaresPackage("// a comment\npackage{\n}\n"))
        assertTrue(declaresPackage("package = {\n}\n"))
        assertTrue(declaresPackage("package: {\n}\n"))
        assertTrue(declaresPackage("\"package\" {\n}\n"))
        assertFalse(declaresPackage("workspace = [\"app\", \"libs/*\"]\n"))
        assertFalse(declaresPackage(""))
        assertFalse(declaresPackage("dependencies {\n  package2 { path = \"../p\" }\n}\n"))
    }

    @Test
    fun `a program is written as avin new writes it`() {
        val files = avinPackageFiles("geom-tools", AvinPackageKind.PROGRAM)
        assertEquals(
            setOf("avin.hocon", ".gitignore", "geom_tools/geom_tools.avin", "main.avin", "tests/geom_tools_tests.avin"),
            files.keys,
        )
        assertEquals("package {\n  name = \"geom-tools\"\n  version = \"0.1.0\"\n}\n\ndependencies {\n}\n", files["avin.hocon"])
        assertEquals(".avin/\n", files[".gitignore"])
        assertEquals("import geom_tools.greeting\n\nmain()\n    println(greeting(\"world\"))\n", files["main.avin"])
        assertTrue(files.getValue("geom_tools/geom_tools.avin").startsWith("module geom_tools\n"))
        assertTrue(files.getValue("tests/geom_tools_tests.avin").startsWith("module tests\n"))
        assertTrue(files.getValue("tests/geom_tools_tests.avin").contains("@test(\""))
    }

    @Test
    fun `a library has no main`() {
        val files = avinPackageFiles("shapes", AvinPackageKind.LIBRARY)
        assertFalse(files.containsKey("main.avin"))
        assertTrue(files.containsKey("shapes/shapes.avin"))
        assertTrue(files.containsKey("tests/shapes_tests.avin"))
    }

    @Test
    fun `a description goes into the package block, escaped`() {
        val manifest = avinPackageFiles("shapes", AvinPackageKind.LIBRARY, "  Plane \"shapes\" \\ areas  ").getValue("avin.hocon")
        assertTrue(manifest, manifest.contains("  description = \"Plane \\\"shapes\\\" \\\\ areas\"\n}"))
        assertFalse(avinPackageFiles("shapes", AvinPackageKind.LIBRARY, "   ").getValue("avin.hocon").contains("description"))
    }

    @Test
    fun `hocon strings escape what must be escaped`() {
        assertEquals("\"plain\"", hoconString("plain"))
        assertEquals("\"a\\nb\\tc\"", hoconString("a\nb\tc"))
        assertEquals("\"\\u0001\"", hoconString("\u0001"))
        assertEquals("\"Fläche 名\"", hoconString("Fläche 名"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a package is not written under a name that is not a package name`() {
        avinPackageFiles("My App", AvinPackageKind.PROGRAM)
    }

    @Test
    fun `writing a package keeps existing files and adds the build line to a gitignore`() {
        val root = temp.newFolder("p")
        File(root, ".gitignore").writeText(".idea/")
        File(root, "main.avin").writeText("main()\n    println(1)\n")
        writeAvinPackage(root, "p", AvinPackageKind.PROGRAM)
        assertEquals(".idea/\n.avin/\n", File(root, ".gitignore").readText())
        assertEquals("main()\n    println(1)\n", File(root, "main.avin").readText())
        assertTrue(File(root, "p/p.avin").isFile)
        writeAvinPackage(root, "p", AvinPackageKind.PROGRAM)
        assertEquals(".idea/\n.avin/\n", File(root, ".gitignore").readText())
    }

    @Test
    fun `the packages written check with the avin command`() {
        val avin = listOf("/opt/homebrew/bin/avin", "/usr/local/bin/avin").map(::File).firstOrNull { it.canExecute() }
            ?: System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "avin") }.firstOrNull { it.canExecute() }
        assumeTrue("avin is not installed", avin != null)
        for ((name, kind) in listOf("geom-tools" to AvinPackageKind.PROGRAM, "shapes" to AvinPackageKind.LIBRARY)) {
            val root = temp.newFolder(name)
            writeAvinPackage(root, name, kind, "A \"quoted\" description")
            val process = ProcessBuilder(avin!!.path, "check", root.path).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue("avin check timed out", process.waitFor(120, TimeUnit.SECONDS))
            assertEquals("$name: $output", 0, process.exitValue())
        }
    }
}
