package dev.avinlang.intellij.run

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/**
 * Build | Avin, and the same commands in the project view's and the editor's context menus:
 * `avin <kind>` on what is selected ([toolTargets]). Disabled where there is nothing to run it on;
 * in a context menu it is then hidden as well.
 */
abstract class AvinToolAction(private val kind: AvinToolKind) : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /** What the action would run on in the context of [e]. */
    fun targets(e: AnActionEvent): List<File> {
        val project = e.project ?: return emptyList()
        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()
            ?: listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE))
        return toolTargets(kind, selected.mapNotNull(::localFile), project.basePath?.let(::File))
    }

    override fun update(e: AnActionEvent) {
        val enabled = targets(e).isNotEmpty()
        e.presentation.isEnabled = enabled
        e.presentation.isVisible = enabled || !e.isFromContextMenu
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        runTool(project, kind, targets(e))
    }

    private fun localFile(vf: VirtualFile): File? = if (vf.isInLocalFileSystem) File(vf.path) else null
}

/** The Avin submenu, left out of a context menu where none of its commands applies. */
class AvinToolGroup : DefaultActionGroup() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isHideGroupIfEmpty = true
    }
}

class AvinBuildAction : AvinToolAction(AvinToolKind.BUILD)

class AvinCheckAction : AvinToolAction(AvinToolKind.CHECK)

class AvinTestAction : AvinToolAction(AvinToolKind.TEST)

class AvinFormatAction : AvinToolAction(AvinToolKind.FMT)
