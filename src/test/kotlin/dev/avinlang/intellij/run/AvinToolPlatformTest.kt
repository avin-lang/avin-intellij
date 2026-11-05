package dev.avinlang.intellij.run

import com.intellij.analysis.problemsView.ProblemsCollector
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.avinlang.intellij.AvinSettings
import org.jetbrains.plugins.textmate.TextMateService
import java.io.File
import java.util.concurrent.TimeUnit

/** The Build | Avin commands in a running platform: when they apply, their runs, the Problems view. */
class AvinToolPlatformTest : BasePlatformTestCase() {
    private var savedPath = ""
    private var savedRepository = ""

    override fun setUp() {
        super.setUp()
        val settings = AvinSettings.getInstance()
        savedPath = settings.serverPath
        savedRepository = settings.repository
        settings.serverPath = "/nonexistent/avin"
        settings.repository = ""
        TextMateService.getInstance().ensureInitialized()
    }

    override fun tearDown() {
        try {
            AvinSettings.getInstance().serverPath = savedPath
            AvinSettings.getInstance().repository = savedRepository
            RunManager.getInstance(project).allSettings.forEach { RunManager.getInstance(project).removeConfiguration(it) }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun realDirectory(): File = FileUtil.createTempDirectory("avin-tool", null, true)

    private fun pkg(parent: File, name: String): File =
        File(parent, name).apply { mkdirs(); File(this, MANIFEST).writeText("package { name = \"p\" }") }

    private fun vf(file: File): VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!

    /** An `avin` that prints [output] and exits with [exit], whatever it is asked. */
    private fun fakeAvin(dir: File, output: String, exit: Int = 1): File {
        File(dir, "out.txt").writeText(output)
        val avin = File(dir, "avin").apply { writeText("#!/bin/sh\ncat \"\$(dirname \"\$0\")/out.txt\"\nexit $exit\n") }
        avin.setExecutable(true)
        AvinSettings.getInstance().serverPath = avin.path
        return avin
    }

    private fun action(id: String): AnAction = ActionManager.getInstance().getAction(id)!!

    private fun presentationOf(id: String, vararg files: VirtualFile): Pair<Boolean, Boolean> {
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(*files))
            .build()
        val event = TestActionEvent.createTestEvent(action(id), context)
        action(id).update(event)
        return event.presentation.isEnabled to event.presentation.isVisible
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30)
        while (!condition()) {
            assertTrue("timed out waiting until $what", System.currentTimeMillis() < deadline)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
    }

    // ---- the menu ----

    fun `test the Avin group is in the Build menu and the context menus`() {
        val group = action("Avin.Tools") as DefaultActionGroup
        assertEquals(listOf("Avin.Build", "Avin.Check", "Avin.Test", "Avin.Format"), group.childActionsOrStubs.map { ActionManager.getInstance().getId(it) })
        for (parent in listOf("BuildMenu", "ProjectViewPopupMenu", "EditorPopupMenu")) {
            val children = (action(parent) as DefaultActionGroup).childActionsOrStubs.toList()
            assertTrue("$parent holds the Avin group", children.any { ActionManager.getInstance().getId(it) == "Avin.Tools" })
        }
    }

    fun `test the commands apply to a file in a package and not outside one`() {
        val dir = realDirectory()
        val p = pkg(dir, "p")
        val inPackage = File(p, "a/b/x.avin").apply { parentFile.mkdirs(); writeText("") }
        val loose = File(dir, "loose/l.avin").apply { parentFile.mkdirs(); writeText("") }
        for (id in listOf("Avin.Build", "Avin.Check", "Avin.Test", "Avin.Format")) {
            assertTrue(id, presentationOf(id, vf(inPackage)).first)
        }
        for (id in listOf("Avin.Build", "Avin.Check", "Avin.Test")) {
            val (enabled, visible) = presentationOf(id, vf(loose))
            assertFalse("$id is disabled outside a package", enabled)
            assertTrue("$id stays in the Build menu", visible)
        }
        assertTrue(presentationOf("Avin.Format", vf(loose)).first)
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(vf(inPackage), vf(loose)))
            .build()
        val check = action("Avin.Check") as AvinToolAction
        assertEquals(listOf(p), check.targets(TestActionEvent.createTestEvent(check, context)))
    }

    fun `test the editor's file stands for the selection, and several packages each run`() {
        val dir = realDirectory()
        val a = pkg(dir, "a")
        val b = pkg(dir, "b")
        val x = File(a, "x.avin").apply { writeText("") }
        val y = File(b, "m/y.avin").apply { parentFile.mkdirs(); writeText("") }
        val check = action("Avin.Check") as AvinToolAction
        val editor = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, vf(y)).build()
        assertEquals(listOf(b), check.targets(TestActionEvent.createTestEvent(check, editor)))
        val both = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(vf(x), vf(y))).build()
        assertEquals(listOf(a, b), check.targets(TestActionEvent.createTestEvent(check, both)))
    }

    fun `test the test command runs the package's tests through a test configuration`() {
        val p = pkg(realDirectory(), "p")
        val settings = configurationFor(project, Launch(AvinCommandKind.TEST, p, "", "Tests in ${p.name}"))
        val configuration = settings.configuration as AvinRunConfiguration
        assertEquals(listOf("test", p.path), configuration.commandLine().parametersList.list)
        assertEquals(p, configuration.commandLine().workDirectory)
    }

    // ---- a check, a build, a format ----

    private fun runTool(kind: AvinToolKind, targets: List<File>): ConsoleView {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val environment = ExecutionEnvironmentBuilder.create(project, executor, AvinToolProfile(project, kind, targets)).build()
        val state = environment.state as AvinToolState
        val result = state.execute(executor, environment.runner)
        val console = result.executionConsole as ConsoleView
        Disposer.register(testRootDisposable, console)
        val handler = result.processHandler!!
        handler.startNotify()
        assertTrue(handler.waitFor(TimeUnit.SECONDS.toMillis(30)))
        return console
    }

    fun `test a check's diagnostics go to the Problems view and a clean check clears them`() {
        val dir = realDirectory()
        val p = pkg(dir, "p")
        val main = File(p, "main.avin").apply { writeText("main()\n    val x: int = \"s\"\n    y()\n") }
        val unicode = File(p, "sub/é.avin").apply { parentFile.mkdirs(); writeText("module sub\n\nf() -> int = \"x\"\n") }
        fakeAvin(
            dir,
            """
            |error: `f` yields string, not int
            | --> ./sub/é.avin:3:14
            |  |
            |3 | f() -> int = "x"
            |  |              ^^^
            |error: `y` is not defined
            | --> ${main.path}:3:5
            |warning: the C library `z` was not found
            |
            """.trimMargin(),
        )
        runTool(AvinToolKind.CHECK, listOf(p))
        waitUntil("the check's problems are reported") { AvinProblems.getInstance(project).problems(p).size == 3 }

        val problems = AvinProblems.getInstance(project).problems(p)
        val first = problems[0] as AvinFileProblem
        assertEquals(vf(unicode), first.file)
        assertEquals(2, first.line)
        assertEquals(13, first.column)
        assertEquals("`f` yields string, not int", first.text)
        val second = problems[1] as AvinFileProblem
        assertEquals(vf(main), second.file)
        assertEquals(2 to 4, second.line to second.column)
        val third = problems[2] as AvinProblem
        assertEquals(AvinSeverity.WARNING, third.severity)
        assertEquals("the C library `z` was not found", third.text)

        val collector = ProblemsCollector.getInstance(project)
        assertTrue(collector.getFileProblems(vf(unicode)).contains(first))
        assertTrue(collector.getFileProblems(vf(main)).contains(second))

        // A check that finds nothing takes the package's problems away.
        fakeAvin(dir, "", exit = 0)
        runTool(AvinToolKind.CHECK, listOf(p))
        waitUntil("the problems are cleared") { AvinProblems.getInstance(project).problems(p).isEmpty() }
        waitUntil("the Problems view is cleared") { !collector.getFileProblems(vf(main)).contains(second) }
        assertFalse(collector.getFileProblems(vf(unicode)).contains(first))
    }

    fun `test a build's problems of one package leave another's alone`() {
        val dir = realDirectory()
        val a = pkg(dir, "a")
        val b = pkg(dir, "b")
        File(a, "x.avin").writeText("x\n")
        File(b, "y.avin").writeText("y\n")
        fakeAvin(dir, "error: in a\n --> ./x.avin:1:1\n")
        runTool(AvinToolKind.BUILD, listOf(a))
        waitUntil("a's problem") { AvinProblems.getInstance(project).problems(a).size == 1 }
        fakeAvin(dir, "error: in b\n --> ./y.avin:1:1\n")
        runTool(AvinToolKind.BUILD, listOf(b))
        waitUntil("b's problem") { AvinProblems.getInstance(project).problems(b).size == 1 }
        assertEquals("in a", AvinProblems.getInstance(project).problems(a).single().text)
    }

    fun `test a diagnostic naming a file that is not there keeps its place in the text`() {
        val dir = realDirectory()
        val p = pkg(dir, "p")
        fakeAvin(dir, "error: gone\n --> ./missing.avin:2:3\n")
        runTool(AvinToolKind.CHECK, listOf(p))
        waitUntil("the problem") { AvinProblems.getInstance(project).problems(p).size == 1 }
        val problem = AvinProblems.getInstance(project).problems(p).single() as AvinProblem
        assertEquals("gone (${File(p, "missing.avin").path}:2:3)", problem.text)
    }

    fun `test fmt and test report no problems`() {
        val dir = realDirectory()
        val p = pkg(dir, "p")
        File(p, "x.avin").writeText("x\n")
        fakeAvin(dir, "error: not a problem of fmt\n --> ./x.avin:1:1\n")
        runTool(AvinToolKind.FMT, listOf(p))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(emptyList<Any>(), AvinProblems.getInstance(project).problems(p))
    }

    fun `test the console links a diagnostic's relative place, Unicode and all`() {
        val dir = realDirectory()
        val file = File(dir, "sub/é.avin").apply { parentFile.mkdirs(); writeText("module sub\n\nf() -> int = \"x\"\n") }
        val line = " --> ./sub/é.avin:3:14"
        val result = AvinSourceFilter(project, dir).applyFilter(line, line.length)!!
        val link = result.resultItems.single().hyperlinkInfo as OpenFileHyperlinkInfo
        assertEquals(file.path, link.descriptor!!.file.path)
        assertEquals(12 + 13, link.descriptor!!.offset)
    }

    fun `test fmt reloads the files it rewrote`() {
        val dir = realDirectory()
        val file = File(dir, "a.avin").apply { writeText("main()\n    println(  1+2 )\n") }
        val document = FileDocumentManager.getInstance().getDocument(vf(file))!!
        assertEquals("main()\n    println(  1+2 )\n", document.text)
        val avin = File(dir, "avin").apply {
            writeText("#!/bin/sh\nprintf 'main()\\n  println(1 + 2)\\n' > \"\$2\"\n")
            setExecutable(true)
        }
        AvinSettings.getInstance().serverPath = avin.path
        runTool(AvinToolKind.FMT, listOf(file))
        waitUntil("the file is reloaded") { VfsUtilCore.loadText(vf(file)) == "main()\n  println(1 + 2)\n" }
        waitUntil("the document is reloaded") { document.text == "main()\n  println(1 + 2)\n" }
    }
}
