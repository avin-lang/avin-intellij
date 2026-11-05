package dev.avinlang.intellij.project

import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.ide.wizard.language.LanguageGeneratorNewProjectWizard
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** New | Avin File, Avin Test File and Avin Module, and the wizard's registration. */
class AvinFileActionsTest : BasePlatformTestCase() {
    private val manifest = "package {\n  name = \"p\"\n  version = \"0.1.0\"\n}\n"

    private fun dir(path: String): VirtualFile = myFixture.tempDirFixture.findOrCreateDir(path)

    private fun psiDir(path: String) = psiManager.findDirectory(dir(path))!!

    private fun create(path: String, name: String, template: String = FILE_TEMPLATE) =
        createAvinFile(psiDir(path), name, template)

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("pkg/avin.hocon", manifest)
    }

    fun `test a file in the root module has no header`() {
        val file = create("pkg", "main")
        assertEquals("main.avin", file.name)
        assertEquals("", file.text.trim())
    }

    fun `test a file in a module is headed with its dotted path`() {
        val file = create("pkg/shapes/solid", "cube")
        assertEquals("cube.avin", file.name)
        assertEquals("module shapes.solid\n\n", file.text)
    }

    fun `test a name given with its extension is not doubled`() {
        assertEquals("cube.avin", create("pkg/shapes", "cube.avin").name)
    }

    fun `test a test file holds a sample test`() {
        val file = create("pkg/tests", "area_tests", TEST_FILE_TEMPLATE)
        assertEquals("module tests\n\n@test(\"a sample test\")\nsampleTest()\n    assertEq(1 + 1, 2)\n", file.text)
    }

    fun `test a test file in the root has no header`() {
        val file = create("pkg", "root_tests", TEST_FILE_TEMPLATE)
        assertTrue(file.text, file.text.startsWith("@test("))
    }

    fun `test a file outside a package has no header`() {
        assertEquals("", create("loose/a/b", "x").text.trim())
    }

    fun `test the nearest package names the module`() {
        myFixture.addFileToProject("pkg/inner/avin.hocon", manifest)
        assertEquals("module m\n\n", create("pkg/inner/m", "x").text)
    }

    fun `test unicode directories give a unicode header`() {
        assertEquals("module données.über\n\n", create("pkg/données/über", "fläche").text)
    }

    fun `test a module is created with its directories and a first file`() {
        val file = createAvinModule(psiDir("pkg"), "shapes.solid")
        assertEquals("solid.avin", file.name)
        assertEquals("module shapes.solid\n\n", file.text)
        assertNotNull(dir("pkg").findFileByRelativePath("shapes/solid/solid.avin"))
    }

    fun `test a module under a module takes the full path`() {
        dir("pkg/shapes")
        val file = createAvinModule(psiDir("pkg/shapes"), "solid")
        assertEquals("module shapes.solid\n\n", file.text)
    }

    fun `test a module whose directory exists reuses it`() {
        myFixture.addFileToProject("pkg/shapes/area.avin", "module shapes\n")
        val file = createAvinModule(psiDir("pkg"), "shapes.solid")
        assertEquals("module shapes.solid\n\n", file.text)
        assertNotNull(dir("pkg").findFileByRelativePath("shapes/area.avin"))
    }

    fun `test file names are checked`() {
        assertNull(avinFileNameError("cube"))
        assertNull(avinFileNameError("cube.avin"))
        assertNull(avinFileNameError("fläche"))
        assertNotNull(avinFileNameError(""))
        assertNotNull(avinFileNameError(".avin"))
        assertNotNull(avinFileNameError("a/b"))
        assertNotNull(avinFileNameError(".hidden"))
    }

    fun `test the templates and actions are registered`() {
        val manager = FileTemplateManager.getInstance(project)
        assertEquals("avin", manager.getInternalTemplate(FILE_TEMPLATE).extension)
        assertEquals("avin", manager.getInternalTemplate(TEST_FILE_TEMPLATE).extension)
        assertTrue(ActionManager.getInstance().getAction("Avin.NewFile") is CreateAvinFileAction)
        assertTrue(ActionManager.getInstance().getAction("Avin.NewModule") is CreateAvinModuleAction)
    }

    fun `test the wizard is offered among the languages`() {
        val wizard = LanguageGeneratorNewProjectWizard.EP_NAME.extensionList.filterIsInstance<AvinNewProjectWizard>().single()
        assertEquals("Avin", wizard.name)
    }

    fun `test the files the templates write check with the avin command`() {
        val avin = listOf("/opt/homebrew/bin/avin", "/usr/local/bin/avin").map(::File).firstOrNull { it.canExecute() }
            ?: System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "avin") }.firstOrNull { it.canExecute() }
        if (avin == null) return
        val out = Files.createTempDirectory("avin-templates").toFile()
        try {
            writeAvinPackage(out, "p", AvinPackageKind.PROGRAM)
            val files = listOf(
                "shapes/solid/cube.avin" to create("pkg/shapes/solid", "cube").text + "volume(side: int) -> int = side * side * side\n",
                "données/über/fläche.avin" to create("pkg/données/über", "fläche").text + "f() -> int = 1\n",
                "tests/sample_tests.avin" to create("pkg/tests", "sample_tests", TEST_FILE_TEMPLATE).text,
                "shapes/shapes.avin" to createAvinModule(psiDir("pkg"), "shapes").text + "g() -> int = 2\n",
            )
            for ((path, text) in files) File(out, path).also { it.parentFile.mkdirs() }.writeText(text)
            val process = ProcessBuilder(avin.path, "check", out.path).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(120, TimeUnit.SECONDS))
            assertEquals(output, 0, process.exitValue())
        } finally {
            out.deleteRecursively()
        }
    }
}
