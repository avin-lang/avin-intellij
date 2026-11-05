# Avin for IntelliJ

Support for [Avin](https://github.com/avin-lang/avin) in IntelliJ IDEA and the other JetBrains IDEs.
Avin is a natively compiled, garbage-collected application language with a light,
indentation-based syntax.

- Syntax highlighting for `.avin` files, from the Avin TextMate grammar
- Bracket matching, auto-closing brackets and quotes, and `//` line comments (Code | Comment with
  Line Comment), from the Avin language configuration
- Literate Avin (`.lavin`) files: a Markdown document whose lines indented four columns are Avin
  code. The prose is highlighted as Markdown and the code as Avin, and the language server serves
  them like `.avin` files (language id `avin-literate`). The prose has colours of its own, set in
  Settings | Editor | Color Scheme | Avin (*Literate Avin prose*: text, heading, emphasis, strong,
  strikethrough, inline code, fenced block, link, list marker, block quote, horizontal rule); the
  code keeps exactly the colours of Avin code
- The Avin icon on `.avin` and `.lavin` files
- The Avin language server (`avin lsp --stdio`) through [LSP4IJ](https://github.com/redhat-developer/lsp4ij):
  diagnostics, hover, go to definition and the file structure, and whatever else the server offers
  as it grows

- Running programs and their tests: an **Avin** run configuration, ▶ in the gutter on `main` and on
  each `@test`, results in the test runner, and traps and diagnostics linked to the source

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

## Running and testing

Programs run through `avin run` and their tests through `avin test`, invoked the way the language
server is: the server path, or `sysl run <repository>/compiler -- …` with `AVIN_ROOT` set when a
repository is chosen in the settings.

**Run configurations.** Run | Edit Configurations… | + | **Avin**:

| Field | Meaning |
|---|---|
| Command | Run (`avin run`) or Test (`avin test`) |
| Program | An `.avin` or `.lavin` file, a directory, or a path in a package (a directory holding `avin.hocon`) |
| Optimization | Default (the compiler's, `-O2`), `-O0` or `-O2` |
| Program arguments | Passed to the program after `--` (Run) |
| Test filter | Keeps the tests whose name or file contains it (Test) |
| Working directory | Empty runs in the program's root: its package, its directory, or the directory holding a program of one file |
| Environment variables | Added to your shell's environment, which can be left out |

Run from the context menu of an Avin file, or of a directory holding a package or Avin files, makes
a configuration for the program the file is in: its package, or outside a package the file itself
(a directory of loose files is not assumed to be one program, since a directory of examples holds
many).

**The gutter.** An Avin file has no syntax tree in the IDE, so the run icons are placed from its
text:

- ▶ on a line starting `main(` at column 0 runs the program;
- ▶ on the declaration after a line that is `@test` or `@test("a sentence")` runs that test, with
  its name (the sentence, or else the function's name) as the filter; blank lines, comments and
  other annotations may come between, anything else cancels it;
- ▶ on the first line of a file with tests runs them: in a package or a directory program it offers
  the file's tests or all of the program's.

In a `.lavin` file the same rules apply to the program lines, those indented four columns outside a
fenced block. The filter is `avin test`'s, which keeps every test whose name or file contains it,
so running one test can also run another whose name contains its own.

**Test results.** `avin test`'s report is shown in the test runner: a node per file, each test
passed or failed with its time, and under a failure its reason and what it printed. A failed test
navigates to the trap that stopped it (`… at clamp.avin:13:5`), and a passed one to its declaration.
Diagnostics from a program that does not build are shown in the console as they are.

**The console.** Places in a run's output, a trap's `avin: trap: <reason> at <file>:<line>:<col>`
and a diagnostic's `--> <path>:<line>:<col>`, are links to the source. A trap names its file
without a directory, so the file is looked for under the program's root.

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
- `test` runs the unit tests (how the server's and the run's command lines are built, finding `main`
  and the tests in a file's text, reading `avin test`'s report, places in output) and the platform
  tests (the bundle is found, `.avin` files get the grammar, comments, bracket closing and the icon;
  the gutter's run icons, run configurations, console links, and a test run filling the test tree).
- `verifyPlugin` checks the zip against the recommended IDE releases with the IntelliJ Plugin Verifier.
- `runIde` starts a sandbox IDE with the plugin and LSP4IJ installed.

The first build downloads the IntelliJ Platform, which takes a while.
