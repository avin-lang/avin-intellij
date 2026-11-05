package dev.avinlang.intellij

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.editor.ex.util.DataStorage
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.fileTypes.EditorHighlighterProvider
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType
import org.jetbrains.plugins.textmate.language.syntax.highlighting.TextMateEditorHighlighterProvider
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateElementType
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateLexerDataStorage

/**
 * The colours of the prose of a literate `.lavin` file, set in Settings | Editor | Color Scheme | Avin.
 * The grammar puts every line that is not the program under the scope `meta.prose.lavin`; these
 * keys colour what is under it, and the program keeps the colours of the TextMate highlighter.
 */
object LiterateProse {
    const val PROSE_SCOPE = "meta.prose.lavin"
    const val PROGRAM_SCOPE = "meta.embedded.block.avin"

    @JvmField val TEXT = createTextAttributesKey("AVIN_PROSE_TEXT", HighlighterColors.TEXT)
    @JvmField val HEADING = createTextAttributesKey("AVIN_PROSE_HEADING", DefaultLanguageHighlighterColors.KEYWORD)
    @JvmField val EMPHASIS = createTextAttributesKey("AVIN_PROSE_EMPHASIS")
    @JvmField val STRONG = createTextAttributesKey("AVIN_PROSE_STRONG")
    @JvmField val STRIKETHROUGH = createTextAttributesKey("AVIN_PROSE_STRIKETHROUGH")
    @JvmField val CODE = createTextAttributesKey("AVIN_PROSE_CODE", DefaultLanguageHighlighterColors.STRING)
    @JvmField val FENCE = createTextAttributesKey("AVIN_PROSE_FENCE", CODE)
    @JvmField val LINK = createTextAttributesKey("AVIN_PROSE_LINK", CodeInsightColors.HYPERLINK_ATTRIBUTES)
    @JvmField val LIST_MARKER = createTextAttributesKey("AVIN_PROSE_LIST_MARKER", DefaultLanguageHighlighterColors.KEYWORD)
    @JvmField val QUOTE = createTextAttributesKey("AVIN_PROSE_QUOTE", DefaultLanguageHighlighterColors.DOC_COMMENT)
    @JvmField val RULE = createTextAttributesKey("AVIN_PROSE_RULE", DefaultLanguageHighlighterColors.LINE_COMMENT)

    /** Each key with the prefixes of the scopes it colours. */
    private val byScope: List<Pair<String, TextAttributesKey>> = listOf(
        "markup.heading" to HEADING,
        "entity.name.section" to HEADING,
        "punctuation.definition.heading" to HEADING,
        "markup.italic" to EMPHASIS,
        "markup.bold" to STRONG,
        "markup.strikethrough" to STRIKETHROUGH,
        "markup.inline.raw" to CODE,
        "markup.fenced_code" to FENCE,
        "markup.raw.block" to FENCE,
        "meta.link" to LINK,
        "meta.image" to LINK,
        "markup.underline.link" to LINK,
        "punctuation.definition.list" to LIST_MARKER,
        "markup.quote" to QUOTE,
        "meta.separator" to RULE,
    )

    private fun keyOf(scope: String): TextAttributesKey? =
        byScope.firstOrNull { (prefix, _) -> scope == prefix || scope.startsWith("$prefix.") }?.second

    /**
     * The prose keys for a token whose scopes, outermost first, are `scopes`: [TEXT] and then the
     * key of each enclosing construct, so an emphasis inside a quote is both. None when the token
     * is not prose, or is Avin inside the prose (an `avin` fence), which keeps its program colours.
     */
    fun keysFor(scopes: List<String>): List<TextAttributesKey>? {
        if (PROSE_SCOPE !in scopes || PROGRAM_SCOPE in scopes) return null
        return (listOf(TEXT) + scopes.mapNotNull(::keyOf)).distinct()
    }
}

/** The scope names of a TextMate token, outermost first; empty for any other token. */
internal fun scopesOf(type: IElementType): List<String> {
    var scope = (type as? TextMateElementType)?.scope ?: return emptyList()
    val names = ArrayList<String>()
    while (!scope.isEmpty) {
        scope.scopeName?.let { names.add(it.toString()) }
        scope = scope.parent ?: break
    }
    return names.asReversed()
}

/**
 * The TextMate highlighter of a literate file with the prose keys over its prose. A token the
 * prose keys do not name keeps the TextMate highlighter's keys after [LiterateProse.TEXT], and the
 * program is the TextMate highlighter's alone.
 */
class LiterateAvinHighlighter(private val textMate: SyntaxHighlighter) : SyntaxHighlighterBase() {
    override fun getHighlightingLexer() = textMate.highlightingLexer

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> {
        val own = textMate.getTokenHighlights(tokenType)
        val prose = LiterateProse.keysFor(scopesOf(tokenType)) ?: return own
        return if (prose.size == 1) arrayOf(LiterateProse.TEXT, *own) else prose.toTypedArray()
    }
}

/**
 * The editor highlighter of TextMate files: a `.lavin` file gets [LiterateAvinHighlighter], every
 * other TextMate file the TextMate plugin's own highlighter, untouched.
 */
class AvinEditorHighlighterProvider : EditorHighlighterProvider {
    private val textMate = TextMateEditorHighlighterProvider()

    override fun getEditorHighlighter(
        project: Project?,
        fileType: FileType,
        virtualFile: VirtualFile?,
        colors: EditorColorsScheme,
    ): EditorHighlighter {
        val own = if (virtualFile?.extension == "lavin") SyntaxHighlighterFactory.getSyntaxHighlighter(fileType, project, virtualFile) else null
        if (own == null) return textMate.getEditorHighlighter(project, fileType, virtualFile, colors)
        return LiterateEditorHighlighter(LiterateAvinHighlighter(own), colors)
    }
}

/** A lexer highlighter that keeps the TextMate lexer's state the way the TextMate plugin's own does. */
private class LiterateEditorHighlighter(highlighter: SyntaxHighlighter, colors: EditorColorsScheme) :
    LexerEditorHighlighter(highlighter, colors) {
    override fun createStorage(): DataStorage = TextMateLexerDataStorage()
}
