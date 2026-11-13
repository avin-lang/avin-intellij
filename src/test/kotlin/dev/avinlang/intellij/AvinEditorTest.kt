package dev.avinlang.intellij

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.plugins.textmate.TextMateFileType
import org.jetbrains.plugins.textmate.TextMateService
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider

/** The plugin in a running platform: the bundle is found, and an Avin file gets its editor support. */
class AvinEditorTest : BasePlatformTestCase() {
    private var savedPath = ""

    override fun setUp() {
        super.setUp()
        // No server in these tests: a path that does not exist keeps it off.
        val settings = AvinSettings.getInstance()
        savedPath = settings.serverPath
        settings.serverPath = "/nonexistent/avin"
        // Load the bundles once, synchronously; reloading them here would race the editor tests.
        TextMateService.getInstance().ensureInitialized()
    }

    override fun tearDown() {
        try {
            AvinSettings.getInstance().serverPath = savedPath
        } finally {
            super.tearDown()
        }
    }

    fun `test the bundle provider finds the bundle placed beside the plugin`() {
        val bundles = TextMateBundleProvider.EP_NAME.extensionList.flatMap { it.getBundles() }.filter { it.name == "Avin" }
        assertEquals(1, bundles.size)
        assertTrue(bundles[0].path.resolve("syntaxes/avin.tmLanguage.json").toFile().isFile)
    }

    fun `test an avin file is highlighted by the Avin grammar`() {
        val descriptor = TextMateService.getInstance().getLanguageDescriptorByExtension("avin")
        assertNotNull("no TextMate language for .avin", descriptor)
        assertEquals("source.avin", descriptor!!.rootScopeName.toString())
        val file = myFixture.configureByText("main.avin", "main() =\n    println(\"hi\")\n")
        assertEquals(TextMateFileType.INSTANCE, file.virtualFile.fileType)
    }

    fun `test the line comment is double slash`() {
        myFixture.configureByText("main.avin", "<caret>val x = 1\n")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        assertEquals("// val x = 1\n", myFixture.editor.document.text)
    }

    fun `test brackets and quotes close as they are typed`() {
        myFixture.configureByText("main.avin", "<caret>\n")
        myFixture.type("f(")
        assertEquals("f()\n", myFixture.editor.document.text)
        assertEquals(2, myFixture.editor.caretModel.offset)
    }

    fun `test a lavin file is highlighted by the literate grammar`() {
        val descriptor = TextMateService.getInstance().getLanguageDescriptorByExtension("lavin")
        assertNotNull("no TextMate language for .lavin", descriptor)
        assertEquals("text.lavin", descriptor!!.rootScopeName.toString())
        val file = myFixture.configureByText("notes.lavin", "# Title\n\nProse.\n\n    main() =\n        println(\"hi\")\n")
        assertEquals(TextMateFileType.INSTANCE, file.virtualFile.fileType)
    }

    fun `test a lavin file has the Avin icon`() {
        val file = myFixture.configureByText("notes.lavin", "").virtualFile
        val icons = FileIconProvider.EP_NAME.extensionList.mapNotNull { it.getIcon(file, 0, project) }
        assertSame(AvinIcons.FILE, icons.first())
    }

    fun `test a sysl file is highlighted by the Sysl grammar`() {
        val descriptor = TextMateService.getInstance().getLanguageDescriptorByExtension("sysl")
        assertNotNull("no TextMate language for .sysl", descriptor)
        assertEquals("source.sysl", descriptor!!.rootScopeName.toString())
        val text = "// note\nmain() =\n    while true do\n        break\n"
        val file = myFixture.configureByText("main.sysl", text)
        assertEquals(TextMateFileType.INSTANCE, file.virtualFile.fileType)
        val scopes = HashMap<String, List<String>>()
        val it = (myFixture.editor as EditorEx).highlighter.createIterator(0)
        while (!it.atEnd()) {
            scopes[text.substring(it.start, it.end).trim()] = scopesOf(it.tokenType)
            it.advance()
        }
        assertTrue(scopes.toString(), scopes["while"].orEmpty().any { it.startsWith("keyword.control") })
        assertTrue(scopes.toString(), scopes.entries.any { (k, v) -> k.contains("note") && v.any { it.startsWith("comment.line") } })
        assertTrue(scopes.getValue("while").none { it.contains("avin") })
    }

    fun `test an avin file highlights type aliases, export, with and placeholder lambdas`() {
        val text = "type Parsed[T] = Result[T, string]\nexport geo.inner.{Box, area as surface}\n" +
            "val moved = p with { x = 3 }\nval doubled = xs.map(_ * 2)\nval u: int | string = 1\n"
        val file = myFixture.configureByText("main.avin", text)
        assertEquals(TextMateFileType.INSTANCE, file.virtualFile.fileType)
        val scopes = HashMap<String, List<String>>()
        val it = (myFixture.editor as EditorEx).highlighter.createIterator(0)
        while (!it.atEnd()) {
            scopes.putIfAbsent(text.substring(it.start, it.end).trim(), scopesOf(it.tokenType))
            it.advance()
        }
        assertTrue(scopes.toString(), scopes.getValue("type").any { it.startsWith("keyword.declaration") })
        assertTrue(scopes.toString(), scopes.getValue("Parsed").any { it.startsWith("entity.name.type") })
        assertTrue(scopes.toString(), scopes.getValue("export").any { it.startsWith("keyword.control.import") })
        assertTrue(scopes.toString(), scopes.getValue("with").any { it.startsWith("keyword.operator.word") })
        assertTrue(scopes.toString(), scopes.getValue("_").any { it.startsWith("variable.language.placeholder") })
    }

    fun `test an avin file highlights def and defer as keywords`() {
        val text = "def area(w: int) -> int = w\nmain() =\n    defer close()\n    val defers = 1\n"
        myFixture.configureByText("main.avin", text)
        val scopes = HashMap<String, List<String>>()
        val it = (myFixture.editor as EditorEx).highlighter.createIterator(0)
        while (!it.atEnd()) {
            scopes.putIfAbsent(text.substring(it.start, it.end).trim(), scopesOf(it.tokenType))
            it.advance()
        }
        assertTrue(scopes.toString(), scopes.getValue("def").any { it.startsWith("keyword.declaration") })
        assertTrue(scopes.toString(), scopes.getValue("defer").any { it.startsWith("keyword.control") })
        assertTrue(scopes.toString(), scopes.getValue("defers").none { it.startsWith("keyword") })
    }

    fun `test the sysl line and block comments`() {
        myFixture.configureByText("main.sysl", "<caret>x = 1\n")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        assertEquals("// x = 1\n", myFixture.editor.document.text)
        myFixture.configureByText("b.sysl", "<selection>x = 1</selection>\n")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_BLOCK)
        assertEquals("/* x = 1 */\n", myFixture.editor.document.text)
    }

    fun `test a sysl file has the Sysl icon and no other file gets it`() {
        val file = myFixture.configureByText("main.sysl", "").virtualFile
        val icons = FileIconProvider.EP_NAME.extensionList.mapNotNull { it.getIcon(file, 0, project) }
        assertSame(AvinIcons.SYSL, icons.first())
        assertNotSame(AvinIcons.SYSL, AvinIcons.FILE)
    }

    fun `test an avin file has the Avin icon`() {
        val file = myFixture.configureByText("main.avin", "").virtualFile
        val icons = FileIconProvider.EP_NAME.extensionList.mapNotNull { it.getIcon(file, 0, project) }
        assertSame(AvinIcons.FILE, icons.first())
    }
}
