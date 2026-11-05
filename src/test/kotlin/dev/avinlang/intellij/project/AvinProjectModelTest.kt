package dev.avinlang.intellij.project

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.NewProjectWizardBaseStep
import com.intellij.ide.wizard.RootNewProjectWizardStep
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import com.intellij.util.ui.UIUtil
import java.io.File

/** Packages are recognised in a project: sources, tests and build output marked. */
class AvinProjectModelTest : HeavyPlatformTestCase() {
    private lateinit var base: File

    override fun setUp() {
        super.setUp()
        base = createTempDir("avin-model")
    }

    private fun write(path: String, text: String = "") {
        val file = File(base, path)
        file.parentFile.mkdirs()
        file.writeText(text)
    }

    private fun dir(path: String) = File(base, path).mkdirs()

    private fun contentRoot(): VirtualFile {
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(base)!!
        root.refresh(false, true)
        PsiTestUtil.addContentRoot(module, root)
        return root
    }

    private fun url(path: String) = "file://" + File(base, path).path.let { if (path.isEmpty()) base.path else it }

    private fun entry() = ModuleRootManager.getInstance(module).contentEntries.single()
    private fun sources() = entry().sourceFolders.filter { !it.isTestSource }.map { it.url }.toSet()
    private fun tests() = entry().sourceFolders.filter { it.isTestSource }.map { it.url }.toSet()
    private fun excluded() = entry().excludeFolderUrls.toSet()

    private val manifest = "package {\n  name = \"p\"\n  version = \"0.1.0\"\n}\n"

    fun `test a package at the content root is recognised`() {
        write("avin.hocon", manifest)
        write("main.avin")
        write("tests/t.avin")
        contentRoot()
        AvinProjectModel.configure(project)
        assertEquals(setOf(url("")), sources())
        assertEquals(setOf(url("tests")), tests())
        assertEquals(setOf(url(".avin")), excluded())
    }

    fun `test tests and the build directory are marked before they exist`() {
        write("avin.hocon", manifest)
        contentRoot()
        AvinProjectModel.configure(project)
        assertEquals(setOf(url("tests")), tests())
        assertEquals(setOf(url(".avin")), excluded())
    }

    fun `test a directory without avin hocon is left alone`() {
        write("main.avin")
        write("tests/t.avin")
        dir(".avin/build")
        contentRoot()
        AvinProjectModel.configure(project)
        assertEmpty(sources())
        assertEmpty(tests())
        assertEmpty(excluded())
    }

    fun `test two packages in one window are each recognised`() {
        write("app/avin.hocon", manifest)
        write("app/main.avin")
        write("libs/geometry/avin.hocon", manifest)
        write("libs/geometry/geometry/g.avin")
        contentRoot()
        AvinProjectModel.configure(project)
        assertEquals(setOf(url("app"), url("libs/geometry")), sources())
        assertEquals(setOf(url("app/tests"), url("libs/geometry/tests")), tests())
        assertEquals(setOf(url("app/.avin"), url("libs/geometry/.avin")), excluded())
    }

    fun `test a workspace root holds no source of its own but its members do`() {
        write("avin.hocon", "workspace = [\"app\"]\n")
        write("app/avin.hocon", manifest)
        contentRoot()
        AvinProjectModel.configure(project)
        assertEquals(setOf(url("app")), sources())
        assertEquals(setOf(url("app/tests")), tests())
        assertEquals(setOf(url(".avin"), url("app/.avin")), excluded())
    }

    fun `test vendored packages and build output are not searched`() {
        write("avin.hocon", manifest)
        write("vendor/dep/avin.hocon", manifest)
        write(".avin/cache/x/avin.hocon", manifest)
        write(".git/x/avin.hocon", manifest)
        contentRoot()
        AvinProjectModel.configure(project)
        assertEquals(setOf(url("")), sources())
    }

    fun `test a sysl package's working directory is excluded`() {
        write("avin.hocon", manifest)
        write("bindings/sysl.hocon", "package {\n}\n")
        dir("bindings/.sysl")
        contentRoot()
        AvinProjectModel.configure(project)
        assertEquals(setOf(url(".avin"), url("bindings/.sysl")), excluded())
    }

    fun `test unicode directory names are recognised`() {
        write("données/avin.hocon", manifest)
        write("données/über/x.avin")
        contentRoot()
        AvinProjectModel.configure(project)
        assertEquals(setOf(url("données")), sources())
        assertEquals(setOf(url("données/.avin")), excluded())
    }

    fun `test recognising again changes nothing and keeps the user's own folders`() {
        write("avin.hocon", manifest)
        val root = contentRoot()
        ModuleRootModificationUtil.updateModel(module) { it.contentEntries.single().addExcludeFolder(root.url + "/docs") }
        AvinProjectModel.configure(project)
        val first = Triple(sources(), tests(), excluded())
        assertTrue(AvinProjectModel.plan(project).isEmpty())
        AvinProjectModel.configure(project)
        assertEquals(first, Triple(sources(), tests(), excluded()))
        assertTrue(url("docs") in excluded())
    }

    fun `test a manifest appearing later is recognised`() {
        val root = contentRoot()
        assertEmpty(sources())
        write("late/avin.hocon", manifest)
        root.refresh(false, true)
        UIUtil.dispatchAllInvocationEvents()
        assertEquals(setOf(url("late")), sources())
    }

    fun `test the wizard writes the package and its module is recognised`() {
        val context = WizardContext(project, testRootDisposable)
        val baseStep = NewProjectWizardBaseStep(RootNewProjectWizardStep(context))
        baseStep.name = "Geom Tools"
        baseStep.path = base.path
        val step = AvinProjectStep(baseStep)
        assertEquals("geom-tools", step.packageNameProperty.get())
        step.kindProperty.set(AvinPackageKind.LIBRARY)
        step.descriptionProperty.set("Plane shapes")
        step.setupProject(project)
        UIUtil.dispatchAllInvocationEvents()
        val root = File(base, "Geom Tools")
        assertTrue(File(root, "avin.hocon").readText().contains("name = \"geom-tools\"\n  version = \"0.1.0\"\n  description = \"Plane shapes\""))
        assertTrue(File(root, "geom_tools/geom_tools.avin").isFile)
        assertTrue(File(root, "tests/geom_tools_tests.avin").isFile)
        assertFalse(File(root, "main.avin").exists())
        val created = ModuleManager.getInstance(project).modules.single { it.name == "Geom Tools" }
        val entry = ModuleRootManager.getInstance(created).contentEntries.single()
        val rootUrl = "file://" + root.path
        assertEquals(setOf(rootUrl), entry.sourceFolders.filter { !it.isTestSource }.map { it.url }.toSet())
        assertEquals(setOf("$rootUrl/tests"), entry.sourceFolders.filter { it.isTestSource }.map { it.url }.toSet())
        assertEquals(setOf("$rootUrl/.avin"), entry.excludeFolderUrls.toSet())
    }

    fun `test a package outside every content root is not reached`() {
        write("inside/main.avin")
        write("outside/avin.hocon", manifest)
        val inside = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(base, "inside"))!!
        PsiTestUtil.addContentRoot(module, inside)
        AvinProjectModel.configure(project)
        assertEmpty(sources())
    }
}
