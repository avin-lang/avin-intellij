package dev.avinlang.intellij

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.plugins.textmate.TextMateFileType
import org.jetbrains.plugins.textmate.TextMateService
import org.jetbrains.plugins.textmate.language.syntax.highlighting.TextMateEditorHighlighterProvider
import java.awt.Font

/** The prose of a literate file has colours of its own, and the program keeps the TextMate ones. */
class LiterateProseTest : BasePlatformTestCase() {
    private data class Token(val start: Int, val end: Int, val text: String, val scopes: List<String>, val keys: List<TextAttributesKey>, val attributes: TextAttributes)

    override fun setUp() {
        super.setUp()
        AvinSettings.getInstance().serverPath = "/nonexistent/avin"
        TextMateService.getInstance().ensureInitialized()
    }

    private val sample = """
        # Counting words

        Plain *emphasis*, **strong**, ~~struck~~ and `code` with [a link](https://avin.dev) and \*.

            main() =
                val total = 1 + 2 // a comment
                println("total: ${'$'}{total}")

        - a list item with *emphasis*
        1. a numbered item

        > a quote with *emphasis*

        ```text
        an illustration
        ```

        ~~~avin
        shown(n) = n + 1
        ~~~

        ---

            last() = "done"
    """.trimIndent() + "\n"

    private fun tokensOf(highlighter: EditorHighlighter, text: String): List<Token> {
        val tokens = ArrayList<Token>()
        val it = highlighter.createIterator(0)
        while (!it.atEnd()) {
            tokens += Token(it.start, it.end, text.substring(it.start, it.end), scopesOf(it.tokenType), it.textAttributesKeys.toList(), it.textAttributes)
            it.advance()
        }
        return tokens
    }

    /** The highlighter the editor uses for `name`, and the TextMate plugin's own for the same text. */
    private fun ourAndTextMate(name: String, text: String): Pair<List<Token>, List<Token>> {
        myFixture.configureByText(name, text)
        val editor = myFixture.editor as EditorEx
        val scheme: EditorColorsScheme = editor.colorsScheme
        val textMate = TextMateEditorHighlighterProvider().getEditorHighlighter(project, TextMateFileType.INSTANCE, myFixture.file.virtualFile, scheme)
        textMate.setText(text)
        return tokensOf(editor.highlighter, text) to tokensOf(textMate, text)
    }

    private fun Token.isProgram() = LiterateProse.PROGRAM_SCOPE in scopes

    private fun at(tokens: List<Token>, offset: Int) = tokens.first { offset >= it.start && offset < it.end }

    fun `test a lavin editor uses the literate highlighter`() {
        myFixture.configureByText("notes.lavin", sample)
        val highlighter = (myFixture.editor as EditorEx).highlighter
        assertTrue(highlighter is LexerEditorHighlighter)
        assertTrue((highlighter as LexerEditorHighlighter).syntaxHighlighter is LiterateAvinHighlighter)
    }

    fun `test the program of a lavin file has exactly the TextMate attributes`() {
        val (ours, textMate) = ourAndTextMate("notes.lavin", sample)
        assertEquals(textMate.map { it.start to it.end }, ours.map { it.start to it.end })
        val program = ours.indices.filter { ours[it].isProgram() }
        assertTrue("the sample has a program", program.size > 10)
        assertTrue("the program is coloured as Avin", program.any { i -> ours[i].scopes.any { it.startsWith("keyword") } })
        assertTrue("the program is coloured as Avin", program.any { i -> ours[i].scopes.any { it.startsWith("comment") } })
        for (i in program) {
            assertEquals(ours[i].text, textMate[i].keys, ours[i].keys)
            assertEquals(ours[i].text, textMate[i].attributes, ours[i].attributes)
        }
    }

    fun `test an avin fence inside the prose is coloured as Avin`() {
        val (ours, textMate) = ourAndTextMate("notes.lavin", sample)
        val shown = sample.indexOf("shown(n)")
        val inFence = ours.indices.filter { ours[it].start >= shown && ours[it].end <= sample.indexOf("~~~", shown) }
        assertTrue(inFence.isNotEmpty())
        for (i in inFence) {
            assertTrue(LiterateProse.PROSE_SCOPE in ours[i].scopes)
            assertEquals(ours[i].text, textMate[i].keys, ours[i].keys)
        }
    }

    fun `test each kind of prose has its own key`() {
        val (ours, _) = ourAndTextMate("notes.lavin", sample)
        fun keysAt(fragment: String, skip: Int = 0, from: Int = 0) = at(ours, sample.indexOf(fragment, from) + skip).keys
        assertTrue(LiterateProse.HEADING in keysAt("# Counting"))
        assertTrue(LiterateProse.HEADING in keysAt("Counting"))
        assertEquals(listOf(LiterateProse.TEXT), keysAt("Plain"))
        assertTrue(LiterateProse.EMPHASIS in keysAt("*emphasis*", 1))
        assertTrue(LiterateProse.STRONG in keysAt("**strong**", 2))
        assertTrue(LiterateProse.STRIKETHROUGH in keysAt("~~struck~~", 2))
        assertTrue(LiterateProse.CODE in keysAt("`code`", 1))
        assertTrue(LiterateProse.LINK in keysAt("[a link]", 1))
        assertTrue(LiterateProse.LINK in keysAt("https://avin.dev"))
        assertTrue(LiterateProse.LIST_MARKER in keysAt("- a list"))
        assertTrue(LiterateProse.LIST_MARKER in keysAt("1. a numbered"))
        assertTrue(LiterateProse.QUOTE in keysAt("> a quote"))
        val quoted = keysAt("*emphasis*", 1, sample.indexOf("> a quote"))
        assertTrue("an emphasis in a quote is both", LiterateProse.QUOTE in quoted && LiterateProse.EMPHASIS in quoted)
        assertTrue(LiterateProse.FENCE in keysAt("```text"))
        assertTrue(LiterateProse.FENCE in keysAt("an illustration"))
        assertTrue(LiterateProse.FENCE in keysAt("~~~avin"))
        assertTrue(LiterateProse.RULE in keysAt("---"))
        for (t in ours.filter { !it.isProgram() && it.text.isNotBlank() }) assertEquals(t.text, LiterateProse.TEXT, t.keys.first())
    }

    fun `test an avin file keeps the TextMate highlighter`() {
        val text = "main() =\n    val x = 1 // note\n    println(\"x\")\n"
        val (ours, textMate) = ourAndTextMate("main.avin", text)
        assertFalse(((myFixture.editor as EditorEx).highlighter as? LexerEditorHighlighter)?.syntaxHighlighter is LiterateAvinHighlighter)
        assertEquals(textMate.map { Triple(it.start, it.end, it.keys) }, ours.map { Triple(it.start, it.end, it.keys) })
        assertEquals(textMate.map { it.attributes }, ours.map { it.attributes })
    }

    fun `test the color settings page names every prose key and shows each in its demo`() {
        val page = AvinColorSettingsPage()
        assertEquals("Avin", page.displayName)
        val keys = page.attributeDescriptors.map { it.key }.toSet()
        assertEquals(AvinColorSettingsPage.TAGS.values.toSet(), keys)
        for (d in page.attributeDescriptors) assertTrue(d.displayName, d.displayName.startsWith("Literate Avin prose//"))
        for (tag in page.additionalHighlightingTagToDescriptorMap.keys) assertTrue(tag, "<$tag>" in page.demoText)
        assertTrue(page.highlighter is LiterateAvinHighlighter)
    }

    fun `test the bundled schemes set the emphasis italic, strong bold and strikethrough struck out`() {
        for (name in listOf("Default", "Darcula")) {
            val scheme = EditorColorsManager.getInstance().getScheme(name)
            assertNotNull(name, scheme)
            assertEquals(name, Font.ITALIC, scheme!!.getAttributes(LiterateProse.EMPHASIS).fontType)
            assertEquals(name, Font.BOLD, scheme.getAttributes(LiterateProse.STRONG).fontType)
            assertEquals(name, EffectType.STRIKEOUT, scheme.getAttributes(LiterateProse.STRIKETHROUGH).effectType)
            assertNotNull(name, scheme.getAttributes(LiterateProse.STRIKETHROUGH).effectColor)
        }
    }
}
