package dev.avinlang.intellij.project

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.TreeStructureProvider
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.avinlang.intellij.AvinIcons

/** Chains of module directories are shown on one line with the module icon. */
class AvinProjectViewTest : BasePlatformTestCase() {
    private val provider = AvinModuleTreeStructureProvider()
    private val decorator = AvinModuleNodeDecorator()

    private val manifest = "package {\n  name = \"p\"\n  version = \"0.1.0\"\n}\n"

    private fun file(path: String, text: String = ""): VirtualFile = myFixture.addFileToProject(path, text).virtualFile

    private fun dir(path: String): VirtualFile = myFixture.tempDirFixture.findOrCreateDir(path)

    private fun node(dir: VirtualFile) = PsiDirectoryNode(project, psiManager.findDirectory(dir)!!, ViewSettings.DEFAULT)

    /** The children of [dir]'s node after the provider: each child's label. */
    private fun shown(dir: VirtualFile): List<AbstractTreeNode<*>> {
        val parent = node(dir)
        return provider.modify(parent, parent.children, ViewSettings.DEFAULT).toList()
    }

    private fun labels(dir: VirtualFile): List<String> = shown(dir).map { label(it) }.sorted()

    private fun label(node: AbstractTreeNode<*>): String {
        node.update()
        return node.presentation.presentableText ?: ""
    }

    private fun icon(node: PsiDirectoryNode) = PresentationData().also { decorator.decorate(node, it) }.getIcon(false)

    fun `test a chain of modules is shown as one dotted node`() {
        val root = file("pkg/avin.hocon", manifest).parent
        file("pkg/main.avin")
        file("pkg/dev/avinlang/parsing/parser.avin")
        assertEquals(listOf("avin.hocon", "dev.avinlang.parsing", "main.avin"), labels(root))
        val joined = shown(root).filterIsInstance<AvinModuleNode>().single()
        assertEquals(root.findFileByRelativePath("dev/avinlang/parsing"), joined.value.virtualFile)
        assertEquals(listOf("parser.avin"), joined.children.map { label(it) })
    }

    fun `test a deep chain is joined to its end`() {
        val root = file("pkg/avin.hocon", manifest).parent
        file("pkg/a/b/c/d/e/f/g/x.avin")
        assertTrue(labels(root).contains("a.b.c.d.e.f.g"))
    }

    fun `test a directory holding a file ends the chain`() {
        val root = file("pkg/avin.hocon", manifest).parent
        file("pkg/a/b/one.avin")
        file("pkg/a/b/c/d/two.avin")
        assertTrue(labels(root).contains("a.b"))
        val b = root.findFileByRelativePath("a/b")!!
        assertEquals(listOf("c.d", "one.avin"), labels(b))
    }

    fun `test a directory holding two modules ends the chain`() {
        val root = file("pkg/avin.hocon", manifest).parent
        file("pkg/a/b/x/one.avin")
        file("pkg/a/b/y/two.avin")
        assertTrue(labels(root).contains("a.b"))
        assertEquals(listOf("x", "y"), labels(root.findFileByRelativePath("a/b")!!))
    }

    fun `test a single module is shown as it is, with the module icon`() {
        val root = file("pkg/avin.hocon", manifest).parent
        file("pkg/shapes/area.avin")
        val child = shown(root).filterIsInstance<PsiDirectoryNode>().single()
        assertEquals("shapes", label(child))
        assertEquals(root.findChild("shapes"), child.value.virtualFile)
        assertSame(AvinIcons.MODULE, icon(child))
    }

    fun `test the joined node has the module icon`() {
        val root = file("pkg/avin.hocon", manifest).parent
        file("pkg/a/b/x.avin")
        val joined = shown(root).filterIsInstance<AvinModuleNode>().single()
        assertSame(AvinIcons.MODULE, icon(joined))
    }

    fun `test the package root is not a module and keeps its look`() {
        val root = file("pkg/avin.hocon", manifest).parent
        file("pkg/a/b/x.avin")
        val rootNode = node(root)
        assertNull(icon(rootNode))
        val shownAtTop = provider.modify(node(root.parent), node(root.parent).children, ViewSettings.DEFAULT)
        assertEquals("pkg", label(shownAtTop.filterIsInstance<PsiDirectoryNode>().single { it.value.virtualFile == root }))
    }

    fun `test hidden entries do not end a chain`() {
        val root = file("pkg/avin.hocon", manifest).parent
        myFixture.tempDirFixture.createFile("pkg/a/.keep")
        file("pkg/a/b/x.avin")
        assertTrue(labels(root).contains("a.b"))
    }

    fun `test a nested package ends the chain and is no module`() {
        val root = file("pkg/avin.hocon", manifest).parent
        file("pkg/a/inner/avin.hocon", manifest)
        file("pkg/a/inner/main.avin")
        assertTrue(labels(root).contains("a"))
        val inner = root.findFileByRelativePath("a/inner")!!
        assertNull(icon(node(inner)))
    }

    fun `test a directory that is not a module name is not joined`() {
        val root = file("pkg/avin.hocon", manifest).parent
        file("pkg/a/my-dir/x.avin")
        file("pkg/vendor/dep/d/x.avin")
        assertEquals(listOf("a", "avin.hocon", "vendor"), labels(root))
        assertNull(icon(node(root.findFileByRelativePath("vendor")!!)))
    }

    fun `test unicode directory names are joined`() {
        val root = file("pkg/avin.hocon", manifest).parent
        file("pkg/données/über/名前/x.avin")
        assertTrue(labels(root).contains("données.über.名前"))
    }

    fun `test without avin hocon nothing changes`() {
        val top = file("loose/a/b/c/x.avin").parent.parent.parent.parent
        val parent = node(top)
        val children = parent.children
        assertSame(children, provider.modify(parent, children, ViewSettings.DEFAULT))
        assertNull(icon(node(top.findChild("a")!!)))
    }

    fun `test an empty package shows nothing new`() {
        val root = file("pkg/avin.hocon", manifest).parent
        assertEquals(listOf("avin.hocon"), labels(root))
    }

    fun `test the joined node represents its intermediate directories`() {
        val root = file("pkg/avin.hocon", manifest).parent
        file("pkg/a/b/c/x.avin")
        val joined = shown(root).filterIsInstance<AvinModuleNode>().single()
        assertTrue(joined.canRepresent(root.findFileByRelativePath("a")))
        assertTrue(joined.canRepresent(psiManager.findDirectory(root.findFileByRelativePath("a/b")!!)))
        assertTrue(joined.canRepresent(psiManager.findDirectory(root.findFileByRelativePath("a/b/c")!!)))
        assertFalse(joined.canRepresent(root))
    }

    fun `test the provider is registered`() {
        assertTrue(TreeStructureProvider.EP.getExtensions(project).any { it is AvinModuleTreeStructureProvider })
    }

    fun `test the IDE's compact middle packages setting does not change the result`() {
        val root = file("pkg/avin.hocon", manifest).parent
        file("pkg/a/b/x/one.avin")
        file("pkg/a/b/y/two.avin")
        file("pkg/c/d/z.avin")
        val b = root.findFileByRelativePath("a/b")!!
        for (hide in listOf(true, false)) {
            val settings = object : ViewSettings {
                override fun isHideEmptyMiddlePackages() = hide
                override fun isFlattenPackages() = false
            }
            val top = node(root).let { PsiDirectoryNode(project, it.value, settings) }
            val shownTop = provider.modify(top, top.children, settings).map { label(it) }.sorted()
            assertEquals("hide=$hide", listOf("a.b", "avin.hocon", "c.d"), shownTop)
            val bNode = PsiDirectoryNode(project, psiManager.findDirectory(b)!!, settings)
            assertEquals("hide=$hide", listOf("x", "y"), provider.modify(bNode, bNode.children, settings).map { label(it) }.sorted())
        }
    }

    fun `test an empty middle directory still joins`() {
        val root = file("pkg/avin.hocon", manifest).parent
        dir("pkg/a/b")
        assertTrue(labels(root).contains("a.b"))
    }
}
