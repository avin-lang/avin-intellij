package dev.avinlang.intellij

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/** The file patterns the plugin hands to the language server, and the language id each carries. */
class ServerMappingTest {
    private fun mappings(): Map<String, String> {
        val stream = javaClass.getResourceAsStream("/META-INF/plugin.xml")
        assertNotNull("plugin.xml is not on the classpath", stream)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
        val nodes = doc.getElementsByTagName("fileNamePatternMapping")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associate {
            assertEquals("avinLanguageServer", it.getAttribute("serverId"))
            it.getAttribute("patterns") to it.getAttribute("languageId")
        }
    }

    @Test
    fun `avin files use the avin language id`() {
        assertEquals("avin", mappings()["*.avin"])
    }

    @Test
    fun `lavin files use the avin-literate language id`() {
        assertEquals("avin-literate", mappings()["*.lavin"])
    }

    @Test
    fun `only avin and lavin files are mapped`() {
        assertEquals(setOf("*.avin", "*.lavin"), mappings().keys)
    }
}
