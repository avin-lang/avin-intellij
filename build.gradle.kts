import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("org.jetbrains.plugins.textmate")
        bundledModule("intellij.platform.smRunner")
        bundledModule("intellij.platform.testRunner")
        plugin("com.redhat.devtools.lsp4ij", providers.gradleProperty("lsp4ijVersion").get())
        testFramework(TestFrameworkType.Platform)
        testBundledPlugins("com.intellij.modules.ultimate")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}

// The TextMate bundle comes from the Avin repository's editor directory, so the grammar and the
// language configuration have one source. It is assembled under build/ and placed beside the
// plugin's jars, where AvinBundleProvider finds it.
val avinEditorDir: Directory = layout.projectDirectory.dir(providers.gradleProperty("avinEditorDir").get())

val avinBundle = tasks.register<Sync>("avinBundle") {
    description = "Copies the Avin TextMate grammar, language configuration and manifest into the plugin."
    val source = avinEditorDir
    val bundleFiles = listOf(
        "package.json",
        "language-configuration.json",
        "language-configuration-literate.json",
        "syntaxes/avin.tmLanguage.json",
        "syntaxes/lavin.tmLanguage.json",
    )
    doFirst {
        for (name in bundleFiles) {
            check(source.file(name).asFile.isFile) {
                "No $name under ${source.asFile}: set -PavinEditorDir to the Avin editor directory (editors/vscode in the Avin repository)."
            }
        }
    }
    from(source) {
        include(bundleFiles)
    }
    into(layout.buildDirectory.dir("textmate/avin"))
}

tasks.prepareSandbox {
    from(avinBundle) {
        into(intellijPlatform.projectName.map { "$it/textmate/avin" })
    }
}

tasks.prepareTestSandbox {
    from(avinBundle) {
        into(intellijPlatform.projectName.map { "$it/textmate/avin" })
    }
}

// The Sysl bundle: the grammar is the Sysl repository's own file, copied here at build time; the
// manifest and language configuration are this plugin's (src/main/textmate/sysl).
val syslGrammarDir: Directory = layout.projectDirectory.dir(providers.gradleProperty("syslGrammarDir").get())

val syslBundle = tasks.register<Sync>("syslBundle") {
    description = "Copies the Sysl TextMate grammar, with this plugin's manifest and language configuration, into the plugin."
    val grammarDir = syslGrammarDir
    val grammar = grammarDir.file("sysl.tmLanguage.json")
    doFirst {
        check(grammar.asFile.isFile) {
            "No sysl.tmLanguage.json under ${grammarDir.asFile}:set -PsyslGrammarDir to the Sysl repository's docs/grammars directory."
        }
    }
    from(layout.projectDirectory.dir("src/main/textmate/sysl"))
    from(grammar) {
        into("syntaxes")
    }
    into(layout.buildDirectory.dir("textmate/sysl"))
}

tasks.prepareSandbox {
    from(syslBundle) {
        into(intellijPlatform.projectName.map { "$it/textmate/sysl" })
    }
}

tasks.prepareTestSandbox {
    from(syslBundle) {
        into(intellijPlatform.projectName.map { "$it/textmate/sysl" })
    }
}
