package dev.avinlang.intellij.run

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.avinlang.intellij.AvinSettings
import org.jetbrains.plugins.textmate.TextMateService
import java.io.File
import java.util.concurrent.TimeUnit

/** Run support in a running platform: the gutter, the configurations, the console and the test tree. */
class AvinRunPlatformTest : BasePlatformTestCase() {
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

    private val sample = """
        |clamp(n: int) -> int = n
        |
        |main() =
        |    print("clamped")
        |
        |@test("clamps below the low bound")
        |below()
        |    assertEq(clamp(0), 0)
        |
        |@test
        |leavesInRange()
        |    assert(true)
    """.trimMargin()

    private fun realDirectory(): File = FileUtil.createTempDirectory("avin-run", null, true)

    fun `test the gutter has a run icon on main, on each test, and one for the file's tests`() {
        myFixture.configureByText("clamp.avin", sample)
        val document = myFixture.editor.document
        val marks = myFixture.findAllGutters()
            .filterIsInstance<LineMarkerInfo.LineMarkerGutterIconRenderer<*>>()
            .map { document.getLineNumber(it.lineMarkerInfo.startOffset) to it.tooltipText }
            .sortedBy { it.first }
        assertEquals(
            listOf(
                0 to "Run 'Tests in clamp.avin'",
                2 to "Run 'clamp.avin'",
                6 to "Run 'clamps below the low bound'",
                10 to "Run 'leavesInRange'",
            ),
            marks,
        )
    }

    fun `test a file without main or tests has no run icons`() {
        myFixture.configureByText("lib.avin", "add(a: int, b: int) -> int = a + b\n")
        assertEquals(emptyList<Any>(), myFixture.findAllGutters().filterIsInstance<LineMarkerInfo.LineMarkerGutterIconRenderer<*>>())
    }

    fun `test in a package the file's icon offers the file's tests and the package's`() {
        val pkg = realDirectory()
        File(pkg, MANIFEST).writeText("package { name = \"p\" }")
        val file = File(pkg, "src/t.avin").apply { parentFile.mkdirs(); writeText(sample) }
        val launches = gutterLaunches(file, sample)
        assertEquals(
            listOf(Launch(AvinCommandKind.TEST, pkg, "src/t.avin", "Tests in src/t.avin"), Launch(AvinCommandKind.TEST, pkg, "", "Tests in ${pkg.name}")),
            launches.first { it.first == 0 }.second,
        )
        assertEquals(listOf(Launch(AvinCommandKind.RUN, pkg, "", pkg.name)), launches.first { it.first == 2 }.second)
    }

    fun `test a gutter launch makes a temporary configuration once and then reuses it`() {
        val program = File(realDirectory(), "clamp.avin").apply { writeText(sample) }
        val launch = Launch(AvinCommandKind.TEST, program, "leavesInRange", "leavesInRange")
        val first = configurationFor(project, launch)
        val configuration = first.configuration as AvinRunConfiguration
        assertTrue(first.isTemporary)
        assertEquals(AvinCommandKind.TEST, configuration.kind)
        assertEquals(program.path, configuration.program)
        assertEquals("leavesInRange", configuration.testFilter)
        assertSame(first, configurationFor(project, launch))
        assertNotSame(first, configurationFor(project, launch.copy(testFilter = "below", name = "below")))
        assertEquals(listOf("test", program.path, "leavesInRange"), configuration.commandLine().parametersList.list)
        assertEquals(program.parentFile, configuration.commandLine().workDirectory)
    }

    fun `test the console links a trap to its line`() {
        val dir = realDirectory()
        val file = File(dir, "main.avin").apply { writeText("main() =\n    print(\"x\")\n    assert(false)\n") }
        val line = "avin: trap: assertion failed at main.avin:3:5"
        val result = AvinSourceFilter(project, dir).applyFilter(line, 100 + line.length)!!
        val item = result.resultItems.single()
        assertEquals(100 + line.indexOf("main.avin"), item.highlightStartOffset)
        assertEquals(100 + line.length, item.highlightEndOffset)
        val link = item.hyperlinkInfo as OpenFileHyperlinkInfo
        assertEquals(file.path, link.descriptor!!.file.path)
        // Line 3, column 5: after "main() =\n" and "    print(\"x\")\n", four columns in.
        assertEquals(9 + 15 + 4, link.descriptor!!.offset)
        assertNull(AvinSourceFilter(project, dir).applyFilter("at missing.avin:1:1", 19))
    }

    fun `test the test locator finds a test's declaration and a trap's place`() {
        val file = File(realDirectory(), "clamp.avin").apply { writeText(sample) }
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!
        val scope = GlobalSearchScope.allScope(project)
        val declaration = AvinTestLocator.getLocation(TEST_PROTOCOL, "${file.path}::clamps below the low bound", project, scope).single()
        assertEquals(6, declaration.openFileDescriptor!!.line)
        val trap = AvinTestLocator.getLocation(SOURCE_PROTOCOL, "${file.path}:8:5", project, scope).single()
        assertEquals(7, trap.openFileDescriptor!!.line)
        assertEquals(4, trap.openFileDescriptor!!.column)
    }

    fun `test an avin test run fills the test tree`() {
        val dir = realDirectory()
        File(dir, "clamp.avin").writeText(sample)
        val report = """
            |running 2 tests
            |
            |clamp.avin
            |  ok    clamps below the low bound  3ms
            |  FAIL  leavesInRange               4ms
            |        assertion failed: four is left as it is at clamp.avin:12:5
            |        > clamping 4
            |
            |1 passed, 1 failed — 7ms
        """.trimMargin()
        File(dir, "report.txt").writeText(report + "\n")
        val avin = File(dir, "avin").apply { writeText("#!/bin/sh\ncat \"\$(dirname \"\$0\")/report.txt\"\nexit 1\n") }
        avin.setExecutable(true)
        AvinSettings.getInstance().serverPath = avin.path

        val settings = configurationFor(project, Launch(AvinCommandKind.TEST, File(dir, "clamp.avin"), "", "Tests"))
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val environment = ExecutionEnvironmentBuilder.create(executor, settings).build()
        val state = settings.configuration.getState(executor, environment) as AvinTestState
        val result = state.execute(executor, environment.runner)
        val console = result.executionConsole as SMTRunnerConsoleView
        Disposer.register(testRootDisposable, console)
        val handler = result.processHandler!!
        handler.startNotify()
        assertTrue(handler.waitFor(TimeUnit.SECONDS.toMillis(30)))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        val root = console.resultsViewer.testsRootNode
        val tests = root.allTests.filter { it.isLeaf }
        assertEquals(listOf("clamps below the low bound", "leavesInRange"), tests.map { it.name })
        assertTrue(tests[0].isPassed)
        assertTrue(tests[1].isDefect)
        assertEquals("assertion failed: four is left as it is at clamp.avin:12:5", tests[1].errorMessage)
        assertEquals("$SOURCE_PROTOCOL://${File(dir, "clamp.avin").path}:12:5", tests[1].locationUrl)
        assertEquals("$TEST_PROTOCOL://${File(dir, "clamp.avin").path}::clamps below the low bound", tests[0].locationUrl)
    }
}
