package dev.avinlang.intellij.run

import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageVisitor
import java.io.File

/** The name the test runner knows Avin's tests by. */
const val TEST_FRAMEWORK = "Avin"

/** The service message that tells the test tree [event], or null for text shown as it is. */
fun serviceMessage(event: TestEvent): String? = when (event) {
    is TestEvent.Count -> ServiceMessageBuilder("testCount").addAttribute("count", event.count.toString())
    is TestEvent.SuiteStarted -> ServiceMessageBuilder.testSuiteStarted(event.name).addAttribute("locationHint", event.location)
    is TestEvent.SuiteFinished -> ServiceMessageBuilder.testSuiteFinished(event.name)
    is TestEvent.Started -> ServiceMessageBuilder.testStarted(event.name).addAttribute("locationHint", event.location)
    is TestEvent.Output -> ServiceMessageBuilder.testStdOut(event.name).addAttribute("out", event.text)
    is TestEvent.Failed -> ServiceMessageBuilder.testFailed(event.name).addAttribute("message", event.message)
    is TestEvent.Finished -> ServiceMessageBuilder.testFinished(event.name).addAttribute("duration", event.durationMs.toString())
    is TestEvent.Text -> null
}?.toString()

/** Turns `avin test`'s report into the test tree's events; other output is shown as it is. */
class AvinTestEventsConverter(properties: TestConsoleProperties, root: File) :
    OutputToGeneralTestEventsConverter(TEST_FRAMEWORK, properties) {
    private val parser = AvinTestOutputParser(root)

    override fun processServiceMessages(text: String, outputType: Key<*>, visitor: ServiceMessageVisitor): Boolean {
        if (text.startsWith("##teamcity[")) return super.processServiceMessages(text, outputType, visitor)
        if (outputType != ProcessOutputTypes.STDOUT) return false
        val events = parser.feed(text)
        if (events.size == 1 && events[0] is TestEvent.Text) return false
        for (event in events) {
            val message = serviceMessage(event)
            if (message != null) {
                super.processServiceMessages(message, outputType, visitor)
            } else if (event is TestEvent.Text) {
                fireOnUncapturedOutput(event.line + "\n", outputType)
            }
        }
        return true
    }

    override fun flushBufferOnProcessTermination(exitCode: Int) {
        for (event in parser.finish()) {
            serviceMessage(event)?.let { process(it + "\n", ProcessOutputTypes.STDOUT) }
        }
        super.flushBufferOnProcessTermination(exitCode)
    }
}

/** The console of an `avin test` run: its report in the test tree, with places linked to the source. */
class AvinTestConsoleProperties(
    configuration: RunConfiguration,
    executor: Executor,
    private val root: File,
) : SMTRunnerConsoleProperties(configuration, TEST_FRAMEWORK, executor), SMCustomMessagesParsing {
    init {
        addStackTraceFilter(AvinSourceFilter(configuration.project, root))
    }

    override fun createTestEventsConverter(testFrameworkName: String, consoleProperties: TestConsoleProperties): OutputToGeneralTestEventsConverter =
        AvinTestEventsConverter(consoleProperties, root)

    override fun getTestLocator(): SMTestLocator = AvinTestLocator
}

/** A place in an Avin file. The file has no finer syntax tree, so the place is a line and column. */
class AvinSourceLocation(project: Project, file: PsiFile, private val line: Int, private val column: Int) :
    PsiLocation<PsiFile>(project, file) {
    override fun getOpenFileDescriptor(): OpenFileDescriptor =
        OpenFileDescriptor(project, psiElement.virtualFile, line, column)

    override fun getNavigatable(): Navigatable = openFileDescriptor
}

private val PLACE = Regex("""^(.*):(\d+):(\d+)$""")
private val NAMED_TEST = Regex("""^(.*?\.l?avin)::(.*)$""", RegexOption.DOT_MATCHES_ALL)

/** Finds the places the parser's locations name: a line and column, or a test's declaration. */
object AvinTestLocator : SMTestLocator {
    override fun getLocation(protocol: String, path: String, project: Project, scope: GlobalSearchScope): List<Location<*>> {
        when (protocol) {
            SOURCE_PROTOCOL -> {
                val m = PLACE.find(path) ?: return emptyList()
                val file = psiFile(project, m.groupValues[1]) ?: return emptyList()
                return listOf(AvinSourceLocation(project, file, m.groupValues[2].toInt() - 1, m.groupValues[3].toInt() - 1))
            }
            TEST_PROTOCOL -> {
                val m = NAMED_TEST.find(path) ?: return emptyList()
                val file = psiFile(project, m.groupValues[1]) ?: return emptyList()
                val text = FileDocumentManager.getInstance().getDocument(file.virtualFile)?.charsSequence ?: file.text
                val line = scanRunTargets(file.name, text)
                    .filterIsInstance<TestTarget>()
                    .firstOrNull { it.name == m.groupValues[2] }
                    ?.line ?: 0
                return listOf(AvinSourceLocation(project, file, line, 0))
            }
        }
        return emptyList()
    }

    private fun psiFile(project: Project, filePath: String): PsiFile? {
        val vf = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return null
        return PsiManager.getInstance(project).findFile(vf)
    }
}
