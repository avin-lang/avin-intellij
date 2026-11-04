package dev.avinlang.intellij.run

import dev.avinlang.intellij.ServerCommand
import dev.avinlang.intellij.avinCommand
import java.io.File

/** A package's manifest; the directory holding it is the package. */
const val MANIFEST = "avin.hocon"

/** Whether [name] is an Avin source file: `.avin`, or `.lavin` for a literate one. */
fun isAvinSource(name: String): Boolean = name.endsWith(".avin") || name.endsWith(".lavin")

/** Whether [name] is a literate source file, whose program is the lines indented four columns. */
fun isLiterate(name: String): Boolean = name.endsWith(".lavin")

/** The package [start] is in: the nearest directory at or above it holding `avin.hocon`. */
fun packageRoot(start: File): File? {
    var dir: File? = start.absoluteFile.let { if (it.isDirectory) it else it.parentFile }
    while (dir != null) {
        if (File(dir, MANIFEST).isFile) return dir
        dir = dir.parentFile
    }
    return null
}

/**
 * The program [file] belongs to, as the run and test commands are given it: the package it is in,
 * or, outside a package, the file itself (a directory names itself). A directory of loose files is
 * not assumed to be one program, since a directory of examples holds many.
 */
fun programFor(file: File): File = packageRoot(file) ?: file.absoluteFile

/**
 * The directory the test report names files from, and the default working directory: the package
 * the program is in, the program when it is a directory, or the directory holding a program of one
 * file.
 */
fun programRoot(program: File): File {
    val absolute = program.absoluteFile
    return packageRoot(absolute) ?: if (absolute.isDirectory) absolute else (absolute.parentFile ?: absolute)
}

/**
 * The file the test report heads [file]'s tests with, which is also a filter that keeps only that
 * file's tests: its path under [root] when the program is a directory, otherwise its name.
 */
fun shownFile(file: File, root: File): String {
    val f = file.absoluteFile.path
    val r = root.absoluteFile.path
    return if (root.isDirectory && f.startsWith(r + File.separator)) f.substring(r.length + 1) else file.name
}

/** What to do with a program: run it, or run its tests. */
enum class AvinCommandKind(val word: String) { RUN("run"), TEST("test") }

/** One invocation of `avin run` or `avin test`, as a run configuration describes it. */
data class AvinRequest(
    val kind: AvinCommandKind,
    /** The program: a source file, a directory, or a path in a package; absolute. */
    val program: String,
    /** `-O0`, `-O2`, or empty for the compiler's default. */
    val optimization: String = "",
    /** The arguments given to the program (run only). */
    val programArguments: List<String> = emptyList(),
    /** Keeps the tests whose name or file contains it (test only); empty runs every test. */
    val testFilter: String = "",
    /** The working directory; empty uses the program's root directory. */
    val workingDirectory: String = "",
)

/** The arguments after `avin`: `run <program> [-O] [-- args…]` or `test <program> [filter] [-O]`. */
fun avinArguments(request: AvinRequest): List<String> {
    val optimization = request.optimization.trim().takeIf { it == "-O0" || it == "-O2" }
    return when (request.kind) {
        AvinCommandKind.RUN -> buildList {
            add("run")
            add(request.program)
            optimization?.let(::add)
            if (request.programArguments.isNotEmpty()) {
                add("--")
                addAll(request.programArguments)
            }
        }
        AvinCommandKind.TEST -> buildList {
            add("test")
            add(request.program)
            request.testFilter.takeIf { it.isNotEmpty() }?.let(::add)
            optimization?.let(::add)
        }
    }
}

/**
 * The process that carries out [request], invoked as the language server is ([avinCommand]): the
 * installed `avin` at [serverPath], or the compiler of the checkout at [repository] through
 * `sysl run`. [env] is the environment it runs in; the working directory is the request's, or the
 * program's root directory.
 */
fun avinProcess(serverPath: String?, repository: String?, env: Map<String, String>, request: AvinRequest): ServerCommand {
    val cwd = request.workingDirectory.trim().ifEmpty { programRoot(File(request.program)).path }
    return avinCommand(serverPath, repository, env, avinArguments(request), cwd)
}
