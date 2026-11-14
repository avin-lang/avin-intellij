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

    /** The scopes of the token covering the first [word] on the 1-based [line] of the open file. */
    private fun wordScopes(text: String, line: Int, word: String): List<String> {
        val lineStart = text.lines().take(line - 1).sumOf { it.length + 1 }
        val offset = text.indexOf(word, lineStart)
        assertTrue("$word not on line $line", offset in lineStart until lineStart + text.lines()[line - 1].length)
        val it = (myFixture.editor as EditorEx).highlighter.createIterator(offset)
        return scopesOf(it.tokenType)
    }

    private fun assertKeyword(text: String, line: Int, word: String) {
        val scopes = wordScopes(text, line, word)
        assertTrue("$word on line $line: $scopes", scopes.any { it.startsWith("keyword") || it.startsWith("storage.modifier") })
    }

    private fun assertIdentifier(text: String, line: Int, word: String) {
        val scopes = wordScopes(text, line, word)
        assertTrue("$word on line $line: $scopes", scopes.none { it.startsWith("keyword") || it.startsWith("storage.modifier") })
    }

    fun `test the declaration words are keywords where they begin a declaration`() {
        val text = listOf(
            "data Shape",
            "class Record(x: int)",
            "object Registry",
            "trait Named",
            "impl Named for Record",
            "impl[A] Named for Box[A]",
            "impl[A, B] (A, B)",
            "import geo.shapes",
            "module geo",
            "open class Base",
            "abstract class Shape",
            "private data Hidden(n: int)",
            "",
        ).joinToString("\n")
        myFixture.configureByText("main.avin", text)
        val words = listOf(
            1 to "data", 2 to "class", 3 to "object", 4 to "trait", 5 to "impl", 6 to "impl", 7 to "impl",
            8 to "import", 9 to "module", 10 to "open", 10 to "class", 11 to "abstract", 11 to "class", 12 to "data",
        )
        for ((line, word) in words) assertKeyword(text, line, word)
    }

    fun `test the declaration words are ordinary names everywhere else`() {
        val text = listOf(
            "class Record(val data: string, val class: int, val type: string)",
            "    object() -> string = s\"object of \$data\"",
            "    trait -> int = class * 2",
            "    def impl = data.charCount",
            "object Registry",
            "    val import = 3",
            "    var module = \"m\"",
            "    impl(n: int) -> int = n + import",
            "    data(n: int) -> int = n * 10",
            "abstract class Base",
            "    abstract(x: int) -> int",
            "    open(x: int) -> int = abstract(x) + 1",
            "class Derived: Base",
            "    override abstract(x: int) -> int = x * 3",
            "data(x: int) -> int = x + 1",
            "class(x: int) -> int = x + 2",
            "impl(x: int) -> int = x + 5",
            "import(x: int) -> int = x + 6",
            "module(x: int) -> int = x + 7",
            "main()",
            "    val data = \"payload\"",
            "    var object = class + 1",
            "    val r = Record(data = \"d\", class = 7, type = \"t\")",
            "    println(r.type)",
            "    println(r.class)",
            "    println(open + abstract)",
            "    println(impl(1) + module(2) + import(3))",
            "",
        ).joinToString("\n")
        myFixture.configureByText("main.avin", text)
        val words = listOf(
            1 to "data", 1 to "type",
            2 to "object", 3 to "trait", 3 to "class", 4 to "impl", 4 to "data",
            6 to "import", 7 to "module", 8 to "impl", 9 to "data",
            11 to "abstract", 12 to "open", 12 to "abstract",
            14 to "abstract",
            15 to "data", 16 to "class", 17 to "impl", 18 to "import", 19 to "module",
            21 to "data", 22 to "object", 22 to "class", 23 to "data", 23 to "class", 23 to "type",
            24 to "type", 25 to "class", 26 to "open", 26 to "abstract",
            27 to "impl", 27 to "module", 27 to "import",
        )
        for ((line, word) in words) assertIdentifier(text, line, word)
        assertKeyword(text, 10, "abstract")
        assertKeyword(text, 10, "class")
        assertKeyword(text, 14, "override")
    }

    fun `test impl with type parameters before a type is an impl`() {
        val text = "impl[A] Named for Box[A]\nimpl[A, B] (A, B)\n"
        myFixture.configureByText("main.avin", text)
        assertKeyword(text, 1, "impl")
        assertKeyword(text, 2, "impl")
    }

    fun `test the reserved words stay keywords`() {
        val text = listOf(
            "main()",
            "    val x = if a then b else c",
            "    while go do break",
            "    for i in xs do continue",
            "    loop",
            "        defer close()",
            "        return x",
            "    val y = v is not Foo",
            "    val z = v match",
            "    yield 1",
            "",
        ).joinToString("\n")
        myFixture.configureByText("main.avin", text)
        val words = listOf(
            2 to "val", 2 to "if", 2 to "then", 2 to "else", 3 to "while", 3 to "do", 3 to "break",
            4 to "for", 4 to "continue", 5 to "loop", 6 to "defer", 7 to "return", 8 to "is", 10 to "yield",
        )
        for ((line, word) in words) assertKeyword(text, line, word)
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
