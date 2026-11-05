package dev.avinlang.intellij.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderEx
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import dev.avinlang.intellij.run.MANIFEST
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What one package root asks of the module holding it: the root as a source folder (unless it is a
 * workspace's root, which holds no source of its own), `tests` as a test source folder, and the
 * build directories excluded. Folders are given as URLs, so `tests` and `.avin` may not exist yet.
 */
data class AvinRootLayout(
    val root: VirtualFile,
    val sources: List<String>,
    val tests: List<String>,
    val excluded: List<String>,
)

/**
 * Recognises Avin packages in a project: every directory under a content root holding `avin.hocon`
 * gets its source, test and excluded folders. Folders already marked are left as they are, and
 * nothing is unmarked, so a user's own choices stand.
 */
object AvinProjectModel {
    /** Directories never searched for packages: build output, version control, dependencies. */
    private val SKIPPED = setOf(".git", ".hg", ".svn", ".idea", BUILD_DIR, SYSL_BUILD_DIR, "node_modules")

    /** The layout of every package under [contentRoot]. Needs a read action. */
    fun layoutsUnder(contentRoot: VirtualFile, index: ProjectFileIndex? = null): List<AvinRootLayout> {
        val roots = ArrayList<VirtualFile>()
        val syslRoots = ArrayList<VirtualFile>()
        VfsUtilCore.visitChildrenRecursively(contentRoot, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (!file.isDirectory) return false
                if (file != contentRoot) {
                    if (file.name in SKIPPED) return false
                    if (file.name == VENDOR_DIR && file.parent?.let(::isPackageRoot) == true) return false
                    if (index != null && index.isExcluded(file)) return false
                }
                if (isPackageRoot(file)) roots.add(file)
                if (file.findChild(SYSL_MANIFEST)?.isDirectory == false) syslRoots.add(file)
                return true
            }
        })
        return roots.map { root ->
            val manifest = root.findChild(MANIFEST)
            val text = manifest?.let { VfsUtilCore.loadText(it) }.orEmpty()
            val isPackage = declaresPackage(text)
            val excluded = buildList {
                add(root.url + "/" + BUILD_DIR)
                for (s in syslRoots) {
                    if (VfsUtilCore.isAncestor(root, s, false) && packageRootOf(s) == root) add(s.url + "/" + SYSL_BUILD_DIR)
                }
            }
            AvinRootLayout(
                root = root,
                sources = if (isPackage) listOf(root.url) else emptyList(),
                tests = if (isPackage) listOf(root.url + "/" + TESTS_DIR) else emptyList(),
                excluded = excluded,
            )
        }
    }

    /** The layouts of every package in [project]'s modules, by module. Needs a read action. */
    fun plan(project: Project): Map<Module, List<AvinRootLayout>> {
        val index = ProjectFileIndex.getInstance(project)
        val result = LinkedHashMap<Module, List<AvinRootLayout>>()
        for (module in ModuleManager.getInstance(project).modules) {
            val layouts = ModuleRootManager.getInstance(module).contentRoots.flatMap { layoutsUnder(it, index) }
            val missing = layouts.filter { needsChange(module, it) }
            if (missing.isNotEmpty()) result[module] = missing
        }
        return result
    }

    private fun needsChange(module: Module, layout: AvinRootLayout): Boolean {
        val entry = ModuleRootManager.getInstance(module).contentEntries
            .filter { e -> e.file?.let { VfsUtilCore.isAncestor(it, layout.root, false) } == true }
            .maxByOrNull { it.url.length } ?: return false
        val sourceUrls = entry.sourceFolders.map { it.url }.toSet()
        val excludedUrls = entry.excludeFolderUrls.toSet()
        return layout.sources.any { it !in sourceUrls } || layout.tests.any { it !in sourceUrls } ||
            layout.excluded.any { it !in excludedUrls }
    }

    /** Marks the folders [plan] found missing. Needs a write action. */
    fun apply(plan: Map<Module, List<AvinRootLayout>>) {
        for ((module, layouts) in plan) {
            if (module.isDisposed) continue
            ModuleRootModificationUtil.updateModel(module) { model ->
                for (layout in layouts) {
                    val entry = model.contentEntries
                        .filter { e -> e.file?.let { VfsUtilCore.isAncestor(it, layout.root, false) } == true }
                        .maxByOrNull { it.url.length } ?: continue
                    val sourceUrls = entry.sourceFolders.map { it.url }.toMutableSet()
                    for (url in layout.sources) if (sourceUrls.add(url)) entry.addSourceFolder(url, false)
                    for (url in layout.tests) if (sourceUrls.add(url)) entry.addSourceFolder(url, true)
                    val excludedUrls = entry.excludeFolderUrls.toMutableSet()
                    for (url in layout.excluded) if (excludedUrls.add(url)) entry.addExcludeFolder(url)
                }
            }
        }
    }

    /** Recognises [project]'s packages now. Call on the event dispatch thread. */
    fun configure(project: Project) {
        if (project.isDisposed) return
        val plan = runReadActionBlocking { plan(project) }
        if (plan.isNotEmpty()) WriteAction.run<RuntimeException> { apply(plan) }
    }

    private val PENDING = Key.create<AtomicBoolean>("avin.configure.pending")

    /** Recognises [project]'s packages soon, once however many times it is asked before then. */
    fun scheduleConfigure(project: Project) {
        val flag = (project as UserDataHolderEx).putUserDataIfAbsent(PENDING, AtomicBoolean(false))
        if (!flag.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater({
            flag.set(false)
            configure(project)
        }, ModalityState.nonModal(), project.disposed)
    }
}

/** Recognises a project's packages when it opens. */
class AvinProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        AvinProjectModel.scheduleConfigure(project)
    }
}

/**
 * Recognises packages that appear while a project is open: a manifest created, copied, moved or
 * renamed into place, or a directory arriving that may hold one.
 */
class AvinManifestListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        if (project.isDisposed) return
        if (events.any(::mayAddPackage)) AvinProjectModel.scheduleConfigure(project)
    }

    private fun mayAddPackage(event: VFileEvent): Boolean = when (event) {
        is VFileCreateEvent -> event.isDirectory || isManifestName(event.childName)
        is VFileCopyEvent -> event.file.isDirectory || isManifestName(event.newChildName)
        is VFileMoveEvent -> event.file.isDirectory || isManifestName(event.file.name)
        is VFilePropertyChangeEvent -> event.propertyName == "name" && isManifestName(event.newValue as? String ?: "")
        else -> false
    }

    private fun isManifestName(name: String) = name == MANIFEST || name == SYSL_MANIFEST
}
