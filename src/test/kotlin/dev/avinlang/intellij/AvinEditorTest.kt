package dev.avinlang.intellij

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.actionSystem.IdeActions
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

    fun `test an avin file has the Avin icon`() {
        val file = myFixture.configureByText("main.avin", "").virtualFile
        val icons = FileIconProvider.EP_NAME.extensionList.mapNotNull { it.getIcon(file, 0, project) }
        assertSame(AvinIcons.FILE, icons.first())
    }
}
