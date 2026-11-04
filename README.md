# Avin for IntelliJ

Support for [Avin](https://github.com/avin-lang/avin) in IntelliJ IDEA and the other JetBrains IDEs.
Avin is a natively compiled, garbage-collected application language with a light,
indentation-based syntax.

- Syntax highlighting for `.avin` files, from the Avin TextMate grammar
- Bracket matching, auto-closing brackets and quotes, and `//` line comments (Code | Comment with
  Line Comment), from the Avin language configuration
- Literate Avin (`.lavin`) files: a Markdown document whose lines indented four columns are Avin
  code. The prose is highlighted as Markdown and the code as Avin, and the language server serves
  them like `.avin` files (language id `avin-literate`)
- The Avin icon on `.avin` and `.lavin` files
- The Avin language server (`avin lsp --stdio`) through [LSP4IJ](https://github.com/redhat-developer/lsp4ij):
  diagnostics, hover, go to definition and the file structure, and whatever else the server offers
  as it grows

The plugin targets IntelliJ Platform 2026.2 (build 262) and later, and uses LSP4IJ 0.21.0 or later.

## Changes

- 0.1.1: recognizes literate Avin `.lavin` files (grammar, icon, language server).
- 0.1.0: first release (`.avin` highlighting, editor behaviour, language server).

## Installing from disk

1. Build the plugin (below), or take a released `avin-intellij-<version>.zip`.
2. Settings | Plugins | ⚙ | Install Plugin from Disk…, and choose the zip.
3. Restart the IDE if it asks.

**LSP4IJ** is a required dependency. The plugin declares it, so the IDE knows it is needed: when it
is missing, the IDE normally offers to download it from the JetBrains Marketplace as part of the
install. If your IDE does not offer that (an offline IDE, or a custom plugin repository), install
**LSP4IJ** from Settings | Plugins | Marketplace first, then install this zip. The bundled
**TextMate Bundles** plugin must also be enabled, which it is by default.

## Settings

Settings | Languages & Frameworks | Avin:

| Setting | Default | Meaning |
|---|---|---|
| Server path | `avin` | The avin executable, run as `<path> lsp --stdio`. A bare name is looked up on `PATH` (the IDE uses your login shell's `PATH`). |
| Repository | empty | A checkout of the Avin repository to run the server from instead: `sysl run <repository>/compiler -- lsp --stdio`, with `AVIN_ROOT` set to the checkout and the checkout as the working directory. Empty uses the server path. |

Changing either restarts the server. If the server cannot be found or started, the plugin says so
once in a notification and leaves the server off; highlighting, brackets and comments keep working.
After fixing the setting, the server starts again for open Avin files. LSP4IJ's own **Language
Servers** tool window shows the server's state and its log, and can restart it.

## How the grammar is sourced

The grammar and the editor behaviour have one source: the Avin repository's
`editors/vscode/` directory. The build copies three files from it into the plugin, as a TextMate
bundle under `textmate/avin/` beside the plugin's jars (five files since 0.1.1):

- `package.json`: the bundle manifest that names the languages, the `.avin` and `.lavin` extensions and the files below
- `syntaxes/avin.tmLanguage.json`: the grammar
- `language-configuration.json`: comments, brackets, auto-closing pairs and indentation
- `syntaxes/lavin.tmLanguage.json` and `language-configuration-literate.json`: the same for `.lavin` files

The directory is the Gradle property `avinEditorDir`, `../avin/editors/vscode` by default (the
Avin repository checked out beside this one). Point it elsewhere with
`./gradlew buildPlugin -PavinEditorDir=/path/to/avin/editors/vscode`, or in `gradle.properties`.
The build stops with a message naming the missing file if the directory does not hold them. Rebuild
the plugin to pick up a changed grammar.

## Building

Requires a JDK 17 or later to run Gradle; the build compiles with JDK 21, which Gradle downloads
into `~/.gradle` if no JDK 21 is installed.

```
./gradlew buildPlugin
./gradlew test
./gradlew verifyPlugin
./gradlew runIde
```

- `buildPlugin` writes `build/distributions/avin-intellij-<version>.zip`.
- `test` runs the unit tests (how the server's command line is built from the settings) and the
  platform tests (the bundle is found, `.avin` files get the grammar, comments, bracket closing and
  the icon).
- `verifyPlugin` checks the zip against the recommended IDE releases with the IntelliJ Plugin Verifier.
- `runIde` starts a sandbox IDE with the plugin and LSP4IJ installed.

The first build downloads the IntelliJ Platform, which takes a while.
