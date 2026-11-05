package dev.avinlang.intellij

import com.intellij.lang.LanguageExtension
import com.intellij.lang.folding.LanguageFolding
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import com.redhat.devtools.lsp4ij.client.features.LSPBreadcrumbsFeature
import com.redhat.devtools.lsp4ij.client.features.LSPDocumentSymbolFeature
import com.redhat.devtools.lsp4ij.client.features.LSPFoldingRangeFeature
import com.redhat.devtools.lsp4ij.features.documentSymbol.LSPBreadcrumbsProvider
import com.redhat.devtools.lsp4ij.features.foldingRange.LSPFoldingRangeBuilder
import com.redhat.devtools.lsp4ij.features.workspaceSymbol.LSPWorkspaceGotoClassContributor
import com.redhat.devtools.lsp4ij.features.workspaceSymbol.LSPWorkspaceGotoSymbolContributor
import org.jetbrains.plugins.textmate.TextMateService

/**
 * The outline, breadcrumbs, folding and Go to Symbol/Class come from the language server through
 * LSP4IJ, which serves them for the language Avin files have: this pins that wiring.
 */
class AvinStructureTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        TextMateService.getInstance().ensureInitialized()
    }

    private fun avinLanguage() = myFixture.configureByText("shapes.avin", "data Shape\n    Circle(r: real)\n").language

    fun `test an Avin file's structure view is the server's outline`() {
        // The structure view is a module of its own, so its extension point is reached by name.
        val factories = LanguageExtension<Any>("com.intellij.lang.psiStructureViewFactory").allForLanguage(avinLanguage())
        assertTrue(factories.toString(), factories.any { it.javaClass.name == "com.redhat.devtools.lsp4ij.features.documentSymbol.LSPDocumentSymbolStructureViewFactory" })
    }

    fun `test an Avin file's folding is the server's`() {
        val builders = LanguageFolding.INSTANCE.allForLanguage(avinLanguage())
        assertTrue(builders.toString(), builders.any { it is LSPFoldingRangeBuilder })
    }

    fun `test breadcrumbs come from the server's outline`() {
        assertTrue(BreadcrumbsProvider.EP_NAME.extensionList.any { it is LSPBreadcrumbsProvider })
    }

    fun `test Go to Symbol and Go to Class ask the server`() {
        assertTrue(ChooseByNameContributor.SYMBOL_EP_NAME.extensionList.any { it is LSPWorkspaceGotoSymbolContributor })
        assertTrue(ChooseByNameContributor.CLASS_EP_NAME.extensionList.any { it is LSPWorkspaceGotoClassContributor })
    }

    fun `test the client turns Go to Class on and leaves the outline and folding to LSP4IJ`() {
        val features = AvinClientFeatures()
        assertTrue(features.workspaceSymbolFeature.supportsGotoClass())
        assertTrue(features.workspaceSymbolFeature is AvinWorkspaceSymbolFeature)
        assertEquals(LSPDocumentSymbolFeature::class.java, features.documentSymbolFeature.javaClass)
        assertEquals(LSPFoldingRangeFeature::class.java, features.foldingRangeFeature.javaClass)
        assertEquals(LSPBreadcrumbsFeature::class.java, features.breadcrumbsFeature.javaClass)
    }
}
