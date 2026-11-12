# Cucumber JetBrains Plugin

A standalone JetBrains IDE plugin named **Cucumber**: its own `.feature` file type with highlighting,
step resolution and validation (undefined / ambiguous), navigation, completion, formatting, a
*Create step definition* quick fix, a **Cucumber** tool window and a Cucumber run configuration with
a test tree. It uses only IntelliJ Platform APIs; Gherkin and language plugins are not required.
It is written in Kotlin and built with Gradle and the IntelliJ Platform Gradle Plugin; the Kotlin
standard library is provided by the IDE at run time.

## Feature files

- **Languages**: feature files may use any of the ~80 Gherkin languages: put `# language: zh-CN`
  (or `de`, `fr`, `ja`, …) before the first line; English is the default. Localized keywords
  (e.g. `功能:` / `功能：`, `假如`, `当`, `那么`, with or without a space before the step text) are
  used everywhere: highlighting, step resolution, completion (also of the `# language:` code),
  formatting, structure view, folding, Scenario Outlines, run markers and the tool window.
- **Navigation**: Ctrl+click or Ctrl+B anywhere on a step line (keyword included) opens the step
  definition; a gutter icon on each defined step jumps there too. For ambiguous steps a chooser
  lists every matching definition.
- **Reformat Code** (Ctrl+Alt+L) normalizes indentation (2 spaces per level; tags and comments
  follow the next element), trims trailing whitespace and aligns data table columns. Doc string
  content keeps its relative indentation.
- **Structure** (Ctrl+F12 / Structure tool window): Feature, Background, Rule, Scenario (Outline),
  Examples and steps. **Folding** of section bodies, doc strings and data tables; **Ctrl+/**
  toggles `# ` comments.
- **Scenario Outlines**: steps resolve with their `<param>`s replaced by the first Examples row.
  Ctrl+click on a `<param>` opens its Examples column (elsewhere on the line: the step definition;
  on a column header: the `<param>` usages); caret highlighting marks all occurrences. The
  *Cucumber Scenario Outline parameters* inspection reports `<param>`s without an Examples column,
  unused columns, outlines without Examples and `<param>` in plain scenarios.
- **Tags**: typing `@` completes tags used in the project; Ctrl+click on a tag lists its other
  occurrences.
- **Step completion** (Ctrl+Space after a step keyword, any dialect): the project's step
  definitions as readable text (`the user "<string>" sends <int> request(s)`; regexes with anchors
  removed, groups as `<param>`s, escapes resolved, `a/b` / `(?:a|b)` as choices; patterns that
  cannot be shown as text are skipped) plus the step texts already used in feature files. Words
  typed are matched in order; definitions for the step's keyword (`And`/`But` resolved) come first.
  Choosing a definition starts a live template: the caret goes to the first parameter (inside the
  quotes of a `{string}`, `0` selected for an `{int}`) and Tab moves to the next.
- **Quick documentation** (Ctrl+Q / hover) on a step shows the matching definition(s), file:line,
  language and argument values (e.g. `{int} = 42`); an undefined step that only fails because of an
  argument type says so.
- Colors: *Settings | Editor | Color Scheme | Cucumber*.

## Step definitions in code

Works in any language, even in files the IDE only knows as plain text:

- A Cucumber gutter icon marks each step definition used by feature steps (Scenario Outline steps
  included; the tooltip gives the count); click it to open the step (or choose among several).
  Unused definitions get a grey icon. The step index is built in the background once the project is
  indexed and then updated incrementally. Build output (`target/`, `build/`, ...) never
  triggers a rescan, so the icons appear together with the editor's other markers.
- Large repositories: once the IDE has indexed the project, only files whose words (from the
  IDE's word index) include a step keyword outside comments (`Given`, `When`, `Then`, `And`,
  `But`, `Step`, `defineStep`, `given`, `GIVEN`, `StepDefinition`, ...) are read; the rest are
  skipped unread. Files the word index does not cover (outside project content, unknown file
  types, unsaved edits) are always read, and during indexing every file is read. On a 2,129-file
  Maven project this cut a cold step index build from ~2 s to ~0.5 s with identical results. Run
  the IDE with `-Dcucumber.stepIndex.fullScan=true` to read every file.
- Ctrl+click / Ctrl+B on a step definition's pattern string (e.g. `@Given("...")`) opens the
  feature steps using it.
- Ctrl+hover underlines just that pattern string, also in files the IDE only knows as plain text or
  TextMate (e.g. Go or Rust without their plugins), where an implicit reference covers the literal.
- *Find Step Usages* (editor context menu) lists the feature steps using the definition at the caret
  (in a `.feature` file: the usages of the definitions of the step at the caret).
- *Rename Step...* (editor context menu, or Alt+Enter *Rename step everywhere...* on a step) renames
  the step in every `.feature` file and, when only the literal text of a simple pattern changes (no
  regex classes/quantifiers, optional text or alternatives), the definition pattern too; other steps
  using that definition keep their parameter values. One undoable command; otherwise only feature
  steps are renamed and a notification says so.
- Alt+Enter on an undefined step: *Create step definition*, or *Create all undefined step
  definitions* of the file (one per distinct text) in a chosen step file of the detected language or
  a new step file next to an existing one (Java, Kotlin, JavaScript/TypeScript, Python, Ruby).
- *Find Unused Step Definitions* (Tools menu, the Cucumber tool window's *More* menu, Search
  Everywhere) lists the definitions no feature step uses (Scenario Outline steps count with their
  examples substituted) in the Find tool window, grouped by file; double-click navigates.
  *Delete All Not Excluded...* / *Delete Selected...* there, or *Delete Unused Step Definition* in the
  editor context menu (caret on the definition), delete the whole definition: annotations + method
  (Java, Kotlin, Groovy, Scala, C#), attributes + `fn` (Rust), decorators + `def` (Python), or the
  registration call with its block (JS/TS, Ruby, Go, C++ macros, java8/Kotlin/Groovy/Scala lambdas).
  One undoable command after a confirmation listing them; definitions sharing their method with
  another step annotation lose only their annotation, and ones whose extent is unclear (e.g. Kotlin
  expression bodies, `[Given("a"), When("b")]`) are left alone and reported.

## Cucumber tool window

The tool window on the right lists feature files with their scenarios and steps; double-click an
item to open it. The step-definition language is detected automatically: in mixed projects each
feature shows its language as a tinted pill, otherwise it appears once in the summary line. Tags
are tinted pills, steps get Given/When/Then letter tiles, undefined steps a red `?` tile and an
"undefined" pill. The filter field accepts text or tag expressions (`@smoke and not @wip`, `or`,
parentheses), evaluated on effective tags (feature, rule, scenario and examples tags).
**Run Selected** / **Run All** start the run configuration below; right-click an item to open it,
go to its step definition, run it, copy its name, path or run command, reveal it in the Project
view, or expand / collapse the tree.

## Running, debugging and test results

- **Run configuration "Cucumber"**: feature file or directory, optional scenario line, tag expression,
  runner (auto-detected), working directory (auto), runner arguments and environment variables.
  Right-click a `.feature` file, a folder or a scenario/example row, or use the gutter icons on
  `Feature:` / `Scenario:` lines (any language), for *Run*, *Debug* and *Modify Run Configuration*;
  the gutter icon shows the last result. Only the selected feature, scenario or example row runs;
  other unit tests are not run.
- **Maven**: `mvn test -Dtest=<Cucumber runner> -Dcucumber.features=<file>:<line>` (JUnit Platform
  runners also get `-Dsurefire.includeJUnit5Engines=cucumber`, so each scenario runs once; with
  several runner classes only the one closest to the feature runs it); multi-module builds use
  `-pl <module> -am`. Running a module or project folder runs all of its features.
- **Gradle**: `-D` does not reach the test JVM, so runs add `--init-script` with a generated script that
  copies `CUCUMBER_FEATURES`, `CUCUMBER_FILTER_TAGS` and `CUCUMBER_PLUGIN` (or, when unset, the
  same `-Dcucumber.*` options of the Gradle command line, so a copied run command needs no
  environment) to the `cucumber.*` system properties of every `Test` task (and, with JUnit Platform
  runners, limits the run to the Cucumber engine).
- **Tags**: while a tag filter is active in the tool window, runs pass it on
  (`-Dcucumber.filter.tags`, `CUCUMBER_FILTER_TAGS`, `--tags`).
- **Test tree**: Cucumber-JVM, cucumber-js and cucumber-ruby write Cucumber Messages (`message`
  formatter, ndjson) to a temporary file that is read while the run progresses; the Run window shows
  Feature → Scenario → Step with pass/fail/skip, durations, failure messages and stack traces,
  navigation to the `.feature` line, and **Rerun Failed Tests** (by `file:line`). behave and the
  Go / Rust / C# runners show a plain console.
- **Last results in the editor**: after a run (with the test tree), the run icons of `Feature:`,
  `Scenario:` / `Scenario Outline:` lines and example rows turn green (passed), red (failed or
  ambiguous) or yellow (skipped, pending, undefined); hovering shows the result with the failing step
  and the first line of its error (features and outlines also count their scenarios), and the
  context menu keeps Run / Debug. Failed, undefined and pending steps get their own gutter icon with
  the message. Results are kept per line across IDE restarts (IDE cache) and dropped once that line
  is edited; a newer run replaces the results of the lines it ran. Files without such results fall
  back to the platform's stored test states (`cucumber://<path>:<line>`).
- **Debug** (IntelliJ IDEA, i.e. when the Java plugin is present): the IDE debugger listens on a free port
  and the forked test JVM connects to it (Maven `-Dmaven.surefire.debug=-agentlib:jdwp=...server=n...`,
  Gradle via the init script).

## Supported step-definition frameworks

| Language | Framework | Step definitions recognised | Pattern dialect |
|---|---|---|---|
| C++ | Cucumber-CPP (Cucumber project; wire protocol) | `GIVEN("…") { }`, `WHEN`, `THEN`, `AND`, `BUT`, `STEP` | regex |
| Go | godog | `ctx.Step(`^…$`, fn)`, `.Given/.When/.Then` | regex (`(?P<name>…)` supported) |
| Java | Cucumber-JVM | `@Given("…")` (qualified / `value =`), Java 8 `Given("…", …)`, text blocks | `^…`/`…$`/`/…/` = regex, else Cucumber Expression |
| JavaScript / TypeScript | Cucumber.js | `Given('…', fn)`, `When(/…/, fn)`, `defineStep`, `this.Given`, `Given<World>(…)` | Cucumber Expression (strings, templates), regex (literals) |
| Kotlin | Cucumber-JVM | annotations and `Given("…") { }`, raw `"""…"""` | as Java |
| Scala | Cucumber Scala | `Given("""…""") { (arg: Type) => … }` | regex-looking patterns or Cucumber Expression |
| Groovy | Cucumber-JVM | `Given(~/…/) { }`, `Given("…") { }` | `~` / slashy = regex, else as Java |
| Ruby | Cucumber-Ruby | `Given('…') do`, `Given(/…/) do`, `%r{…}` | strings = Cucumber Expression, regex literals |
| Rust* | cucumber-rs | `#[given("…")]`, `#[when(expr = "…")]`, `#[then(regex = r"…")]` (raw strings too) | literal = exact match; `expr` = Cucumber Expression; `regex` |
| Python* | behave / pytest-bdd | `@given('…')`, `@step`, `parsers.parse/cfparse/re/string(…)` | `parse` format, exact text, or regex |
| C#* | Reqnroll / SpecFlow | `[Given("…")]`, `[StepDefinition(@"…")]`, raw `"""…"""` | regex-looking patterns or Cucumber Expression |

Cucumber Expressions support `{int}`, `{float}`, `{word}`, `{string}`, `{}` and the numeric types,
optional text `request(s)`, alternation `on/off` and `\` escapes. Commented-out definitions are
ignored. The unmarked rows track Cucumber-maintained projects. The starred Rust, Python and C#
adapters support community-maintained frameworks.

## Build

Requirements: JDK 21+. The build uses the Gradle wrapper (Gradle 9.7.1), Kotlin 2.4 and the
IntelliJ Platform Gradle Plugin 2.x. The version is `pluginVersion` in `gradle.properties`
(currently `0.0.1`). The plugin compiles against an installed IntelliJ IDEA 2026.2
(`%LOCALAPPDATA%\Programs\IntelliJ IDEA`), or `-PplatformLocalPath=<IDE home>`. When neither
exists, IntelliJ IDEA `platformVersion` is downloaded.

```powershell
./gradlew.bat build              # compile + SelfTest + format check + build/distributions/cucumber-jetbrains-plugin-<version>.zip
./gradlew.bat spotlessApply      # format Kotlin sources and build scripts (ktfmt, Meta style)
./gradlew.bat buildPlugin        # plugin zip only
./gradlew.bat runIde             # start a sandbox IDE with the plugin
./gradlew.bat selfTest -PcucumberProjects=C:\src\my-service   # also check that project's steps all resolve
```

`build.ps1` wraps the same build and can install the result:

```powershell
./build.ps1 -Project C:\src\my-service        # build + SelfTest, also checking that project
./build.ps1 -Install -IdeHome "$env:LOCALAPPDATA\Programs\IntelliJ IDEA"   # install (IDE must be closed)
```

Repositories default to Maven Central, the Gradle Plugin Portal and JetBrains. Behind a corporate
mirror, override `repositoryMaven`, `repositoryGradlePlugins`, `repositoryIntellij` and
`repositoryIntellijDependencies` in `~/.gradle/gradle.properties`. If `services.gradle.org` is
blocked, also place the Gradle distribution in the wrapper cache.

`SelfTest` (no IDE, no JUnit) runs unit cases for the IDE-free `core` package (literals, patterns,
Gherkin dialects, formatter, outlines, tags, tag expressions, renames, run commands, Cucumber
Messages) and a create-step snippet round-trip for every language (generate, insert, re-parse, must
match). It also checks that every step in `src/test/resources/testdata/multi-language` matches
exactly one definition.

## Install

1. *Settings | Plugins | ⚙ | Install Plugin from Disk…* → `build/distributions/cucumber-jetbrains-plugin-<version>.zip`.
2. Restart the IDE.
3. If `.feature` files are still not highlighted, disable the bundled *Gherkin* / *Cucumber for …*
   plugins. Then make sure that `*.feature` is associated with the **Cucumber Feature** file type
   (*Settings | Editor | File Types*).

## Debug the plugin in IDEA

The quickest way is `./gradlew.bat runIde`; you can also start it in debug mode from IDEA's Gradle
tool window. To debug the plugin inside your regular IDEA instance instead:

1. Close the target IDE, then build and install the plugin into it:

   ```powershell
   ./build.ps1 -IdeHome "C:\Path\To\IntelliJ IDEA" -Install
   ```

2. In the target IDE, open *Help | Edit Custom VM Options…*. Preserve the existing options and add:

   ```text
   -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005
   ```

   Restart that IDE. Use `suspend=y` instead of `suspend=n` only when you need to stop the IDE
   before plugin initialization; it will wait for the debugger before starting.
3. Open this project in IDEA, select **Cucumber Remote Debug** in the run-configuration
   selector, and click **Debug**. It attaches to the IDE at `127.0.0.1:5005`.
4. Set breakpoints in `CucumberToolWindowFactory` for sidebar interactions, or in
   `feature/FeatureParserDefinition`, `feature/FeatureStepInspection`, and `StepIndex` for parsing and
   step resolution. Rebuild and reinstall after changing code; close the target IDE before using
   `-Install` again.

For startup/plugin-loading failures, inspect *Help | Show Log in Explorer* in the target IDE and
check `idea.log`. Include the complete exception and `Caused by` stack when reporting an error.

## Verify inside a real IDE

`scripts/verify-in-ide.ps1` starts an IDE headless in a throwaway sandbox (its own config, system
and plugin directories and a copy of the project; nothing of yours is touched), disables Gherkin,
runs the plugin's own step-resolution inspection, and fails on unexpected problems or plugin errors.

```powershell
./scripts/verify-in-ide.ps1                                          # src/test/resources/testdata/multi-language in RustRover: 0 / 0
./scripts/verify-in-ide.ps1 -Control                                 # baseline without the plugin
./scripts/verify-in-ide.ps1 -IdeHome "$env:LOCALAPPDATA\Programs\WebStorm"
./scripts/verify-in-ide.ps1 -Project src\test\resources\testdata\ambiguous -ExpectUndefined 1 -ExpectAmbiguous 1
./scripts/verify-in-ide.ps1 -Project C:\src\my-service
```

`scripts/format-in-ide.ps1` runs the IDE's command-line formatter (`bin\format.bat`) in a sandbox on a
messy `.feature` file and checks the plugin's Gherkin formatting result.

`./scripts/verify-navigation.ps1 -Unused` adds unused definitions to a copy of the fixture (Java, Go,
Python, Rust), runs *Find Unused Step Definitions* (Find tool window), the editor's *Delete Unused
Step Definition* and the bulk deletion, prints the edited files, checks every step still resolves and
undoes the deletion.

## Limitations

- Definitions are found textually: patterns built at run time (concatenation, constants,
  f-strings, interpolated strings) and localized step annotations in code (e.g. `@Angenommen`) are
  not recognised.
- *Create step definition* adds to an existing step file (*Create all undefined step definitions*
  can also create a new one for Java, Kotlin, JS/TS, Python and Ruby). godog
  handlers are registered in the `*godog.ScenarioContext` initializer when there is one.
- Renaming a step rewrites the step definition pattern only when it is safe (see above); Scenario
  Outline steps whose `<param>`s stand for a renamed part are not rewritten.
- Data table alignment counts characters, so columns with wide (CJK) characters may look unaligned.
- Runner autodetection is best-effort. Go, Rust and C# run the project's whole test command.
  Debugging covers Cucumber-JVM only; the test tree needs a runner with the `message` formatter
  (cucumber-jvm, cucumber-js, cucumber-ruby). With parallel execution, feature suites close at the end.
- JetBrains IDEs exclude `foo.js` next to `foo.ts` as TypeScript output, so it is not scanned.
- The word-index pre-filter trusts the IDE's lexer for what is a comment: a definition the
  textual parser would find inside a comment the IDE recognises but the parser does not (e.g. Ruby
  `=begin`/`=end`, nested `/* /* */ */`) is skipped in smart mode. Disable the pre-filter with
  `-Dcucumber.stepIndex.commentWords=true` (keep comment words) or `-Dcucumber.stepIndex.fullScan=true`.
- Built and tested against build 262 (2026.2) only (`since-build="262"`).

## Credits

The Cucumber logo used for the plugin, tool-window and `.feature` file icons is taken from
[cucumber/cucumber-js](https://github.com/cucumber/cucumber-js/blob/main/docs/images/logo.svg)
(MIT License). Cucumber is a trademark of its respective owners.

The Gherkin keyword tables (`core/GherkinDialectData.kt`) are generated from
[gherkin-languages.json](https://github.com/cucumber/gherkin/blob/main/gherkin-languages.json)
(MIT License).

## Layout

```
src/main/kotlin/io/github/fangzhengjie/cucumber/
  core/     IDE-free logic: languages, patterns, literal readers, snippets, Gherkin dialects and line
            classification (GherkinSyntax), formatter, outlines, tags, run commands
  feature/  standalone feature file type, lexer/parser, PSI, highlighting, completion, inspections,
            formatting, structure view, folding
  lang/     one adapter per source language: parse step definitions, write snippets
  ide/      step index and usages, navigation, rename, tool window, run configuration, settings
src/test/kotlin/…/SelfTest.kt   dependency-free tests (./gradlew.bat selfTest)
src/test/resources/testdata/multi-language/  features + step files in every language (all must resolve)
src/test/resources/testdata/ambiguous/ one undefined and one ambiguous step (negative IDE check)
```
