package dev.avinlang.intellij

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.plugins.textmate.TextMateFileType
import javax.swing.Icon

/**
 * Settings | Editor | Color Scheme | Avin: the colours of the prose of literate `.lavin` files. The
 * program in a literate file, and every `.avin` file, take the TextMate colours of the scheme.
 */
class AvinColorSettingsPage : ColorSettingsPage {
    override fun getDisplayName() = "Avin"

    override fun getIcon(): Icon = AvinIcons.FILE

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getHighlighter(): SyntaxHighlighter =
        LiterateAvinHighlighter(SyntaxHighlighterFactory.getSyntaxHighlighter(TextMateFileType.INSTANCE, null, LightVirtualFile("demo.lavin")) ?: PlainSyntaxHighlighter())

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAGS

    override fun getDemoText() = DEMO

    companion object {
        private const val GROUP = "Literate Avin prose//"

        private val DESCRIPTORS = arrayOf(
            AttributesDescriptor(GROUP + "Text", LiterateProse.TEXT),
            AttributesDescriptor(GROUP + "Heading", LiterateProse.HEADING),
            AttributesDescriptor(GROUP + "Emphasis", LiterateProse.EMPHASIS),
            AttributesDescriptor(GROUP + "Strong", LiterateProse.STRONG),
            AttributesDescriptor(GROUP + "Strikethrough", LiterateProse.STRIKETHROUGH),
            AttributesDescriptor(GROUP + "Inline code", LiterateProse.CODE),
            AttributesDescriptor(GROUP + "Fenced block", LiterateProse.FENCE),
            AttributesDescriptor(GROUP + "Link", LiterateProse.LINK),
            AttributesDescriptor(GROUP + "List marker", LiterateProse.LIST_MARKER),
            AttributesDescriptor(GROUP + "Block quote", LiterateProse.QUOTE),
            AttributesDescriptor(GROUP + "Horizontal rule", LiterateProse.RULE),
        )

        internal val TAGS = mapOf(
            "text" to LiterateProse.TEXT,
            "heading" to LiterateProse.HEADING,
            "emphasis" to LiterateProse.EMPHASIS,
            "strong" to LiterateProse.STRONG,
            "strike" to LiterateProse.STRIKETHROUGH,
            "code" to LiterateProse.CODE,
            "fence" to LiterateProse.FENCE,
            "link" to LiterateProse.LINK,
            "marker" to LiterateProse.LIST_MARKER,
            "quote" to LiterateProse.QUOTE,
            "rule" to LiterateProse.RULE,
        )

        internal val DEMO = """
            <heading># Counting words</heading>

            <text>A literate file is </text><emphasis>*prose*</emphasis><text> with the </text><strong>**program**</strong><text> indented four columns;</text>
            <text>see </text><link>[the guide](https://avin.dev)</link><text> and </text><code>`avin tangle`</code><text>, not </text><strike>~~the old way~~</strike><text>.</text>

                main() =
                    println("hello")

            <marker>-</marker><text> one item</text>
            <marker>1.</marker><text> a numbered item</text>

            <quote>> A quoted remark.</quote>

            <fence>```text</fence>
            <fence>an illustration, not compiled</fence>
            <fence>```</fence>

            <rule>---</rule>
        """.trimIndent() + "\n"
    }
}
