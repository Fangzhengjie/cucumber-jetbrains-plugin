package io.github.fangzhengjie.cucumber

import io.github.fangzhengjie.cucumber.SelfTest.check
import io.github.fangzhengjie.cucumber.SelfTest.equal
import io.github.fangzhengjie.cucumber.core.CucumberMessages
import io.github.fangzhengjie.cucumber.core.GherkinKeywordType
import io.github.fangzhengjie.cucumber.core.GherkinSyntax
import io.github.fangzhengjie.cucumber.core.IgnoredPaths
import io.github.fangzhengjie.cucumber.core.Language
import io.github.fangzhengjie.cucumber.core.PatternKind
import io.github.fangzhengjie.cucumber.core.Patterns
import io.github.fangzhengjie.cucumber.core.StepDefinitionInfo
import io.github.fangzhengjie.cucumber.core.StepRename
import io.github.fangzhengjie.cucumber.ide.JvmCommand
import io.github.fangzhengjie.cucumber.lang.JavaScriptAdapter
import io.github.fangzhengjie.cucumber.lang.JvmAdapter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Regression tests for the issues found in the code review (IDE-free). */
internal object ReviewFixTests {

  fun run() {
    ignoredPaths()
    keywordMatching()
    outlineRename()
    regexMatchSemantics()
    messageRuns()
    messageFileRestart()
    jvmDirectories()
    jvmRunnerSelection()
    gradleSelfContained()
  }

  /** One Cucumber run; every run uses the same ids, as sequential id generators do. */
  private fun messageRun(name: String): String =
      listOf(
              """{"meta":{"protocolVersion":"24.0.0"}}""",
              """{"gherkinDocument":{"uri":"features/a.feature","feature":{"location":{"line":1,"column":1},"keyword":"Feature","name":"A","children":[{"scenario":{"id":"s1","location":{"line":2,"column":3},"keyword":"Scenario","name":"$name","steps":[{"id":"st1","location":{"line":3,"column":5},"keyword":"Given ","text":"x"}]}}]}}}""",
              """{"pickle":{"id":"p1","uri":"features/a.feature","name":"$name","astNodeIds":["s1"],"steps":[{"id":"ps1","text":"x","astNodeIds":["st1"]}]}}""",
              """{"testRunStarted":{"timestamp":{"seconds":1,"nanos":0}}}""",
              """{"testCase":{"id":"tc1","pickleId":"p1","testSteps":[{"id":"t1","pickleStepId":"ps1"}]}}""",
              """{"testCaseStarted":{"id":"c1","testCaseId":"tc1"}}""",
              """{"testStepStarted":{"testCaseStartedId":"c1","testStepId":"t1"}}""",
              """{"testStepFinished":{"testCaseStartedId":"c1","testStepId":"t1","testStepResult":{"status":"PASSED"}}}""",
              """{"testCaseFinished":{"testCaseStartedId":"c1"}}""",
              """{"testRunFinished":{"success":true}}""")
          .joinToString("\n", postfix = "\n")

  /** Every started node has a unique id and every suite is finished. */
  private fun checkTree(events: List<CucumberMessages.Event>, scenarios: Int, label: String) {
    val started =
        events.filter { it is CucumberMessages.SuiteStarted || it is CucumberMessages.TestStarted }
    equal(started.size, started.map { it.id }.toSet().size, "$label: unique node ids")
    equal(
        events.count { it is CucumberMessages.SuiteStarted },
        events.count { it is CucumberMessages.SuiteFinished },
        "$label: every suite finished")
    equal(scenarios * 3, started.size, "$label: feature, scenario and step per run")
  }

  private fun messageRuns() {
    val converter = CucumberMessages.Converter("/work")
    val events = ArrayList<CucumberMessages.Event>()
    for (line in (messageRun("One") + messageRun("Two")).lines()) {
      events.addAll(converter.accept(line))
    }
    events.addAll(converter.finish())
    checkTree(events, 2, "messages: two runs in one stream")
    equal(
        listOf("One", "Two"),
        events
            .filterIsInstance<CucumberMessages.SuiteStarted>()
            .map { it.name }
            .filter { it != "A" },
        "messages: both runs shown")
  }

  private fun messageFileRestart() {
    val file = Path.of("build", "selftest-run", "review", "messages.ndjson").toAbsolutePath()
    Files.createDirectories(file.parent)
    Files.deleteIfExists(file)
    val follower = CucumberMessages.FileFollower(file)
    val converter = CucumberMessages.Converter("/work")
    val events = ArrayList<CucumberMessages.Event>()
    var restarts = 0
    fun poll() =
        follower.read({
          restarts++
          events.addAll(converter.restart())
        }) { line ->
          events.addAll(converter.accept(line))
        }

    poll()
    val first = messageRun("One")
    val cut = first.indexOf("testCaseStarted")
    Files.writeString(file, first.substring(0, cut), StandardCharsets.UTF_8)
    poll()
    Files.writeString(file, first, StandardCharsets.UTF_8)
    poll()
    equal(0, restarts, "follower: appended content is no restart")
    // The next run truncates the file and writes more than was read before the next poll.
    Files.writeString(file, messageRun("Second run"), StandardCharsets.UTF_8)
    poll()
    equal(1, restarts, "follower: rewritten file (longer) restarts")
    // ... or less: the file is shorter than what was read.
    Files.writeString(file, messageRun("3").substring(0, 40), StandardCharsets.UTF_8)
    poll()
    equal(2, restarts, "follower: truncated file restarts")
    Files.writeString(file, messageRun("3"), StandardCharsets.UTF_8)
    poll()
    follower.rest()?.let { events.addAll(converter.accept(it)) }
    events.addAll(converter.finish())
    checkTree(events, 3, "follower: three runs")
    equal(
        listOf("One", "Second run", "3"),
        events
            .filterIsInstance<CucumberMessages.SuiteStarted>()
            .map { it.name }
            .filter { it != "A" },
        "follower: every run read from its start")
    Files.deleteIfExists(file)
  }

  private fun write(path: Path, text: String) {
    Files.createDirectories(path.parent)
    Files.writeString(path, text, StandardCharsets.UTF_8)
  }

  private fun jvmDirectories() {
    val root = Path.of("build", "selftest-run", "review", "maven-modules").toAbsolutePath()
    write(root.resolve("pom.xml"), "<project/>")
    val app = root.resolve("app")
    write(app.resolve("pom.xml"), "<project/>")
    write(
        app.resolve("src/test/java/RunCukesTest.java"),
        "@RunWith(Cucumber.class)\npublic class RunCukesTest {}\n")
    write(app.resolve("src/test/resources/f/a.feature"), "Feature: A\n")

    val module =
        JvmCommand.build(root.toString(), listOf(app.toString()), JvmCommand.Options(), false)
    equal(
        listOf(
            "mvn",
            "test",
            "-pl",
            "app",
            "-am",
            "-Dtest=RunCukesTest",
            "-Dsurefire.failIfNoSpecifiedTests=false"),
        module.argv,
        "jvm: module directory -> that module, all its features")
    equal(mapOf<String, String>(), module.env, "jvm: module directory -> no CUCUMBER_FEATURES")
    equal(root.toString(), module.workDir, "jvm: module directory -> build root")

    val project =
        JvmCommand.build(root.toString(), listOf(root.toString()), JvmCommand.Options(), false)
    check(
        project.argv.none { it.startsWith("-Dcucumber.features") },
        "jvm: project root -> no cucumber.features")
    check("-pl" !in project.argv, "jvm: project root -> whole build")

    val folder = app.resolve("src/test/resources/f")
    equal(
        "-Dcucumber.features=src/test/resources/f",
        JvmCommand.build(root.toString(), listOf(folder.toString()), JvmCommand.Options(), false)
            .argv
            .last(),
        "jvm: feature folder relative to its module")

    val gradle = Path.of("build", "selftest-run", "review", "gradle-modules").toAbsolutePath()
    write(gradle.resolve("settings.gradle.kts"), "include(\"lib\")\n")
    write(gradle.resolve("lib/build.gradle.kts"), "")
    val lib =
        JvmCommand.build(
            gradle.toString(),
            listOf(gradle.resolve("lib").toString()),
            JvmCommand.Options(),
            false)
    equal(listOf("gradle", ":lib:test"), lib.argv, "jvm: gradle module directory -> its test task")
    equal(mapOf<String, String>(), lib.env, "jvm: gradle module directory -> no CUCUMBER_FEATURES")
  }

  private fun jvmRunnerSelection() {
    val module = Path.of("build", "selftest-run", "review", "runners").toAbsolutePath()
    write(module.resolve("pom.xml"), "<project/>")
    write(
        module.resolve("src/test/java/com/x/RunXTest.java"),
        "@RunWith(Cucumber.class)\npublic class RunXTest {}\n")
    write(
        module.resolve("src/test/java/com/y/RunYTest.java"),
        "@RunWith(Cucumber.class)\npublic class RunYTest {}\n")
    val feature = module.resolve("src/test/resources/com/y/b.feature")
    write(feature, "Feature: B\n")
    val selected =
        JvmCommand.build(module.toString(), listOf("$feature:2"), JvmCommand.Options(), false)
    check(
        "-Dtest=RunYTest" in selected.argv,
        "jvm: one runner, the closest to the feature: " + selected.argv)
    val all = JvmCommand.build(module.toString(), listOf(), JvmCommand.Options(), false)
    check(
        "-Dtest=RunXTest,RunYTest" in all.argv,
        "jvm: every runner when running all features: " + all.argv)
    val other = module.resolve("src/test/resources/z.feature")
    write(other, "Feature: Z\n")
    check(
        "-Dtest=RunXTest" in
            JvmCommand.build(
                    module.toString(), listOf(other.toString()), JvmCommand.Options(), false)
                .argv,
        "jvm: no closer runner -> the first by name")
  }

  private fun gradleSelfContained() {
    val gradle = Path.of("build", "selftest-run", "review", "gradle-platform").toAbsolutePath()
    write(gradle.resolve("build.gradle"), "")
    write(
        gradle.resolve("src/test/java/RunTest.java"),
        "@Suite\n@IncludeEngines(\"cucumber\")\npublic class RunTest {}\n")
    val feature = gradle.resolve("src/test/resources/p.feature")
    write(feature, "Feature: P\n")
    val command =
        JvmCommand.build(
            gradle.toString(),
            listOf("$feature:3"),
            JvmCommand.Options(
                tags = "@a", plugin = "message:/m.ndjson", gradleInitScript = "/i.gradle"),
            false)
    for (arg in
        listOf(
            "-Dcucumber.features=src/test/resources/p.feature:3",
            "-Dcucumber.filter.tags=@a",
            "-Dcucumber.plugin=message:/m.ndjson",
            "-Dcucumber.jetbrains.engineOnly=true")) {
      check(arg in command.argv, "gradle: copied command carries $arg without environment")
    }
    val script = JvmCommand.GRADLE_INIT_SCRIPT
    check(
        script.contains("System.getProperty(property)"),
        "gradle: init script falls back to -D properties")
    check(
        script.contains("'cucumber.jetbrains.jdwp'") &&
            script.contains("'cucumber.jetbrains.engineOnly'"),
        "gradle: init script reads the plugin's -D properties")
  }

  private fun regexMatchSemantics() {
    val java =
        JvmAdapter.JAVA.parse("@Given(\"^I have (\\\\d+) cukes\")\nvoid a(int n) {}\n").single()
    check(java.matches("I have 5 cukes"), "jvm regex: whole step matches")
    check(
        !java.matches("I have 5 cukes today"), "jvm regex: Matcher.matches() needs the whole step")
    val kotlin = JvmAdapter.KOTLIN.parse("@When(\"cukes (\\\\d+)\$\")\nfun a(n: Int) {}\n").single()
    check(!kotlin.matches("many cukes 5"), "kotlin regex: unanchored start still needs whole step")
    val js = JavaScriptAdapter.parse("Given(/^I have (\\d+) cukes/, () => {});\n").single()
    check(js.matches("I have 5 cukes today"), "cucumber-js regex keeps find() semantics")
  }

  private fun outlineRename() {
    val pattern = "I have {int} cukes in my {word}"
    val regex = Patterns.cucumberExpression(pattern)
    val plan =
        StepRename.plan(
            PatternKind.CUCUMBER_EXPRESSION,
            pattern,
            regex,
            "I have <n> cukes in my <where>",
            "I own <n> cukes in my <where>")
    equal(
        "I own {int} cukes in my {word}",
        plan?.newPattern,
        "outline rename: plan from <param> text")
    if (plan != null) {
      equal(
          "I own <count> cukes in my <place>",
          StepRename.rewriteStep(plan, regex, "I have <count> cukes in my <place>"),
          "outline rename: other outline step keeps its params")
      equal(
          "I own 3 cukes in my bag",
          StepRename.rewriteStep(plan, regex, "I have 3 cukes in my bag"),
          "outline rename: concrete step keeps its values")
      equal(
          null,
          StepRename.rewriteStep(plan, regex, "I have <n> pickles in my <where>"),
          "outline rename: other literal text is not rewritten")
    }
    equal(
        null,
        StepRename.plan(
            PatternKind.CUCUMBER_EXPRESSION,
            pattern,
            regex,
            "I have <n> cukes in my <where>",
            "I have <m> cukes in my <where>"),
        "outline rename: changing a parameter is refused")

    val source = "class S {\n  @Given(\"$pattern\")\n  public void s(int n, String w) {}\n}\n"
    val definition = JvmAdapter.JAVA.parse(source).single()
    val edit =
        StepRename.renameDefinition(
            source,
            definition,
            "I have <n> cukes in my <where>",
            "I own <n> cukes in my <where>",
            "I have 5 cukes in my belly")
    equal(
        "\"I own {int} cukes in my {word}\"",
        edit?.replacement,
        "outline rename: definition rewritten")
    equal(
        null,
        StepRename.renameDefinition(
            source, definition, "I have <n> cukes in my <where>", "I own <n> cukes in my <where>"),
        "outline rename: refused without the concrete match text to verify against")
  }

  private fun definition(language: Language, keyword: String, pattern: String): StepDefinitionInfo =
      StepDefinitionInfo.of(
          language,
          keyword,
          PatternKind.EXACT,
          pattern,
          Patterns.toRegex(PatternKind.EXACT, pattern),
          0)

  private fun keywordMatching() {
    val behaveGiven = definition(Language.PYTHON, "given", "x")
    val behaveStep = definition(Language.PYTHON, "step", "x")
    val csharpGeneric = definition(Language.CSHARP, "step", "x")
    val rustWhen = definition(Language.RUST, "when", "x")
    val javaGiven = definition(Language.JAVA, "given", "x")
    val goGiven = definition(Language.GO, "given", "x")
    check(behaveGiven.matches("x", "given"), "behave @given binds Given")
    check(!behaveGiven.matches("x", "when"), "behave @given does not bind When")
    check(behaveGiven.matches("x", null), "unknown keyword is not filtered")
    check(behaveStep.matches("x", "then"), "behave @step binds any keyword")
    check(csharpGeneric.matches("x", "when"), "[StepDefinition] binds any keyword")
    check(!rustWhen.matches("x", "then"), "cucumber-rs #[when] does not bind Then")
    check(javaGiven.matches("x", "then"), "Cucumber-JVM ignores the keyword")
    check(goGiven.matches("x", "when"), "godog ignores the keyword")
    check(!behaveGiven.matches("y", "given"), "keyword never makes other text match")

    equal(
        "given",
        GherkinSyntax.effectiveKeyword(GherkinKeywordType.AND, null),
        "leading And is given")
    equal("when", GherkinSyntax.effectiveKeyword(GherkinKeywordType.AND, "when"), "And continues")
    equal("then", GherkinSyntax.effectiveKeyword(GherkinKeywordType.BUT, "then"), "But continues")
    equal("then", GherkinSyntax.effectiveKeyword(GherkinKeywordType.THEN, "given"), "Then is then")
  }

  private fun ignoredPaths() {
    // The project's own location never matters: paths are relative to a root.
    check(!IgnoredPaths.isIgnored("src/test/java/Steps.java"), "plain source is scanned")
    check(!IgnoredPaths.isIgnored("features/a.feature"), "features dir is scanned")
    // Output directories above `src` are skipped, wherever the module is.
    check(IgnoredPaths.isIgnored("target/test-classes/a.feature"), "maven target skipped")
    check(IgnoredPaths.isIgnored("module/build/resources/a.feature"), "gradle build skipped")
    check(IgnoredPaths.isIgnored("bin/Debug/Steps.cs"), ".NET bin skipped")
    // ... but a source package with such a name is kept.
    check(!IgnoredPaths.isIgnored("src/test/java/com/acme/build/Steps.java"), "src/.../build kept")
    check(!IgnoredPaths.isIgnored("src\\main\\out\\steps.go"), "backslash paths understood")
    // Dependencies and hidden directories are always skipped.
    check(IgnoredPaths.isIgnored("web/node_modules/x/steps.js"), "node_modules skipped")
    check(IgnoredPaths.isIgnored("src/node_modules/x/steps.js"), "node_modules under src skipped")
    check(IgnoredPaths.isIgnored(".venv/lib/steps.py"), "hidden venv skipped")
    check(IgnoredPaths.isIgnored("vendor/github.com/x/steps.go"), "go vendor skipped")
    check(IgnoredPaths.isIgnoredDirectory("build"), "build directory skipped")
    check(!IgnoredPaths.isIgnoredDirectory("src/build"), "src/build directory kept")
    equal(false, IgnoredPaths.isIgnored("steps.py"), "root file kept")
  }
}
