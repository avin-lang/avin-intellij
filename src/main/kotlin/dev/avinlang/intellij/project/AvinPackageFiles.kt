package dev.avinlang.intellij.project

import dev.avinlang.intellij.run.MANIFEST
import java.io.File

/** What a new package is: a program, which has `main`, or a library, which has none. */
enum class AvinPackageKind(val label: String) {
    PROGRAM("Program"),
    LIBRARY("Library");

    override fun toString(): String = label
}

/**
 * The files of a new package named [name], by their paths under its root, as `avin new` writes them:
 * the manifest (with [description] when one is given), a module named after the package holding one
 * function, `main.avin` using it (a program only), a test of it in `tests/`, and a `.gitignore`
 * naming `.avin/`.
 */
fun avinPackageFiles(name: String, kind: AvinPackageKind, description: String = ""): Map<String, String> {
    require(packageNameError(name) == null) { packageNameError(name)!! }
    val module = mainModuleFor(name)
    val describe = description.trim().takeIf { it.isNotEmpty() }?.let { "  description = ${hoconString(it)}\n" }.orEmpty()
    return buildMap {
        put(MANIFEST, "package {\n  name = \"$name\"\n  version = \"0.1.0\"\n$describe}\n\ndependencies {\n}\n")
        put(".gitignore", "$BUILD_DIR/\n")
        put(
            "$module/$module.avin",
            "module $module\n\n// The greeting for `name`.\ngreeting(name: string) -> string = \"Hello, \" + name + \"!\"\n",
        )
        if (kind == AvinPackageKind.PROGRAM) {
            put("main.avin", "import $module.greeting\n\nmain()\n    println(greeting(\"world\"))\n")
        }
        put(
            "$TESTS_DIR/${module}_tests.avin",
            "module tests\n\nimport $module.greeting\n\n@test(\"the greeting names whom it greets\")\n" +
                "greetsByName()\n    assertEq(greeting(\"Avin\"), \"Hello, Avin!\")\n",
        )
    }
}

/**
 * Writes a new package's files into [root]. A file already there is left as it is, except that a
 * `.gitignore` is given the `.avin/` line it lacks, so a repository the wizard has just made keeps
 * its own lines.
 */
fun writeAvinPackage(root: File, name: String, kind: AvinPackageKind, description: String = "") {
    for ((path, text) in avinPackageFiles(name, kind, description)) {
        val file = File(root, path)
        file.parentFile.mkdirs()
        when {
            !file.exists() -> file.writeText(text)
            path == ".gitignore" -> {
                val existing = file.readText()
                if (existing.lines().none { it.trim() == "$BUILD_DIR/" || it.trim() == BUILD_DIR }) {
                    file.writeText(existing + (if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n") + text)
                }
            }
        }
    }
}

/** [text] as a quoted HOCON string, its quotes, backslashes and control characters escaped. */
fun hoconString(text: String): String = buildString {
    append('"')
    for (c in text) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\t' -> append("\\t")
            '\r' -> append("\\r")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}
