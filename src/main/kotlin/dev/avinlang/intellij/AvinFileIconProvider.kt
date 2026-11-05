package dev.avinlang.intellij

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/**
 * Gives `.avin` and literate `.lavin` files the Avin icon, and `.sysl` files the Sysl icon, in the
 * project view, editor tabs and search results.
 */
class AvinFileIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        when (file.extension) {
            "avin", "lavin" -> AvinIcons.FILE
            "sysl" -> AvinIcons.SYSL
            else -> null
        }
}

object AvinIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/icons/avin.svg", AvinIcons::class.java)

    @JvmField
    val SYSL: Icon = IconLoader.getIcon("/icons/sysl.svg", AvinIcons::class.java)

    /** An Avin module: a directory of an Avin package, shown in the project view. */
    @JvmField
    val MODULE: Icon = IconLoader.getIcon("/icons/avinModule.svg", AvinIcons::class.java)
}
