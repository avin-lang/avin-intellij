package dev.avinlang.intellij.project

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.TreeStructureProvider
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.PsiDirectoryNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager
import dev.avinlang.intellij.AvinIcons

/**
 * Shows a chain of Avin modules on one line. A module directory holding nothing but one other
 * module directory, and that one likewise, is shown as a single node labelled with the dotted path
 * (`dev/avinlang/parsing` as `dev.avinlang.parsing`), opening onto the last directory's contents. A
 * directory holding files, or more than one directory, ends the chain. This is always on, in every
 * project view pane. Inside an Avin package it takes the place of the IDE's own Compact Middle
 * Packages, which joins by other languages' rules (it would show `a.b.x` and `a.b.y` where Avin
 * shows `a.b` holding `x` and `y`): every module directory is shown by this provider's node,
 * whatever that setting is.
 */
class AvinModuleTreeStructureProvider : TreeStructureProvider, DumbAware {
    override fun modify(
        parent: AbstractTreeNode<*>,
        children: Collection<AbstractTreeNode<*>>,
        settings: ViewSettings?,
    ): Collection<AbstractTreeNode<*>> {
        val parentDir = (parent.value as? PsiDirectory)?.virtualFile
        val seen = HashSet<VirtualFile>()
        var changed = false
        val result = ArrayList<AbstractTreeNode<*>>(children.size)
        for (child in children) {
            val start = moduleStart(child, parentDir)
            if (start == null) {
                result.add(child)
                continue
            }
            changed = true
            // Another view may have joined directories already, so one first directory can stand
            // behind several children: it is shown once.
            if (!seen.add(start)) continue
            result.add(moduleNode(child as PsiDirectoryNode, start, settings) ?: child)
        }
        return if (changed) result else children
    }

    /**
     * The module directory [child] begins under [parentDir]: the directory directly under the
     * parent on the way to the one the child shows, which lies deeper when another view (the IDE's
     * own Compact Middle Packages, for one) has joined directories. Null when the child is not a
     * module directory's node.
     */
    private fun moduleStart(child: AbstractTreeNode<*>, parentDir: VirtualFile?): VirtualFile? {
        if (child !is PsiDirectoryNode) return null
        val dir = child.value?.takeIf { it.isValid }?.virtualFile ?: return null
        var start = dir
        if (parentDir != null && VfsUtilCore.isAncestor(parentDir, dir, true)) {
            while (start.parent != null && start.parent != parentDir) start = start.parent
        }
        return start.takeIf(::isModuleDirectory)
    }

    private fun moduleNode(child: PsiDirectoryNode, start: VirtualFile, settings: ViewSettings?): AbstractTreeNode<*>? {
        val project = child.project ?: return null
        val from = start.parent ?: return null
        val end = moduleChainEnd(start)
        val label = VfsUtilCore.getRelativePath(end, from, '.') ?: return null
        if (child is AvinModuleNode && child.value?.virtualFile == end && child.label == label) return child
        val psiEnd = PsiManager.getInstance(project).findDirectory(end) ?: return null
        return AvinModuleNode(project, psiEnd, settings, label, from)
    }
}

/**
 * A chain of module directories shown as one node: [label] is their dotted path under [from], the
 * directory the parent node shows, and the node's value is the chain's last directory.
 */
class AvinModuleNode(
    project: Project,
    directory: PsiDirectory,
    settings: ViewSettings?,
    val label: String,
    private val from: VirtualFile,
) : PsiDirectoryNode(project, directory, settings) {
    override fun updateImpl(data: PresentationData) {
        super.updateImpl(data)
        data.presentableText = label
        if (!isMarkedRoot()) data.setIcon(AvinIcons.MODULE)
    }

    /**
     * Whether a directory of the chain is marked as a source or test folder, which keeps that
     * folder's icon, so `tests` still reads as the test folder.
     */
    private fun isMarkedRoot(): Boolean {
        val project = project ?: return false
        val index = ProjectFileIndex.getInstance(project)
        var dir: VirtualFile? = value?.takeIf { it.isValid }?.virtualFile ?: return false
        while (dir != null && dir != from) {
            if (index.getSourceRootForFile(dir) == dir) return true
            dir = dir.parent
        }
        return false
    }

    override fun getTitle(): String = label

    override fun getSortKey(): Comparable<*> = label

    override fun getQualifiedNameSortKey(): String = label

    /** The intermediate directories are shown by this node too, so selecting one selects it. */
    override fun canRepresent(element: Any?): Boolean {
        if (super.canRepresent(element)) return true
        val file = when (element) {
            is PsiDirectory -> element.virtualFile
            is VirtualFile -> element
            else -> return false
        }
        val end = value?.virtualFile ?: return false
        return file.isDirectory && VfsUtilCore.isAncestor(file, end, true) && VfsUtilCore.isAncestor(from, file, true)
    }

    override fun contains(file: VirtualFile): Boolean {
        if (super.contains(file)) return true
        val end = value?.virtualFile ?: return false
        return file.isDirectory && VfsUtilCore.isAncestor(file, end, true) && VfsUtilCore.isAncestor(from, file, true)
    }
}
