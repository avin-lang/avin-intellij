package dev.avinlang.intellij

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

/** Gives `.avin` and literate `.lavin` files the Avin icon in the project view, editor tabs and search results. */
class AvinFileIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        if (file.extension == "avin" || file.extension == "lavin") AvinIcons.FILE else null
}

object AvinIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/icons/avin.svg", AvinIcons::class.java)
}
