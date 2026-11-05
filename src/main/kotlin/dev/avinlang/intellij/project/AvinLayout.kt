package dev.avinlang.intellij.project

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.avinlang.intellij.run.MANIFEST
import java.text.Normalizer

/*
 * How an Avin package lays out its files: the directory holding `avin.hocon` is the package's root,
 * its own files are the root module, and every directory under it is a module named by its path
 * (`shapes/solid` is `shapes.solid`). `tests` holds the package's tests, `.avin` is what building
 * leaves, and a directory holding its own `avin.hocon` is another package.
 */

/** The directory building leaves in a package's root: executables and caches, never source. */
const val BUILD_DIR = ".avin"

/** The directory holding a package's own tests, module `tests`. */
const val TESTS_DIR = "tests"

/** The directory `avin vendor` copies dependencies into, which is not read as a module. */
const val VENDOR_DIR = "vendor"

/** A Sysl package's manifest, and the working directory Sysl leaves beside it. */
const val SYSL_MANIFEST = "sysl.hocon"
const val SYSL_BUILD_DIR = ".sysl"

private val PACKAGE_BLOCK = Regex("""(?m)^\s*"?package"?\s*[:=]?\s*\{""")

/**
 * Whether a manifest's [text] declares a package. A workspace's root manifest may hold only
 * `workspace = [...]`; such a root compiles nothing of its own.
 */
fun declaresPackage(text: CharSequence): Boolean = PACKAGE_BLOCK.containsMatchIn(text)

/**
 * Why [name] cannot name a module, or null when it can. A module is named as any Avin name is: a
 * letter or `_`, then letters, digits of any script, combining marks and `_`, written in
 * Normalization Form C.
 */
fun moduleSegmentError(name: String): String? {
    if (name.isEmpty()) return "A module name cannot be empty"
    val first = name.codePointAt(0)
    if (!(Character.isLetter(first) || first == '_'.code)) {
        return "`$name` is not a module name: a name begins with a letter or `_`"
    }
    var i = 0
    while (i < name.length) {
        val c = name.codePointAt(i)
        val ok = Character.isLetterOrDigit(c) || c == '_'.code ||
            Character.getType(c).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt() }
        if (!ok) return "`$name` is not a module name: `${String(Character.toChars(c))}` cannot be part of a name"
        i += Character.charCount(c)
    }
    if (!Normalizer.isNormalized(name, Normalizer.Form.NFC)) {
        return "`$name` is not in Normalization Form C: write `${Normalizer.normalize(name, Normalizer.Form.NFC)}`"
    }
    return null
}

/** Why the dotted module path [path] (`shapes.solid`) cannot name a module, or null when it can. */
fun modulePathError(path: String): String? {
    if (path.isBlank()) return "A module name cannot be empty"
    for (segment in path.split('.')) moduleSegmentError(segment)?.let { return it }
    return null
}

/**
 * Why [name] cannot name a package, or null when it can: a lowercase ASCII letter, then lowercase
 * letters, digits, `_` and `-`.
 */
fun packageNameError(name: String): String? {
    if (name.isEmpty()) return "A package name cannot be empty"
    if (name[0] !in 'a'..'z' || name.any { it !in 'a'..'z' && it !in '0'..'9' && it != '_' && it != '-' }) {
        return "`$name` is not a package name: a name begins with a lowercase letter, and holds lowercase letters, digits, `_` and `-`"
    }
    return null
}

/** A package name made from a project's name: lowercased, other characters written `-`. */
fun packageNameFor(projectName: String): String {
    val lowered = projectName.trim().lowercase().map { c ->
        if (c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-') c else '-'
    }.joinToString("").trim('-')
    val name = if (lowered.isNotEmpty() && lowered[0] in 'a'..'z') lowered else "app" + (if (lowered.isEmpty()) "" else "-$lowered")
    return name.replace(Regex("-+"), "-")
}

/** The module a package named [packageName] calls its main module: the name with `-` written `_`. */
fun mainModuleFor(packageName: String): String = packageName.replace('-', '_')

/** Whether [dir] holds a manifest, which makes it a package's root. */
fun isPackageRoot(dir: VirtualFile): Boolean = dir.isDirectory && dir.findChild(MANIFEST)?.isDirectory == false

/** The package [file] is in: the nearest directory at or above it holding `avin.hocon`. */
fun packageRootOf(file: VirtualFile): VirtualFile? {
    var dir: VirtualFile? = if (file.isDirectory) file else file.parent
    while (dir != null) {
        if (isPackageRoot(dir)) return dir
        dir = dir.parent
    }
    return null
}

/**
 * The module the files of [dir] belong to, as their `module` header names it: its path under its
 * package's root written with dots (`shapes/solid` is `shapes.solid`). Empty for the root itself,
 * whose files have no header, and null outside a package.
 */
fun modulePathOf(dir: VirtualFile): String? {
    val root = packageRootOf(dir) ?: return null
    if (root == dir) return ""
    return VfsUtilCore.getRelativePath(dir, root, '.')
}

/** The `module` line a new file in [dir] begins with, or empty for the root module and outside a package. */
fun moduleHeaderFor(dir: VirtualFile): String =
    modulePathOf(dir)?.takeIf { it.isNotEmpty() }?.let { "module $it" } ?: ""

/** Whether an entry of a directory takes no part in its layout: a hidden file or directory. */
private fun isHidden(file: VirtualFile): Boolean = file.name.startsWith(".")

/**
 * Whether [dir] is a module of the package it is in: a directory under the package's root (not the
 * root, not another package's root) whose path is made of module names, outside `vendor`.
 */
fun isModuleDirectory(dir: VirtualFile): Boolean {
    if (!dir.isDirectory) return false
    val root = packageRootOf(dir) ?: return false
    if (root == dir) return false
    val path = VfsUtilCore.getRelativePath(dir, root, '/') ?: return false
    val segments = path.split('/')
    if (segments.first() == VENDOR_DIR) return false
    return segments.all { moduleSegmentError(it) == null }
}

/**
 * The last directory of the chain of modules starting at [dir]: while a directory holds no files
 * and exactly one directory, and that directory is a module of the same package, the chain goes on
 * into it. Hidden entries (`.DS_Store`, `.git`) are not counted. [dir] itself when it holds files,
 * several directories, or none.
 */
fun moduleChainEnd(dir: VirtualFile): VirtualFile {
    var current = dir
    while (true) {
        val entries = current.children.filterNot(::isHidden)
        if (entries.size != 1) return current
        val only = entries[0]
        if (!only.isDirectory || isPackageRoot(only) || moduleSegmentError(only.name) != null) return current
        current = only
    }
}
