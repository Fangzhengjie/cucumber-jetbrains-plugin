package io.github.fangzhengjie.cucumber

import io.github.fangzhengjie.cucumber.core.CucumberMessages
import io.github.fangzhengjie.cucumber.core.FeatureOutline
import io.github.fangzhengjie.cucumber.core.GherkinDialects
import io.github.fangzhengjie.cucumber.core.GherkinFormatter
import io.github.fangzhengjie.cucumber.core.GherkinKeywordType
import io.github.fangzhengjie.cucumber.core.GherkinOutline
import io.github.fangzhengjie.cucumber.core.GherkinSyntax
import io.github.fangzhengjie.cucumber.core.IgnoredPaths
import io.github.fangzhengjie.cucumber.core.Language
import io.github.fangzhengjie.cucumber.core.Literals
import io.github.fangzhengjie.cucumber.core.Outlines
import io.github.fangzhengjie.cucumber.core.PatternKind
import io.github.fangzhengjie.cucumber.core.Patterns
import io.github.fangzhengjie.cucumber.core.RunResults
import io.github.fangzhengjie.cucumber.core.Snippets
import io.github.fangzhengjie.cucumber.core.StepArguments
import io.github.fangzhengjie.cucumber.core.StepDefinitionInfo
import io.github.fangzhengjie.cucumber.core.StepTextTemplates
import io.github.fangzhengjie.cucumber.core.StepWords
import io.github.fangzhengjie.cucumber.core.TagExpressions
import io.github.fangzhengjie.cucumber.core.Tags
import io.github.fangzhengjie.cucumber.ide.CucumberCommands
import io.github.fangzhengjie.cucumber.ide.JvmCommand
import io.github.fangzhengjie.cucumber.lang.JavaScriptAdapter
import io.github.fangzhengjie.cucumber.lang.JvmAdapter
import io.github.fangzhengjie.cucumber.lang.RustAdapter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Objects
import java.util.regex.Pattern
import kotlin.system.exitProcess

/**
 * Dependency-free tests (no JUnit, no IDE), run by build.ps1.
 *
 * Usage: `SelfTest <fixture dir> [--project <dir>]...`
 */
object SelfTest {

  private var failures = 0
  private var checks = 0

  @JvmStatic
  fun main(args: Array<String>) {
    literals()
    patterns()
    comments()
    snippetsRoundTrip()
    stepTextTemplates(args)
    ctrlHoverLiteralRanges()
    stepWordsCoverAdapters(args)
    runResults()
    stepDefinitionBlocks(args)
    gherkinDialects()
    editorFormatterAndOutline(args)
    outlineParsing()
    outlineProblems()
    tagParsing()
    stepArguments()
    stepdefsRename()
    stepdefsRenameLiterals()
    stepdefsNewStepFiles()
    tagExpressions()
    featureOutline()
    cucumberMessages()
    runCommands()
    localizedStructure()
    ReviewFixTests.run()
    var expectProject = false
    for (arg in args) {
      if (arg == "--project") {
        expectProject = true
      } else {
        resolveProject(Path.of(arg), !expectProject)
        expectProject = false
      }
    }
    System.out.printf("%d checks, %d failures%n", checks, failures)
    if (failures > 0) {
      exitProcess(1)
    }
  }

  /** Reports the totals and fails the process on failures (for other test entry points). */
  internal fun finish() {
    System.out.printf("%d checks, %d failures%n", checks, failures)
    if (failures > 0) {
      exitProcess(1)
    }
  }

  // --- feature: i18n --------------------------------------------------------

  private fun gherkinDialects() {
    equal("en", GherkinDialects.dialectFor("Feature: x\n  Scenario: y\n").code, "default dialect")
    equal(
        "en",
        GherkinDialects.dialectFor("Feature: x\n# language: de\n").code,
        "language comment after content")
    equal("zh-CN", GherkinDialects.dialectFor("# language: zh-CN\n功能: 测试\n").code, "zh-CN")
    equal(
        "de",
        GherkinDialects.dialectFor("\n# a comment\n  #language:de  \nFunktionalität: x\n").code,
        "de after a comment")
    equal("fr", GherkinDialects.dialectFor("# language: FR\n").code, "case-insensitive code")
    equal("en", GherkinDialects.dialectFor("# language: xx-unknown\n").code, "unknown code")
    check(GherkinDialects.all.size >= 70, "dialect count ${GherkinDialects.all.size}")
    val codes = listOf("en", "zh-CN", "zh-TW", "ja", "ko", "de", "fr", "es", "pt", "it", "nl", "ru")
    for (code in codes + "pl") {
      check(GherkinDialects.forCode(code) != null, "dialect $code")
    }

    val zh = GherkinDialects.forCode("zh_cn")!!
    stepMatch(zh, "假如 the java service is ready", "假如", GherkinKeywordType.GIVEN, 2)
    stepMatch(zh, "假如the java service is ready", "假如", GherkinKeywordType.GIVEN, 2)
    stepMatch(zh, "当the client", "当", GherkinKeywordType.WHEN, 1)
    stepMatch(zh, "那么 ok", "那么", GherkinKeywordType.THEN, 2)
    stepMatch(zh, "而且 ok", "而且", GherkinKeywordType.AND, 2)
    stepMatch(zh, "但是ok", "但是", GherkinKeywordType.BUT, 2)
    stepMatch(zh, "* ok", "*", GherkinKeywordType.AND, 1)
    check(zh.matchStep("Given ok") == null, "zh-CN does not accept English steps")
    sectionMatch(zh, "功能: 测试", GherkinKeywordType.FEATURE, 3)
    sectionMatch(zh, "功能：测试", GherkinKeywordType.FEATURE, 3)
    sectionMatch(zh, "场景大纲: x", GherkinKeywordType.SCENARIO_OUTLINE, 5)
    sectionMatch(zh, "例子:", GherkinKeywordType.EXAMPLES, 3)

    val de = GherkinDialects.forCode("de")!!
    stepMatch(de, "Gegeben sei ein Konto", "Gegeben sei", GherkinKeywordType.GIVEN, 11)
    stepMatch(de, "Angenommen x", "Angenommen", GherkinKeywordType.GIVEN, 10)
    stepMatch(de, "Und x", "Und", GherkinKeywordType.AND, 3)
    check(de.matchStep("Undx") == null, "de keywords need a space")
    sectionMatch(de, "Szenariogrundriss: x", GherkinKeywordType.SCENARIO_OUTLINE, 18)
    sectionMatch(de, "Beispiele:", GherkinKeywordType.EXAMPLES, 10)

    val fr = GherkinDialects.forCode("fr")!!
    stepMatch(fr, "Etant donné qu'il pleut", "Etant donné qu'", GherkinKeywordType.GIVEN, 15)
    stepMatch(fr, "Étant donné que x", "Étant donné que", GherkinKeywordType.GIVEN, 15)
    stepMatch(fr, "Lorsqu'il pleut", "Lorsqu'", GherkinKeywordType.WHEN, 7)
    stepMatch(fr, "Et qu'il pleut", "Et qu'", GherkinKeywordType.AND, 6)
    stepMatch(fr, "Alors x", "Alors", GherkinKeywordType.THEN, 5)
    sectionMatch(fr, "Scénario: x", GherkinKeywordType.SCENARIO, 9)

    val en = GherkinDialects.ENGLISH
    stepMatch(en, "Given x", "Given", GherkinKeywordType.GIVEN, 5)
    stepMatch(en, "given x", "given", GherkinKeywordType.GIVEN, 5)
    stepMatch(en, "But", "But", GherkinKeywordType.BUT, 3)
    check(en.matchStep("Givenx") == null, "English keywords need a space")
    check(en.matchStep("Scenario: x") == null, "a section is no step")
    sectionMatch(en, "Scenario Outline: x", GherkinKeywordType.SCENARIO_OUTLINE, 17)
    sectionMatch(en, "Scenarios:", GherkinKeywordType.EXAMPLES, 10)
    sectionMatch(en, "Example: x", GherkinKeywordType.SCENARIO, 8)
    check(en.matchSection("Feature x") == null, "a section keyword needs a colon")
    equal(GherkinKeywordType.WHEN, GherkinDialects.stepType("当"), "stepType across dialects")
    equal(GherkinKeywordType.GIVEN, GherkinDialects.stepType("Given"), "stepType English first")
    equal("given", GherkinKeywordType.GIVEN.stepKeyword(), "canonical keyword")
    check(
        Regex("^(" + zh.alternation(GherkinKeywordType.WHEN) + ")").containsMatchIn("当x"),
        "zh regex")
    check(
        Regex("^(" + en.alternation(GherkinKeywordType.GIVEN) + ")").find("Givenx") == null,
        "en regex needs a space")
  }

  private fun stepMatch(
      dialect: io.github.fangzhengjie.cucumber.core.Dialect,
      line: String,
      keyword: String,
      type: GherkinKeywordType,
      end: Int,
  ) {
    val match = dialect.matchStep(line)
    val actual = match?.let { "${it.keyword}/${it.type}/${it.end}" }
    equal("$keyword/$type/$end", actual, "$dialect step '$line'")
  }

  private fun sectionMatch(
      dialect: io.github.fangzhengjie.cucumber.core.Dialect,
      line: String,
      type: GherkinKeywordType,
      end: Int,
  ) {
    val match = dialect.matchSection(line)
    equal("$type/$end", match?.let { "${it.type}/${it.end}" }, "$dialect section '$line'")
  }

  // --- feature: editor ---------------------------------------------------------

  private fun editorFormatterAndOutline(args: Array<String>) {
    val messy =
        listOf(
                "@smoke   ",
                "  Feature: Shopping   ",
                " Some description",
                " # a comment",
                "     Background:",
                " Given a store",
                "   ",
                "       Scenario Outline: buy <n>",
                "  When I buy <n> items",
                "    Then I have <n>",
                "   Examples:",
                " |n|total|",
                "   |  10 | 1\\|2 |",
                "      # between",
                " | 3 |x|",
                "",
                "  Rule: Members",
                " @vip",
                " Scenario: discount",
                " Given a member",
                "     \"\"\"json",
                "     {",
                "       \"a\": 1   ",
                "",
                "     }",
                "     \"\"\"",
                " And a table",
                " | a | bb |",
                " |ccc|d|",
                "# end",
                "")
            .joinToString("\n")
    val expected =
        listOf(
                "@smoke",
                "Feature: Shopping",
                "  Some description",
                "  # a comment",
                "  Background:",
                "    Given a store",
                "",
                "  Scenario Outline: buy <n>",
                "    When I buy <n> items",
                "    Then I have <n>",
                "    Examples:",
                "      | n  | total |",
                "      | 10 | 1\\|2  |",
                "      # between",
                "      | 3  | x     |",
                "",
                "  Rule: Members",
                "    @vip",
                "    Scenario: discount",
                "      Given a member",
                "        \"\"\"json",
                "        {",
                "          \"a\": 1   ",
                "",
                "        }",
                "        \"\"\"",
                "      And a table",
                "        | a   | bb |",
                "        | ccc | d  |",
                "        # end",
                "")
            .joinToString("\n")
    val formatted = GherkinFormatter.format(messy)
    equal(expected, formatted, "gherkin format")
    equal(expected, GherkinFormatter.format(formatted), "gherkin format is idempotent")
    equal(
        "Feature: x\r\n    Scenario: y\r\n        * z\r\n",
        GherkinFormatter.format("Feature: x  \r\nScenario: y\r\n* z\r\n", 4),
        "gherkin format CRLF and indent size")
    equal(
        "Feature: x\n  Scenario: y\n    Given a\n      \"\"\"\n      unterminated\n        kept",
        GherkinFormatter.format("Feature: x\nScenario: y\nGiven a\n\"\"\"\nunterminated\n  kept"),
        "gherkin format unterminated doc string")
    equal(
        "Feature: x\n  Scenario: y\n    Given a\n      ```\n      \"\"\"\n      ```",
        GherkinFormatter.format("Feature: x\nScenario: y\nGiven a\n```\n\"\"\"\n```"),
        "gherkin format backtick doc string")
    val foreign =
        "# language: fr\nFonctionnalité: x\n Scénario: y\nSoit a  \n  |a|bb|\n      |ccc|d|   \n"
    equal(
        "# language: fr\nFonctionnalité: x\n  Scénario: y\n    Soit a\n      | a   | bb |\n      | ccc | d  |\n",
        GherkinFormatter.format(foreign),
        "gherkin format normalizes other languages")

    val outline = GherkinOutline.parse(expected)
    equal(1, outline.roots.size, "outline roots")
    val feature = outline.roots[0]
    equal("Feature: Shopping", feature.toString(), "outline feature")
    equal(
        "[BACKGROUND, SCENARIO_OUTLINE, RULE]",
        feature.children.map { it.kind }.toString(),
        "outline feature children")
    val scenarioOutline = feature.children[1]
    equal(
        "[STEP, STEP, EXAMPLES]",
        scenarioOutline.children.map { it.kind }.toString(),
        "outline scenario outline children")
    val examples = scenarioOutline.children[2]
    equal(expected.indexOf("| 3  | x     |") + 14, examples.endOffset, "outline examples end")
    equal(
        examples.endOffset, scenarioOutline.endOffset, "outline scenario end excludes blank lines")
    val rule = feature.children[2]
    equal("Rule: Members", rule.toString(), "outline rule")
    equal("[SCENARIO]", rule.children.map { it.kind }.toString(), "outline rule children")
    equal(
        "[Given a member, And a table]",
        rule.children[0].children.map { "${it.keyword} ${it.title}" }.toString(),
        "outline steps")
    equal("[TABLE, DOC_STRING, TABLE]", outline.blocks.map { it.kind }.toString(), "outline blocks")
    equal(
        expected.indexOf("        }\n        \"\"\"") + 21,
        outline.blocks[1].endOffset,
        "doc string end")
    equal(
        "[FEATURE: [SCENARIO: [STEP]]]",
        GherkinOutline.parse(foreign)
            .roots
            .map { f ->
              "${f.kind}: " + f.children.map { s -> "${s.kind}: " + s.children.map { it.kind } }
            }
            .toString(),
        "outline of other languages")

    val fixture = args.firstOrNull()?.let { Path.of(it).resolve("features") }
    if (fixture == null || !Files.isDirectory(fixture)) {
      check(false, "editor fixture features directory")
      return
    }
    Files.list(fixture).use { files ->
      for (file in files.filter { it.toString().endsWith(".feature") }.toList()) {
        val text = String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        val result = GherkinFormatter.format(text)
        equal(
            text.filterNot { Character.isWhitespace(it) },
            result.filterNot { Character.isWhitespace(it) },
            "format changes only whitespace in ${file.fileName}")
        equal(result, GherkinFormatter.format(result), "format idempotent for ${file.fileName}")
        check(GherkinOutline.parse(text).roots.size == 1, "outline of ${file.fileName}")
      }
    }
  }

  // feature: outline -----------------------------------------------------------

  private val OUTLINE_FEATURE =
      """
      Feature: Cukes
        Scenario Outline: eating <start> cukes
          Given there are <start> cucumbers
          When I eat <eat> cucumbers named "<name>"
            ""${'"'}
            note <eat>
            ""${'"'}
          Then I should have <left> cucumbers
            | <left> | x |

          @slow
          Examples: first
            | start | eat | left | name | unused |
            | 12    | 5   | 7    | a\|b | u      |
            | 20    | 5   | 15   | c    | u      |

        Scenario: plain
          Given I have <count> cukes

        Scenario Outline: missing examples
          Given I have <n> cukes
      """
          .trimIndent()

  private fun outlineParsing() {
    val scenarios = Outlines.scenarios(OUTLINE_FEATURE)
    equal(3, scenarios.size, "outline scenario count")
    val outline = scenarios[0]
    check(outline.isOutline && outline.outlineKeyword, "outline flagged")
    equal(
        listOf("start", "start", "eat", "name", "eat", "left", "left"),
        outline.params.map { it.name },
        "outline params (title, steps, doc string, table)")
    equal(1, outline.examples.size, "examples tables")
    equal(
        listOf("start", "eat", "left", "name", "unused"),
        outline.examples[0].header.map { it.text },
        "examples header")
    equal(2, outline.examples[0].rows.size, "examples rows")
    equal("a|b", outline.firstRowValues()["name"], "escaped pipe in cell")
    equal(
        "I eat 5 cucumbers named \"a|b\"",
        outline.substitute("I eat <eat> cucumbers named \"<name>\""),
        "substitution with first row")
    equal(
        "keep <zzz> 12",
        Outlines.substitute("keep <zzz> <a>", mapOf("a" to "12")),
        "unknown param kept")
    val eat = OUTLINE_FEATURE.indexOf("<eat>")
    equal("eat", outline.paramAt(eat + 2)?.name, "param at offset")
    val header = OUTLINE_FEATURE.indexOf("| start") + 2
    equal("start", outline.headerCellAt(header)?.text, "header cell at offset")
    equal(2, outline.usages("start").size, "usages of <start>")
    equal(1, outline.headerCells("left").size, "header cells for left")
    equal(outline, Outlines.scenarioAt(scenarios, eat), "scenario at offset")
    check(!scenarios[1].isOutline, "plain scenario")
    equal(
        "I have <count> cukes",
        scenarios[1].substitute("I have <count> cukes"),
        "no substitution in plain scenario")
    check(
        Outlines.scenarios(
                "Feature: x\n  Scenario: y\n    Given z\n    Examples:\n      | a |\n      | 1 |\n")[
                0]
            .isOutline,
        "Scenario with Examples is an outline")
  }

  private fun outlineProblems() {
    val problems = Outlines.problems(Outlines.scenarios(OUTLINE_FEATURE))
    val messages = problems.map { it.message }
    check(messages.any { it.contains("'unused'") }, "unused column reported: $messages")
    check(
        messages.none { it.contains("<start>") || it.contains("<eat>") },
        "used params not reported: $messages")
    check(
        messages.any { it.contains("<count>") && it.contains("Scenario Outline") },
        "plain <x> reported: $messages")
    check(
        messages.contains("Scenario Outline has no Examples"),
        "missing examples reported: $messages")
    val unknown =
        Outlines.problems(
            Outlines.scenarios(
                "Scenario Outline: a\n  Given <x> and <y>\n  Examples:\n    | x |\n    | 1 |\n"))
    equal(
        listOf("Parameter <y> is not a column of any Examples table"),
        unknown.map { it.message },
        "unknown param")
    equal(Outlines.Severity.WARNING, unknown.firstOrNull()?.severity, "unknown param severity")
  }

  private fun tagParsing() {
    val text = "@a @b-c # @comment\nFeature: f\n  \"\"\"\n  @doc\n  \"\"\"\n  @d\n  Scenario: s\n"
    equal(listOf("@a", "@b-c", "@d"), Tags.find(text).map { it.name }, "tags")
    equal(text.lastIndexOf("@d"), Tags.find(text)[2].start, "tag offset")
  }

  private fun stepArguments() {
    val ints =
        StepDefinitionInfo.of(
            Language.JAVA,
            "given",
            PatternKind.CUCUMBER_EXPRESSION,
            "I have {int} cukes in {word}",
            Patterns.cucumberExpression("I have {int} cukes in {word}"),
            0)
    equal(
        listOf(
            StepArguments.Argument("int", "{int}", "42"),
            StepArguments.Argument("word", "{word}", "box")),
        StepArguments.extract(ints, "I have 42 cukes in box"),
        "extracted arguments")
    equal(null, StepArguments.mismatch(ints, "I have 42 cukes in box"), "no mismatch when matching")
    val mismatch = StepArguments.mismatch(ints, "I have abc cukes in box")
    equal("abc", mismatch?.value, "mismatch value")
    equal("{int}", mismatch?.type, "mismatch type")
    equal(
        "argument 'abc' is not a valid {int} for step definition 'I have {int} cukes in {word}'",
        mismatch?.message,
        "mismatch message")
    equal(
        null,
        StepArguments.mismatch(ints, "I own abc cukes in box"),
        "different text is no mismatch")
    val anything =
        StepDefinitionInfo.of(
            Language.JAVA,
            "given",
            PatternKind.CUCUMBER_EXPRESSION,
            "{int}",
            Patterns.cucumberExpression("{int}"),
            0)
    equal(
        null,
        StepArguments.bestMismatch(listOf(anything), "abc"),
        "parameter-only definitions ignored")
    equal(
        "abc",
        StepArguments.bestMismatch(listOf(anything, ints), "I have abc cukes in box")?.value,
        "best mismatch")
    val parse =
        StepDefinitionInfo.of(
            Language.PYTHON,
            "given",
            PatternKind.PARSE_FORMAT,
            "I wait {n:d} seconds",
            Patterns.parseFormat("I wait {n:d} seconds"),
            0)
    equal("{n:d}", StepArguments.mismatch(parse, "I wait x seconds")?.type, "parse format mismatch")
    equal(
        listOf(StepArguments.Argument("n", "{n:d}", "3")),
        StepArguments.extract(parse, "I wait 3 seconds"),
        "parse args")
    val escaped =
        StepArguments.relaxedRegex(
            StepDefinitionInfo.of(
                Language.JAVA,
                "given",
                PatternKind.CUCUMBER_EXPRESSION,
                "a \\{int} {float}",
                "",
                0))
    check(
        escaped != null && Pattern.compile(escaped).matcher("a {int} x").matches(),
        "escaped brace kept: $escaped")
  }

  // feature: stepdefs -------------------------------------------------------

  private fun stepdefsPlan(
      kind: PatternKind,
      pattern: String,
      oldText: String,
      newText: String,
  ): io.github.fangzhengjie.cucumber.core.StepRename.Plan? =
      io.github.fangzhengjie.cucumber.core.StepRename.plan(
          kind, pattern, Patterns.toRegex(kind, pattern), oldText, newText)

  private fun stepdefsRename() {
    val rename = io.github.fangzhengjie.cucumber.core.StepRename
    val ce = PatternKind.CUCUMBER_EXPRESSION
    val plan = stepdefsPlan(ce, "I have {int} cukes", "I have 5 cukes", "I own 5 cukes")
    equal("I own {int} cukes", plan?.newPattern, "rename: cucumber expression literal part")
    if (plan != null) {
      equal(
          "I own 12 cukes",
          rename.rewriteStep(
              plan, Patterns.cucumberExpression("I have {int} cukes"), "I have 12 cukes"),
          "rename: other usage keeps its parameter")
    }
    check(
        stepdefsPlan(ce, "I have {int} cukes", "I have 5 cukes", "I have 6 cukes") == null,
        "rename: changing a parameter value is refused")
    check(
        stepdefsPlan(ce, "I have {int} cuke(s)", "I have 5 cukes", "I own 5 cukes") == null,
        "rename: optional text is refused")
    equal(
        "say \\(hi\\) {string}",
        stepdefsPlan(ce, "say hi {string}", "say hi \"x\"", "say (hi) \"x\"")?.newPattern,
        "rename: cucumber expression escapes new special characters")
    equal(
        "the {string} user logs out",
        stepdefsPlan(
                ce,
                "the {string} user logs in",
                "the \"bob\" user logs in",
                "the \"bob\" user logs out")
            ?.newPattern,
        "rename: text after a parameter")
    equal(
        "^I own (\\d+) cukes\\.\$",
        stepdefsPlan(
                PatternKind.REGEX, "^I have (\\d+) cukes\\.\$", "I have 5 cukes.", "I own 5 cukes.")
            ?.newPattern,
        "rename: regex literal part keeps anchors and escapes")
    check(
        stepdefsPlan(
            PatternKind.REGEX, "^I have (.*) cukes.*\$", "I have 5 cukes", "I own 5 cukes") == null,
        "rename: regex with quantifiers is refused")
    equal(
        "I own {n:d} cukes {{x}}",
        stepdefsPlan(
                PatternKind.PARSE_FORMAT,
                "I have {n:d} cukes {{x}}",
                "I have 5 cukes {x}",
                "I own 5 cukes {x}")
            ?.newPattern,
        "rename: parse format")
    equal(
        "a [c]",
        stepdefsPlan(PatternKind.EXACT, "a (b)", "a (b)", "a [c]")?.newPattern,
        "rename: exact text")
    equal(
        "a plain step",
        rename.literalText(ce, "a plain step"),
        "rename: literal text of a plain pattern")
    check(rename.literalText(ce, "a {int} step") == null, "rename: no literal text with parameters")
  }

  private fun stepdefsRenameDefinition(
      language: Language,
      source: String,
      oldText: String,
      newText: String,
  ): String? {
    val definition = language.adapter().parse(source).firstOrNull() ?: return null
    val edit =
        io.github.fangzhengjie.cucumber.core.StepRename.renameDefinition(
            source, definition, oldText, newText) ?: return null
    return source.substring(0, edit.start) + edit.replacement + source.substring(edit.end)
  }

  private fun stepdefsRenameLiterals() {
    equal(
        "@Given(\"I own {int} cukes\")\npublic void a(int n) {}\n",
        stepdefsRenameDefinition(
            Language.JAVA,
            "@Given(\"I have {int} cukes\")\npublic void a(int n) {}\n",
            "I have 5 cukes",
            "I own 5 cukes"),
        "rename literal: Java")
    equal(
        "@Given(\"say \\\"hi\\\" {int}\")\nfun a(n: Int) {}\n",
        stepdefsRenameDefinition(
            Language.KOTLIN, "@Given(\"say {int}\")\nfun a(n: Int) {}\n", "say 1", "say \"hi\" 1"),
        "rename literal: Kotlin escapes quotes")
    equal(
        "@Given(\"pay \\\$ {int}\")\nfun a(n: Int) {}\n",
        stepdefsRenameDefinition(
            Language.KOTLIN, "@Given(\"pay {int}\")\nfun a(n: Int) {}\n", "pay 5", "pay $ 5"),
        "rename literal: Kotlin escapes \$")
    equal(
        "#[given(regex = r\"^I own (\\d+) cukes$\")]\nasync fn a(w: &mut W, n: i32) {}\n",
        stepdefsRenameDefinition(
            Language.RUST,
            "#[given(regex = r\"^I have (\\d+) cukes$\")]\nasync fn a(w: &mut W, n: i32) {}\n",
            "I have 5 cukes",
            "I own 5 cukes"),
        "rename literal: Rust raw string")
    equal(
        "from pytest_bdd import given, parsers\n@given(parsers.parse('I own {n:d} cukes'))\ndef a(n):\n    pass\n",
        stepdefsRenameDefinition(
            Language.PYTHON,
            "from pytest_bdd import given, parsers\n@given(parsers.parse('I have {n:d} cukes'))\ndef a(n):\n    pass\n",
            "I have 5 cukes",
            "I own 5 cukes"),
        "rename literal: Python pytest-bdd parse")
    equal(
        "Given(/^a\\/c (\\d+)$/, function (n) {});\n",
        stepdefsRenameDefinition(
            Language.JAVASCRIPT, "Given(/^a\\/b (\\d+)$/, function (n) {});\n", "a/b 5", "a/c 5"),
        "rename literal: JavaScript regex literal escapes /")
    equal(
        "Given('it\\'s {int}') do |n|\nend\n",
        stepdefsRenameDefinition(
            Language.RUBY, "Given('it is {int}') do |n|\nend\n", "it is 1", "it's 1"),
        "rename literal: Ruby single quotes")
    equal(
        "ctx.Step(`^I own (\\d+) cukes$`, f)\n",
        stepdefsRenameDefinition(
            Language.GO,
            "ctx.Step(`^I have (\\d+) cukes$`, f)\n",
            "I have 5 cukes",
            "I own 5 cukes"),
        "rename literal: Go raw string")
    equal(
        "[Given(@\"say \"\"hi\"\"\")]\n",
        stepdefsRenameDefinition(
            Language.CSHARP, "[Given(@\"say hello\")]\n", "say hello", "say \"hi\""),
        "rename literal: C# verbatim string")
    check(
        stepdefsRenameDefinition(
            Language.JAVA,
            "@Given(\"\"\"\n    I have {int} cukes\"\"\")\npublic void a(int n) {}\n",
            "I have 5 cukes",
            "I own 5 cukes") == null,
        "rename literal: Java text block is refused")
    check(
        stepdefsRenameDefinition(
            Language.JAVA,
            "@Given(\"I have {int} cukes\")\npublic void a(int n) {}\n",
            "I have 5 cukes",
            "I have 6 cukes") == null,
        "rename literal: parameter change is refused")
  }

  private fun stepdefsNewStepFiles() {
    val templates = io.github.fangzhengjie.cucumber.core.StepFileTemplates
    val texts = listOf("I have 5 cukes", "I have 6 cukes", "the result is \"ok\"")
    val items =
        texts.mapIndexed { i, text ->
          io.github.fangzhengjie.cucumber.core.StepFileTemplates.Item(
              text, Snippets.request(if (i == 2) "Then" else "Given", text, false, false))
        }
    val cases =
        listOf(
            Triple(
                Language.JAVA,
                "Steps.java",
                "package com.example.steps;\n\npublic class Steps {\n}\n"),
            Triple(Language.KOTLIN, "Steps.kt", "package com.example.steps\n\nclass Steps {\n}\n"),
            Triple(
                Language.JAVASCRIPT,
                "steps.js",
                "const { Given } = require('@cucumber/cucumber');\n"),
            Triple(
                Language.JAVASCRIPT, "steps.ts", "import { Given } from '@cucumber/cucumber';\n"),
            Triple(Language.PYTHON, "steps.py", "from behave import given\n"),
            Triple(Language.PYTHON, "test_steps.py", "from pytest_bdd import given\n"),
            Triple(Language.RUBY, "steps.rb", "Given('x') do\nend\n"))
    for ((language, referenceName, referenceText) in cases) {
      val what = "new step file ($language, $referenceName)"
      check(templates.supports(language), "$what: supported")
      val name = templates.defaultName(language)
      check(templates.isValidName(language, name), "$what: default name is valid")
      val template =
          templates.create(language, referenceName, referenceText, name, listOf("given", "then"))
      if (template == null) {
        check(false, "$what: template")
        continue
      }
      equal(
          referenceName.substringAfterLast('.'),
          template.fileName.substringAfterLast('.'),
          "$what: extension")
      val batch = templates.addSnippets(language, template.text, template.fileName, items)
      equal(2, batch.snippets.size, "$what: duplicate step skipped")
      equal(1, batch.skipped.size, "$what: skipped count")
      check(
          batch.text.startsWith(template.text.substringBefore("}")),
          "$what: template kept:\n${batch.text}")
      val definitions = language.adapter().parse(batch.text)
      for (text in texts) {
        equal(
            1,
            definitions.count { it.matches(text) },
            "$what: '$text' defined once in:\n${batch.text}")
      }
    }
    val java =
        templates.create(Language.JAVA, "Steps.java", "package a.b;\n", "MoreSteps", listOf("when"))
    check(
        java != null &&
            java.fileName == "MoreSteps.java" &&
            java.text.startsWith("package a.b;\n") &&
            java.text.contains("import io.cucumber.java.en.When;") &&
            java.text.contains("public class MoreSteps {"),
        "new step file: Java package, imports and class:\n${java?.text}")
    check(!templates.isValidName(Language.JAVA, "1Bad"), "new step file: invalid class name")
    check(!templates.supports(Language.GO), "new step file: Go falls back to existing files")
    check(
        templates.create(Language.GO, "steps.go", "", "new_steps", listOf("given")) == null,
        "new step file: no Go template")
  }

  // ---------------------------------------------------------------------------

  // feature: run ---------------------------------------------------------------

  private fun tagExpressions() {
    fun eval(expression: String, vararg tags: String): Boolean =
        TagExpressions.parse(expression).evaluate(tags.toList())
    check(eval("@a and not @b", "@a"), "tags: and not (match)")
    check(!eval("@a and not @b", "@a", "@b"), "tags: and not (excluded)")
    check(eval("@a or @b and @c", "@a"), "tags: and binds tighter (1)")
    check(!eval("@a or @b and @c", "@b"), "tags: and binds tighter (2)")
    check(eval("@a or @b and @c", "@b", "@c"), "tags: and binds tighter (3)")
    check(eval("not (@a or @b)"), "tags: not group (empty)")
    check(!eval("not (@a or @b)", "@b"), "tags: not group")
    check(eval("(@a or @b) and @c", "@a", "@c"), "tags: parentheses (match)")
    check(!eval("(@a or @b) and @c", "@a"), "tags: parentheses (no match)")
    check(eval("not not @a", "@a"), "tags: double not")
    check(eval("@a\\(1\\)", "@a(1)"), "tags: escaped parentheses")
    check(eval("  ", "@x"), "tags: blank matches everything")
    for (bad in listOf("@a and", "(@a", "@a @b", "and @a", "@a or )", "smoke")) {
      check(TagExpressions.parseOrNull(bad) == null, "tags: invalid '$bad'")
    }
    check(TagExpressions.looksLikeExpression("@smoke"), "tags: looks like (@smoke)")
    check(TagExpressions.looksLikeExpression("not @wip"), "tags: looks like (not)")
    check(TagExpressions.looksLikeExpression("(@a or @b) and not @c"), "tags: looks like (group)")
    check(!TagExpressions.looksLikeExpression("smoke test"), "tags: plain text")
    check(!TagExpressions.looksLikeExpression("@a and"), "tags: incomplete")
    equal(listOf("@a", "@b"), TagExpressions.tags("@a and not @b"), "tags: mentioned tags")
  }

  private fun featureOutline() {
    val text =
        """
        |@f
        |Feature: F
        |  Background:
        |    Given x
        |  @s1
        |  Scenario: One
        |    Given a
        |  Rule: R
        |    @s2
        |    Scenario Outline: Two <n>
        |      Given b <n>
        |      @e1
        |      Examples: First
        |        | n |
        |        | 1 |
        |      Examples:
        |        | n |
        |        | 2 |
        |"""
            .trimMargin()
    val outline = FeatureOutline.parse(text)
    equal(2, outline.featureLine, "outline: feature line")
    equal("F", outline.featureName, "outline: feature name")
    equal(setOf("@f"), outline.featureTags, "outline: feature tags")
    equal(2, outline.scenarios.size, "outline: scenarios")
    val one = outline.scenarios[0]
    val two = outline.scenarios[1]
    equal(6, one.line, "outline: scenario line")
    equal(8, one.endLine, "outline: scenario end")
    equal(setOf("@f", "@s1"), one.tags, "outline: effective tags")
    equal(10, two.line, "outline: outline line")
    equal("Two <n>", two.name, "outline: outline name")
    equal(listOf(15, 18), two.examples.map { it.line }, "outline: example rows")
    equal(setOf("@f", "@s2", "@e1"), two.examples[0].tags, "outline: examples tags")
    equal(setOf("@f", "@s2"), two.examples[1].tags, "outline: untagged examples")
    check(two.matches(TagExpressions.parse("@e1")), "outline: examples tag matches")
    check(!one.matches(TagExpressions.parse("@e1")), "outline: examples tag not inherited")
    check(one.matches(TagExpressions.parse("@f and not @s2")), "outline: feature tag inherited")
    equal(
        FeatureOutline.Target(0, "F", true),
        FeatureOutline.targetAt(outline, 4),
        "outline: background -> feature")
    equal(
        FeatureOutline.Target(0, "F", true),
        FeatureOutline.targetAt(outline, 2),
        "outline: feature line")
    equal(
        FeatureOutline.Target(6, "One", false),
        FeatureOutline.targetAt(outline, 7),
        "outline: step -> scenario")
    equal(
        FeatureOutline.Target(10, "Two <n>", false),
        FeatureOutline.targetAt(outline, 14),
        "outline: header -> outline")
    equal(
        FeatureOutline.Target(15, "Two <n> (example 1)", false),
        FeatureOutline.targetAt(outline, 15),
        "outline: example row")
  }

  /**
   * A hand-written Cucumber Messages stream (background, failure, skip, outline, undefined, hook).
   */
  private val SAMPLE_MESSAGES =
      """
      {"meta":{"protocolVersion":"24.0.0","implementation":{"name":"hand-written"}}}
      {"source":{"uri":"features/shop.feature","data":"...","mediaType":"text/x.cucumber.gherkin+plain"}}
      {"gherkinDocument":{"uri":"features/shop.feature","feature":{"location":{"line":2,"column":1},"tags":[{"location":{"line":1,"column":1},"name":"@shop","id":"t0"}],"language":"en","keyword":"Feature","name":"Shop","description":"","children":[{"background":{"id":"bg","location":{"line":4,"column":3},"keyword":"Background","name":"","steps":[{"id":"b1","location":{"line":5,"column":5},"keyword":"Given ","keywordType":"Context","text":"a shop"}]}},{"scenario":{"id":"s1","tags":[],"location":{"line":7,"column":3},"keyword":"Scenario","name":"Buy","steps":[{"id":"st1","location":{"line":8,"column":5},"keyword":"When ","text":"I buy 2"},{"id":"st2","location":{"line":9,"column":5},"keyword":"Then ","text":"I have 2"}],"examples":[]}},{"rule":{"id":"r","location":{"line":10,"column":3},"keyword":"Rule","name":"Selling","children":[{"scenario":{"id":"s2","tags":[],"location":{"line":11,"column":5},"keyword":"Scenario Outline","name":"Sell <n>","steps":[{"id":"st3","location":{"line":12,"column":7},"keyword":"When ","text":"I sell <n>"}],"examples":[{"id":"ex","tags":[],"location":{"line":14,"column":7},"keyword":"Examples","name":"","tableHeader":{"id":"h","location":{"line":15,"column":9},"cells":[{"location":{"line":15,"column":11},"value":"n"}]},"tableBody":[{"id":"r1","location":{"line":16,"column":9},"cells":[{"location":{"line":16,"column":11},"value":"1"}]},{"id":"r2","location":{"line":17,"column":9},"cells":[{"location":{"line":17,"column":11},"value":"5"}]}]}]}}]}}]}}}
      {"pickle":{"id":"p1","uri":"features/shop.feature","name":"Buy","language":"en","astNodeIds":["s1"],"tags":[{"name":"@shop","astNodeId":"t0"}],"steps":[{"id":"ps1","text":"a shop","type":"Context","astNodeIds":["b1"]},{"id":"ps2","text":"I buy 2","type":"Action","astNodeIds":["st1"]},{"id":"ps3","text":"I have 2","type":"Outcome","astNodeIds":["st2"]}]}}
      {"pickle":{"id":"p2","uri":"features/shop.feature","name":"Sell 5","language":"en","astNodeIds":["s2","r2"],"tags":[],"steps":[{"id":"ps4","text":"a shop","astNodeIds":["b1"]},{"id":"ps5","text":"I sell 5","astNodeIds":["st3","r2"]}]}}
      {"stepDefinition":{"id":"sd1","pattern":{"source":"a shop","type":"CUCUMBER_EXPRESSION"},"sourceReference":{"uri":"steps.js"}}}
      {"testRunStarted":{"timestamp":{"seconds":1,"nanos":0}}}
      {"testCase":{"id":"tc1","pickleId":"p1","testSteps":[{"id":"h1","hookId":"hook"},{"id":"t1","pickleStepId":"ps1","stepDefinitionIds":["sd1"]},{"id":"t2","pickleStepId":"ps2","stepDefinitionIds":[]},{"id":"t3","pickleStepId":"ps3","stepDefinitionIds":[]}]}}
      {"testCase":{"id":"tc2","pickleId":"p2","testSteps":[{"id":"t4","pickleStepId":"ps4"},{"id":"t5","pickleStepId":"ps5"},{"id":"h2","hookId":"hook2"}]}}
      {"testCaseStarted":{"id":"c1","testCaseId":"tc1","attempt":0,"timestamp":{"seconds":1,"nanos":0}}}
      {"testStepStarted":{"testCaseStartedId":"c1","testStepId":"h1","timestamp":{"seconds":1,"nanos":0}}}
      {"testStepFinished":{"testCaseStartedId":"c1","testStepId":"h1","testStepResult":{"status":"PASSED","duration":{"seconds":0,"nanos":1000}},"timestamp":{"seconds":1,"nanos":0}}}
      {"testStepStarted":{"testCaseStartedId":"c1","testStepId":"t1","timestamp":{"seconds":1,"nanos":0}}}
      {"testStepFinished":{"testCaseStartedId":"c1","testStepId":"t1","testStepResult":{"status":"PASSED","duration":{"seconds":1,"nanos":2000000}},"timestamp":{"seconds":1,"nanos":0}}}
      {"testStepStarted":{"testCaseStartedId":"c1","testStepId":"t2","timestamp":{"seconds":1,"nanos":0}}}
      {"testStepFinished":{"testCaseStartedId":"c1","testStepId":"t2","testStepResult":{"status":"FAILED","message":"boom","exception":{"type":"java.lang.AssertionError","message":"boom","stackTrace":"java.lang.AssertionError: boom\n\tat Steps.buy(Steps.java:9)\n"},"duration":{"seconds":0,"nanos":0}},"timestamp":{"seconds":1,"nanos":0}}}
      {"testStepStarted":{"testCaseStartedId":"c1","testStepId":"t3","timestamp":{"seconds":1,"nanos":0}}}
      {"testStepFinished":{"testCaseStartedId":"c1","testStepId":"t3","testStepResult":{"status":"SKIPPED","duration":{"seconds":0,"nanos":0}},"timestamp":{"seconds":1,"nanos":0}}}
      {"testCaseFinished":{"testCaseStartedId":"c1","timestamp":{"seconds":1,"nanos":0},"willBeRetried":false}}
      {"testCaseStarted":{"id":"c2","testCaseId":"tc2","attempt":0,"timestamp":{"seconds":2,"nanos":0}}}
      {"testStepStarted":{"testCaseStartedId":"c2","testStepId":"t4","timestamp":{"seconds":2,"nanos":0}}}
      {"testStepFinished":{"testCaseStartedId":"c2","testStepId":"t4","testStepResult":{"status":"PASSED","duration":{"seconds":0,"nanos":0}},"timestamp":{"seconds":2,"nanos":0}}}
      {"testStepStarted":{"testCaseStartedId":"c2","testStepId":"t5","timestamp":{"seconds":2,"nanos":0}}}
      {"testStepFinished":{"testCaseStartedId":"c2","testStepId":"t5","testStepResult":{"status":"UNDEFINED","duration":{"seconds":0,"nanos":0}},"timestamp":{"seconds":2,"nanos":0}}}
      {"testStepStarted":{"testCaseStartedId":"c2","testStepId":"h2","timestamp":{"seconds":2,"nanos":0}}}
      {"testStepFinished":{"testCaseStartedId":"c2","testStepId":"h2","testStepResult":{"status":"FAILED","message":"cleanup failed\nat hook","duration":{"seconds":0,"nanos":0}},"timestamp":{"seconds":2,"nanos":0}}}
      {"testCaseFinished":{"testCaseStartedId":"c2","timestamp":{"seconds":2,"nanos":0},"willBeRetried":false}}
      {"testRunFinished":{"success":false,"timestamp":{"seconds":3,"nanos":0}}}
      """
          .trimIndent()

  /** A compact, comparable rendering of converter events. */
  private fun describe(event: CucumberMessages.Event): String =
      when (event) {
        is CucumberMessages.SuiteStarted -> "suite+ ${event.name} @${event.location}"
        is CucumberMessages.SuiteFinished -> "suite- ${event.name}"
        is CucumberMessages.TestStarted -> "test+ ${event.name} @${event.location}"
        is CucumberMessages.TestFailed -> "fail ${event.name}: ${event.message}"
        is CucumberMessages.TestIgnored -> "ignore ${event.name}"
        is CucumberMessages.TestFinished -> "test- ${event.name} ${event.durationMs}"
      }

  /** Converts an ndjson file (e.g. from a real Cucumber run) and prints the events. */
  @JvmStatic
  fun convertMessages(file: Path, baseDir: String): List<String> {
    val converter = CucumberMessages.Converter(baseDir)
    val events = ArrayList<CucumberMessages.Event>()
    for (line in Files.readAllLines(file, StandardCharsets.UTF_8)) {
      events.addAll(converter.accept(line))
    }
    events.addAll(converter.finish())
    return events.map { describe(it) }
  }

  private fun cucumberMessages() {
    val json = CucumberMessages.Json.parse("""{"a":[1,2.5,true,null,"x\u0041\n"],"b":{}}""")
    equal(
        mapOf("a" to listOf(1L, 2.5, true, null, "xA\n"), "b" to mapOf<String, Any?>()),
        json,
        "json: values")
    check(
        try {
          CucumberMessages.Json.parse("{\"a\":}")
          false
        } catch (e: IllegalArgumentException) {
          true
        },
        "json: invalid input is rejected")
    equal(
        "a|'b||c|n|[d|]",
        CucumberMessages.escape("a'b|c\n[d]"),
        "messages: service message escaping")
    equal(
        "C:/x/a.feature" to 12,
        CucumberMessages.parseLocation("C:/x/a.feature:12"),
        "messages: location")

    val converter = CucumberMessages.Converter("/work")
    val events = ArrayList<CucumberMessages.Event>()
    for (line in SAMPLE_MESSAGES.lines()) {
      events.addAll(converter.accept(line))
    }
    events.addAll(converter.finish())
    val at = "cucumber:///work/features/shop.feature:"
    val expected =
        listOf(
            "suite+ Shop @${at}2",
            "suite+ Buy @${at}7",
            "test+ Given a shop @${at}5",
            "test- Given a shop 1002",
            "test+ When I buy 2 @${at}8",
            "fail When I buy 2: boom",
            "test- When I buy 2 0",
            "test+ Then I have 2 @${at}9",
            "ignore Then I have 2",
            "test- Then I have 2 0",
            "suite- Buy",
            "suite+ Sell 5 (example 2) @${at}17",
            "test+ Given a shop @${at}5",
            "test- Given a shop 0",
            "test+ When I sell 5 @${at}12",
            "fail When I sell 5: Undefined step: When I sell 5",
            "test- When I sell 5 0",
            "test+ After hook @${at}17",
            "fail After hook: cleanup failed",
            "test- After hook 0",
            "suite- Sell 5 (example 2)",
            "suite- Shop",
        )
    equal(
        expected.joinToString("\n"),
        events.joinToString("\n") { describe(it) },
        "messages: event sequence")
    val failure = events.filterIsInstance<CucumberMessages.TestFailed>().first()
    equal(
        "java.lang.AssertionError: boom\n\tat Steps.buy(Steps.java:9)",
        failure.details,
        "messages: stack trace")
    val buy = events.filterIsInstance<CucumberMessages.SuiteStarted>()[1]
    val step = events.filterIsInstance<CucumberMessages.TestStarted>()[0]
    equal(events[0].id, buy.parentId, "messages: scenario under feature")
    equal(buy.id, step.parentId, "messages: step under scenario")
    equal(
        "0", (events[0] as CucumberMessages.SuiteStarted).parentId, "messages: feature under root")
    check(
        failure.serviceMessage().startsWith("##teamcity[testFailed name='When I buy 2' nodeId='") &&
            failure
                .serviceMessage()
                .contains("details='java.lang.AssertionError: boom|n\tat Steps.buy(Steps.java:9)'"),
        "messages: testFailed service message: " + failure.serviceMessage())
    check(converter.hasEvents(), "messages: has events")
    equal(
        "/work/features/a.feature",
        CucumberMessages.Converter("/work").path("file:///work/features/a.feature"),
        "messages: file uri")
    equal(
        "C:/w/a.feature",
        CucumberMessages.Converter("/x").path("file:///C:/w/a.feature"),
        "messages: windows file uri")
    equal(
        "C:/w/a.feature",
        CucumberMessages.Converter("/x").path("C:\\w\\a.feature"),
        "messages: windows path")
    equal(
        "classpath:no/such.feature",
        CucumberMessages.Converter("/x").path("classpath:no/such.feature"),
        "messages: classpath")
    equal(
        0, CucumberMessages.Converter("/x").accept("not json {").size, "messages: garbage ignored")
  }

  private fun runCommands() {
    val root = Path.of("build", "selftest-run").toAbsolutePath()
    fun write(path: Path, text: String) {
      Files.createDirectories(path.parent)
      Files.writeString(path, text, StandardCharsets.UTF_8)
    }
    fun slash(path: Path): String = path.toString().replace('\\', '/')

    // Maven + JUnit Platform runner: several scenarios, tags, message plugin and debugger.
    val maven = root.resolve("maven")
    write(maven.resolve("pom.xml"), "<project/>")
    write(
        maven.resolve("src/test/java/RunCucumberTest.java"),
        "@Suite\n@IncludeEngines(\"cucumber\")\npublic class RunCucumberTest {}\n")
    val a = maven.resolve("src/test/resources/f/a.feature")
    val b = maven.resolve("src/test/resources/f/b.feature")
    write(a, "Feature: A\n")
    write(b, "Feature: B\n")
    val options =
        JvmCommand.Options(
            tags = "@a and not @b",
            plugin = "message:/x.ndjson",
            debugAddress = "127.0.0.1:5005",
            gradleInitScript = "/i.gradle")
    val mvn =
        JvmCommand.build(
            maven.toString(), listOf(slash(a) + ":3", slash(b) + ":7:9"), options, false)
    equal(
        listOf(
            "mvn",
            "test",
            "-Dtest=RunCucumberTest",
            "-Dsurefire.includeJUnit5Engines=cucumber",
            "-Dsurefire.failIfNoSpecifiedTests=false",
            "-Dcucumber.filter.tags=@a and not @b",
            "-Dcucumber.plugin=message:/x.ndjson",
            "-Dmaven.surefire.debug=-agentlib:jdwp=transport=dt_socket,server=n,suspend=y,address=127.0.0.1:5005",
            "-Dcucumber.features=src/test/resources/f/a.feature:3,src/test/resources/f/b.feature:7:9"),
        mvn.argv,
        "jvm: maven argv")
    equal(
        mapOf(
            "CUCUMBER_FEATURES" to
                "src/test/resources/f/a.feature:3,src/test/resources/f/b.feature:7:9"),
        mvn.env,
        "jvm: maven env")
    equal(
        JvmCommand.build(maven.toString(), a.toString(), 3, false),
        JvmCommand.build(maven.toString(), listOf("$a:3"), JvmCommand.Options(), false),
        "jvm: single-feature API unchanged")
    equal(
        "-Dcucumber.features=classpath:f/a.feature:3",
        JvmCommand.build(
                maven.toString(), listOf("classpath:f/a.feature:3"), JvmCommand.Options(), false)
            .argv
            .last(),
        "jvm: classpath feature")

    // Gradle + JUnit 4 runner: settings travel as environment variables + the init script.
    val gradle = root.resolve("gradle")
    write(gradle.resolve("settings.gradle"), "")
    write(gradle.resolve("build.gradle"), "")
    write(
        gradle.resolve("src/test/java/RunCukesTest.java"),
        "@RunWith(Cucumber.class)\npublic class RunCukesTest {}\n")
    val g = gradle.resolve("src/test/resources/f/a.feature")
    write(g, "Feature: G\n")
    val gradleCommand = JvmCommand.build(gradle.toString(), listOf(slash(g) + ":3"), options, false)
    equal(
        listOf(
            "gradle",
            "--init-script",
            "/i.gradle",
            "test",
            "--tests",
            "RunCukesTest",
            "-Dcucumber.filter.tags=@a and not @b",
            "-Dcucumber.plugin=message:/x.ndjson",
            "-Dcucumber.jetbrains.jdwp=127.0.0.1:5005",
            "-Dcucumber.features=src/test/resources/f/a.feature:3"),
        gradleCommand.argv,
        "jvm: gradle argv")
    equal(
        mapOf(
            "CUCUMBER_FEATURES" to "src/test/resources/f/a.feature:3",
            "CUCUMBER_FILTER_TAGS" to "@a and not @b",
            "CUCUMBER_PLUGIN" to "message:/x.ndjson",
            "CUCUMBER_JDWP_ADDRESS" to "127.0.0.1:5005"),
        gradleCommand.env,
        "jvm: gradle env")
    for (needle in
        listOf(
            "CUCUMBER_FILTER_TAGS",
            "cucumber.plugin",
            "systemProperty",
            "SATISFIES_NONE",
            "server=n")) {
      check(JvmCommand.GRADLE_INIT_SCRIPT.contains(needle), "jvm: init script mentions $needle")
    }
    val platformGradle = root.resolve("gradle-platform")
    write(platformGradle.resolve("build.gradle.kts"), "")
    write(
        platformGradle.resolve("src/test/kotlin/RunTest.kt"),
        "@Suite\n@IncludeEngines(\"cucumber\")\nclass RunTest\n")
    val pf = slash(platformGradle.resolve("src/test/resources/p.feature"))
    equal(
        "true",
        JvmCommand.build(platformGradle.toString(), listOf(pf), options, false)
            .env["CUCUMBER_JETBRAINS_ENGINE_ONLY"],
        "jvm: gradle + JUnit Platform runner -> Cucumber engine only")
    check(
        !JvmCommand.build(platformGradle.toString(), listOf(), options, false)
            .env
            .containsKey("CUCUMBER_JETBRAINS_ENGINE_ONLY"),
        "jvm: gradle, all features -> runner as usual")
    equal(
        "C:/a/b.feature" to ":3:9", JvmCommand.splitLines("C:/a/b.feature:3:9"), "jvm: split lines")
    equal("C:/a/b.feature" to "", JvmCommand.splitLines("C:/a/b.feature"), "jvm: no lines")

    // Other runners: tags and the message formatter.
    val js = root.resolve("js")
    write(js.resolve("package.json"), "{}")
    val jsFeature = slash(js.resolve("features/x.feature")) + ":4"
    equal(
        Language.JAVASCRIPT,
        CucumberCommands.detectLanguage(js.toString(), jsFeature),
        "commands: detect js")
    equal(
        Language.JAVA,
        CucumberCommands.detectLanguage(maven.toString(), slash(a)),
        "commands: detect maven")
    equal(
        listOf(
            "npx",
            "cucumber-js",
            "features/x.feature:4",
            "--tags",
            "@a",
            "--format",
            "message:/m.ndjson"),
        CucumberCommands.build(
                CucumberCommands.Request(
                    js.toString(),
                    null,
                    listOf(jsFeature),
                    "@a",
                    messagesFile = "/m.ndjson",
                    windows = false))
            .argv,
        "commands: cucumber-js")
    val ruby = root.resolve("ruby")
    write(ruby.resolve("Gemfile"), "")
    equal(
        listOf(
            "bundle",
            "exec",
            "cucumber",
            "features/x.feature:4",
            "--tags",
            "@a",
            "--format",
            "pretty",
            "--format",
            "message",
            "--out",
            "/m.ndjson",
            "--strict"),
        CucumberCommands.build(
                CucumberCommands.Request(
                    ruby.toString(),
                    Language.RUBY,
                    listOf(slash(ruby.resolve("features/x.feature")) + ":4"),
                    "@a",
                    listOf("--strict"),
                    "/m.ndjson",
                    windows = false))
            .argv,
        "commands: cucumber-ruby")
    equal(
        listOf("python", "-m", "behave", "features/x.feature:4", "--tags=@a"),
        CucumberCommands.build(
                CucumberCommands.Request(
                    ruby.toString(),
                    Language.PYTHON,
                    listOf(slash(ruby.resolve("features/x.feature")) + ":4"),
                    "@a",
                    windows = false))
            .argv,
        "commands: behave")
    check(CucumberCommands.supportsMessages(Language.KOTLIN), "commands: jvm messages")
    check(!CucumberCommands.supportsMessages(Language.PYTHON), "commands: behave has no messages")
  }

  // --- localized keywords across outline / formatter / run targets -------------------------------

  private fun localizedStructure() {
    val zhDialect = GherkinDialects.forCode("zh-CN")!!
    equal(
        GherkinKeywordType.SCENARIO,
        GherkinSyntax.header("场景：x", zhDialect)?.type,
        "syntax: zh header")
    equal("x", GherkinSyntax.header("场景：x", zhDialect)?.title, "syntax: zh title")
    equal("abc", GherkinSyntax.step("假如abc", zhDialect)?.text, "syntax: zh step without space")
    check(GherkinSyntax.step("Given x", zhDialect) == null, "syntax: English step in zh file")
    equal(
        "Given",
        GherkinSyntax.step("Given x", GherkinDialects.ENGLISH)?.keyword,
        "syntax: English step")
    equal("and", GherkinSyntax.canonicalStepKeyword("而且"), "syntax: canonical zh keyword")
    equal(
        "de",
        GherkinSyntax.dialect(listOf("", "# language: de", "Funktionalität: x")).code,
        "syntax: dialect of lines")

    val zh =
        listOf(
                "# language: zh-CN",
                "@f",
                "功能：购物",
                "  @s",
                "  场景大纲: 买 <n> 个",
                "    假如我有<n>个",
                "    那么剩下<left>个",
                "    @ex",
                "    例子：",
                "      | n | left |",
                "      | 1 | 2    |",
                "",
                "  场景: 普通",
                "    当我等待",
                "")
            .joinToString("\n")
    val zhMessy =
        zh.lines().joinToString("\n") { line ->
          if (line.startsWith("#") || line.isBlank()) line
          else " " + line.trim().replace("| 2    |", "|2|") + "  "
        }
    equal(zh, GherkinFormatter.format(zhMessy), "format zh-CN")
    equal(zh, GherkinFormatter.format(zh), "format zh-CN idempotent")

    val zhScenarios = Outlines.scenarios(zh)
    equal(2, zhScenarios.size, "zh outlines: scenarios")
    check(
        zhScenarios[0].isOutline && zhScenarios[0].outlineKeyword,
        "zh outlines: 场景大纲 is an outline")
    equal(listOf("n", "n", "left"), zhScenarios[0].params.map { it.name }, "zh outlines: params")
    equal(
        listOf("n", "left"),
        zhScenarios[0].examples.single().header.map { it.text },
        "zh outlines: header")
    equal("我有1个", zhScenarios[0].substitute("我有<n>个"), "zh outlines: substitution")
    check(!zhScenarios[1].isOutline, "zh outlines: 场景 is plain")
    equal(
        listOf<String>(),
        Outlines.problems(zhScenarios).map { it.message },
        "zh outlines: no problems")

    val zhOutline = FeatureOutline.parse(zh)
    equal("购物", zhOutline.featureName, "zh feature outline: name")
    equal(setOf("@f"), zhOutline.featureTags, "zh feature outline: feature tags")
    equal(
        listOf("场景大纲", "场景"),
        zhOutline.scenarios.map { it.keyword },
        "zh feature outline: keywords")
    equal(setOf("@f", "@s"), zhOutline.scenarios[0].tags, "zh feature outline: scenario tags")
    equal(
        listOf(FeatureOutline.ExampleRow(11, 1, setOf("@f", "@s", "@ex"))),
        zhOutline.scenarios[0].examples,
        "zh feature outline: example rows")
    equal(
        FeatureOutline.Target(11, "买 <n> 个 (example 1)", false),
        FeatureOutline.targetAt(zhOutline, 11),
        "zh feature outline: example target")
    equal(
        FeatureOutline.Target(13, "普通", false),
        FeatureOutline.targetAt(zhOutline, 14),
        "zh feature outline: step target")

    val zhTree = GherkinOutline.parse(zh)
    equal(
        "[功能: 购物 [场景大纲: 买 <n> 个 [假如: 我有<n>个, 那么: 剩下<left>个, 例子], 场景: 普通 [当: 我等待]]]",
        zhTree.roots.map { f -> "$f " + f.children.map { s -> "$s " + s.children } }.toString(),
        "zh structure")

    val de =
        listOf(
                "# language: de",
                "Funktionalität: Einkaufen",
                "  Szenariogrundriss: kaufe <n>",
                "    Angenommen ich habe <n> Gurken",
                "    Und ich esse <m>",
                "",
                "    Beispiele:",
                "      | n | m |",
                "      | 3 | 1 |",
                "")
            .joinToString("\n")
    equal(de, GherkinFormatter.format(de.lines().joinToString("\n") { it.trim() }), "format de")
    val deScenarios = Outlines.scenarios(de)
    check(deScenarios.single().outlineKeyword, "de outlines: Szenariogrundriss")
    equal(listOf("n", "n", "m"), deScenarios.single().params.map { it.name }, "de outlines: params")
    equal(
        listOf("n", "m"),
        deScenarios.single().examples.single().header.map { it.text },
        "de outlines: Beispiele")
    equal(
        listOf<String>(),
        Outlines.problems(deScenarios).map { it.message },
        "de outlines: no problems")
    equal(
        FeatureOutline.Target(9, "kaufe <n> (example 1)", false),
        FeatureOutline.targetAt(FeatureOutline.parse(de), 9),
        "de feature outline: example target")
    equal(
        "[FEATURE, SCENARIO_OUTLINE, STEP, STEP, EXAMPLES]",
        GherkinOutline.parse(de).all().map { it.kind }.toString(),
        "de structure")
  }

  private fun ctrlHoverLiteralRanges() {
    val ranges = io.github.fangzhengjie.cucumber.core.LiteralRanges
    val go = "package steps\n\nfunc init() {\n\tctx.Step(`^I have (\\d+) cukes$`, haveCukes)\n}\n"
    val start = go.indexOf('`')
    val literal = io.github.fangzhengjie.cucumber.core.StepRename.literalAt(Language.GO, go, start)
    check(
        literal != null && go.substring(literal.start, literal.end) == "`^I have (\\d+) cukes$`",
        "ctrlhover: Go literal range")
    check(
        literal != null && ranges.leafIsCoarser(0, go.length, literal.start, literal.end),
        "ctrlhover: plain-text leaf is coarser than the literal")
    check(!ranges.leafIsCoarser(10, 30, 10, 30), "ctrlhover: string token equal to the literal")
    check(
        !ranges.leafIsCoarser(11, 29, 10, 30), "ctrlhover: token inside the literal (Kotlin entry)")
    check(!ranges.leafIsCoarser(0, 100, 90, 110), "ctrlhover: literal not inside the leaf")
    check(!ranges.leafIsCoarser(0, 100, 5, 5), "ctrlhover: empty literal")
  }

  // feature: scope
  /** StepWords must cover every text an adapter's pre-check accepts (word index pre-filter). */
  private fun stepWordsCoverAdapters(args: Array<String>) {
    val samples = ArrayList<Pair<Language, String>>()
    val prefixes =
        listOf(
            "",
            " ",
            "\n",
            "@",
            ".",
            "[",
            ", ",
            "#[",
            "this.",
            "@io.cucumber.java.en.",
            "x.",
            "(",
            "{",
            "\t@ ")
    val suffixes = listOf("(", " (", "<World>(", "Attribute(", "(value = ", " '", " \"x\"", "!(")
    val names = StepWords.of(Language.entries) + listOf("GIVEN", "given", "Step", "StepDefinition")
    for (language in Language.entries) {
      for (name in names) {
        for (prefix in prefixes) {
          for (suffix in suffixes) {
            samples.add(language to "$prefix$name$suffix\"a step\")\n")
          }
        }
      }
    }
    val fixture = args.firstOrNull()?.let { Path.of(it) }
    if (fixture != null && Files.isDirectory(fixture)) {
      Files.walk(fixture).use { files ->
        for (file in files.filter { Files.isRegularFile(it) }.toList()) {
          val language = Language.forFileName(file.fileName.toString()) ?: continue
          samples.add(language to Files.readString(file, StandardCharsets.UTF_8))
        }
      }
    }
    var accepted = 0
    val missed = ArrayList<String>()
    for ((language, text) in samples) {
      if (language.adapter().mayContainSteps(text)) {
        accepted++
        if (!StepWords.mayContainSteps(language, text)) {
          missed.add("$language: ${text.take(80)}")
        }
      }
    }
    check(missed.isEmpty(), "StepWords misses texts the adapters accept: ${missed.take(5)}")
    check(accepted > 500, "StepWords: too few accepted samples ($accepted)")
    val call = Regex("""(?<![\w.@${'$'}])(Given|When|Then|And|But)\s*\(\s*""")
    val callWords = listOf("Given", "When", "Then", "And", "But")
    val tricky =
        listOf(
            "\$Given(",
            "_When (",
            "x.Then(",
            "@And(",
            "Given\n\t(",
            "But  x(",
            "Given",
            "(When\u000B(",
            "éThen(",
            "AndAnd(")
    val mismatches =
        (samples.map { it.second } + tricky).filter {
          call.containsMatchIn(it) != StepWords.containsCall(it, callWords, ".@$")
        }
    check(
        mismatches.isEmpty(),
        "StepWords.containsCall differs from the regex: ${mismatches.take(5)}")
    equal(
        setOf("Given", "x", "\$y", "GivenAttribute"),
        StepWords.tokens("@Given(x) \$y [GivenAttribute("),
        "StepWords.tokens")
    check(
        !StepWords.mayContainSteps(Language.JAVA, "class Givens { void given() {} }"),
        "StepWords: whole words only")
  }

  // --- feature: results -----------------------------------------------------

  private fun runResults() {
    val collector = RunResults.Collector(CucumberMessages.Converter("/work")::path)
    for (line in SAMPLE_MESSAGES.lines()) {
      collector.accept(line)
    }
    val path = "/work/features/shop.feature"
    equal(setOf(path), collector.takeChanged(), "results: changed files")
    equal(emptySet<String>(), collector.takeChanged(), "results: changes taken")
    val lines = collector.results()[path].orEmpty()
    fun at(line: Int): String = lines[line]?.let { "${it.kind} ${it.describe()}" } ?: "none"
    equal("FEATURE Failed (2 failed of 2 scenarios): When I buy 2: boom", at(2), "results: feature")
    equal("STEP Passed (2 passed of 2 runs)", at(5), "results: background step")
    equal("SCENARIO Failed: When I buy 2: boom", at(7), "results: scenario")
    equal("STEP Failed: boom", at(8), "results: failed step")
    equal("STEP Skipped", at(9), "results: skipped step")
    equal("OUTLINE Failed: After hook: cleanup failed", at(11), "results: outline")
    equal("STEP Undefined", at(12), "results: undefined step")
    equal("none", at(16), "results: example row not run")
    equal("EXAMPLE Failed: After hook: cleanup failed", at(17), "results: example row")
    val scenario = lines.getValue(7)
    check(scenario.matches("  Scenario:   Buy  "), "results: line unchanged")
    check(!scenario.matches("  Scenario: Buy more"), "results: line changed")
    check(lines.getValue(17).matches("        | 5 |"), "results: example row signature")
    check(lines.getValue(8).matches("    When I buy 2"), "results: step signature")
    equal(RunResults.Status.FAILED, RunResults.Status.of("failed"), "results: status")
    equal(RunResults.Status.SKIPPED, RunResults.Status.of("UNKNOWN"), "results: unknown status")
    equal("C:/x/a.feature", RunResults.normalizePath("c:\\x\\a.feature"), "results: drive letter")
    equal("/x/a.feature", RunResults.normalizePath("/x/a.feature"), "results: unix path")
    equal("expected 5", RunResults.headline("\n expected 5 \n\tat x"), "results: headline")

    // A retried attempt only counts once it is final; a second stream (another runner) adds to it.
    val retried = RunResults.Collector(CucumberMessages.Converter("/work")::path)
    val firstAttempt =
        SAMPLE_MESSAGES.lines()
            .takeWhile { !it.contains("\"testCaseStarted\":{\"id\":\"c2\"") }
            .map {
              if (it.contains("\"testCaseFinished\""))
                  it.replace("\"willBeRetried\":false", "\"willBeRetried\":true")
              else it
            }
    for (line in firstAttempt) {
      retried.accept(line)
    }
    equal(emptyMap<String, Any>(), retried.results(), "results: retried attempt ignored")
    retried.restart()
    for (line in SAMPLE_MESSAGES.lines()) {
      retried.accept(line)
    }
    equal(
        "Failed: When I buy 2: boom",
        retried.results()[path]?.get(7)?.describe(),
        "results: after restart")
  }

  // --- feature: unused -------------------------------------------------------------

  /** Whole step definition blocks for every fixture definition, and deleting them. */
  private fun stepDefinitionBlocks(args: Array<String>) {
    val blocks = io.github.fangzhengjie.cucumber.core.StepDefinitionBlocks
    val fixture =
        Path.of(if (args.isNotEmpty()) args[0] else "src/test/resources/testdata/multi-language")
            .resolve("steps")
    // 1-based line ranges of each definition's block, in source order.
    val expectedLines =
        mapOf(
            "behave_steps.py" to "3-5 7-9 11-13 15-17 19-21 23-25 29-31 33-35",
            "js_steps.js" to "3 4 5 6",
            "steps.cpp" to "3-4",
            "Steps.cs" to "8-9 11-12 14-15 17-18 20-21 23-24 26-27",
            "steps.go" to "6 7 8 9",
            "steps.groovy" to "3 4 5",
            "Steps.java" to "8-9 11-12 14-15 17-18",
            "Steps.kt" to "6-7 10 11",
            "steps.rb" to "1-2 4-5 7-8 10-11",
            "steps.rs" to "6-7 9-10 12-13 15-16 18-19 21-22 24-25",
            "steps.scala" to "4",
            "steps.ts" to "3 4 5",
            "test_bdd.py" to "3-5 7-9 11-13 15-17")
    val seen = ArrayList<String>()
    val texts = HashMap<String, String>()
    Files.list(fixture)
        .use { stream -> stream.sorted().toList() }
        .forEach { file ->
          val name = file.fileName.toString()
          val language = Language.forFileName(name) ?: return@forEach
          seen.add(name)
          val text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n")
          texts[name] = text
          val lines = text.split("\n")
          val definitions = language.adapter().parse(text)
          val ranges = expectedLines[name]?.split(" ") ?: emptyList()
          equal(ranges.size, definitions.size, "blocks $name: definitions")
          val all = ArrayList<io.github.fangzhengjie.cucumber.core.StepDefinitionBlocks.Block>()
          for ((i, definition) in definitions.withIndex()) {
            val block = blocks.find(language, text, definition.offset)
            val range = ranges.getOrNull(i)?.split("-")?.map { it.toInt() } ?: continue
            val expected =
                lines.subList(range.first() - 1, range.last()).joinToString("\n").trimStart()
            equal(expected, block?.text(text), "blocks $name #$i")
            if (block == null) {
              continue
            }
            all.add(block)
            val deleted = blocks.delete(text, listOf(block))
            equal(
                definitions.filterIndexed { j, _ -> j != i }.map { it.pattern },
                language.adapter().parse(deleted).map { it.pattern },
                "blocks $name #$i: the other definitions remain")
            equal(
                block.text(text).trim(),
                text.substring(block.deleteStart, block.deleteEnd).trim(),
                "blocks $name #$i: only whitespace deleted besides the block")
            check(!deleted.contains("\n\n\n"), "blocks $name #$i: no double blank line left")
          }
          equal(
              listOf<String>(),
              language.adapter().parse(blocks.delete(text, all)).map { it.pattern },
              "blocks $name: all deleted")
        }
    equal(expectedLines.keys.sorted(), seen.sorted(), "blocks: fixture files")

    fun one(language: Language, text: String, pattern: String): String? {
      val definition =
          language.adapter().parse(text).firstOrNull { it.pattern == pattern } ?: return null
      return blocks.find(language, text, definition.offset)?.text(text)
    }
    fun deleteOne(language: Language, text: String, pattern: String): String? {
      val definition =
          language.adapter().parse(text).firstOrNull { it.pattern == pattern } ?: return null
      val block = blocks.find(language, text, definition.offset) ?: return null
      return blocks.delete(text, listOf(block))
    }

    // Exact texts from the fixture.
    equal(
        "@When(\"the java client sends {int} request(s)\")\n    public void sends(int count) {}",
        one(Language.JAVA, texts["Steps.java"]!!, "the java client sends {int} request(s)"),
        "blocks: java")
    val javaLines = texts["Steps.java"]!!.split("\n")
    equal(
        (javaLines.subList(0, 13) + javaLines.subList(16, javaLines.size)).joinToString("\n"),
        deleteOne(Language.JAVA, texts["Steps.java"]!!, "the java client sends {int} request(s)"),
        "blocks: java deleted with its blank line")
    equal(
        "When \"the ruby client sends {int} request(s)\" do |count|\nend",
        one(Language.RUBY, texts["steps.rb"]!!, "the ruby client sends {int} request(s)"),
        "blocks: ruby without parentheses")
    equal(
        "ctx.Given(\"^the go user \\\"([^\\\"]*)\\\" exists\$\", userExists)",
        one(Language.GO, texts["steps.go"]!!, "^the go user \"([^\"]*)\" exists\$"),
        "blocks: go")
    equal(
        "#[then(regex = r#\"^the rust raw \"([^\"]*)\" pattern\$\"#)]\n" +
            "async fn raw(_world: &mut DemoWorld, _value: String) {}",
        one(Language.RUST, texts["steps.rs"]!!, "^the rust raw \"([^\"]*)\" pattern\$"),
        "blocks: rust raw string")
    equal(
        "[Given(@\"the csharp user \"\"(.*)\"\" exists\")]\n        public void User(string name) { }",
        one(Language.CSHARP, texts["Steps.cs"]!!, "the csharp user \"(.*)\" exists"),
        "blocks: c# verbatim string")
    equal(
        "@then(parsers.cfparse(\"the pytest-bdd total is {total:d}\"), target_fixture=\"total\")\n" +
            "def total(total):\n    return total",
        one(Language.PYTHON, texts["test_bdd.py"]!!, "the pytest-bdd total is {total:d}"),
        "blocks: pytest-bdd parser")
    val goLines = texts["steps.go"]!!.split("\n")
    val goDefinitions = Language.GO.adapter().parse(texts["steps.go"]!!)
    equal(
        goLines.filterIndexed { i, _ -> i != 5 && i != 7 }.joinToString("\n"),
        blocks.delete(
            texts["steps.go"]!!,
            listOf(goDefinitions[0], goDefinitions[2]).map {
              blocks.find(Language.GO, texts["steps.go"]!!, it.offset)!!
            }),
        "blocks: two go calls deleted")
    equal(null, blocks.find(Language.JAVA, texts["Steps.java"]!!, 0), "blocks: not a definition")

    // Annotations shared with other definitions, brackets in literals and comments.
    val java =
        "class S {\n    @Given(\"a\")\n    @When(\"b\")\n    public void x() {}\n\n    @Deprecated\n" +
            "    @Given(\"c\")\n    public void c(String s) {\n        String t = \"}\";\n        char d = '}';\n" +
            "        // }\n        /* } */\n    }\n}\n"
    equal("@When(\"b\")", one(Language.JAVA, java, "b"), "blocks: java stacked annotation")
    equal("@Given(\"a\")", one(Language.JAVA, java, "a"), "blocks: java stacked annotation (first)")
    equal(
        java.replace("    @When(\"b\")\n", ""),
        deleteOne(Language.JAVA, java, "b"),
        "blocks: java stacked annotation deleted")
    equal(
        "@Deprecated\n    @Given(\"c\")\n    public void c(String s) {\n        String t = \"}\";\n" +
            "        char d = '}';\n        // }\n        /* } */\n    }",
        one(Language.JAVA, java, "c"),
        "blocks: java other annotations and braces in literals")
    equal(
        "class S {\n    @Given(\"a\")\n    @When(\"b\")\n    public void x() {}\n}\n",
        deleteOne(Language.JAVA, java, "c"),
        "blocks: java last method deleted with the blank line before")
    equal(
        null,
        one(Language.KOTLIN, "class K {\n    @Given(\"e\")\n    fun e() = Unit\n}\n", "e"),
        "blocks: kotlin expression body")
    val csharpList = "class C {\n    [Given(\"f\"), When(\"g\")]\n    public void F() { }\n}\n"
    equal(null, one(Language.CSHARP, csharpList, "f"), "blocks: c# attribute list")
    equal(null, one(Language.CSHARP, csharpList, "g"), "blocks: c# attribute list (second)")
    equal(
        "[Given(\"h\")]\n    [Scope(Tag = \"x\")]\n    public void H() => Do(\"}\");",
        one(
            Language.CSHARP,
            "class C {\n    [Given(\"h\")]\n    [Scope(Tag = \"x\")]\n    public void H() => Do(\"}\");\n}\n",
            "h"),
        "blocks: c# expression body")
    equal(
        "@when(\"b\")",
        one(Language.PYTHON, "@given(\"a\")\n@when(\"b\")\ndef f():\n    pass\n", "b"),
        "blocks: python stacked")
    val python =
        "import pytest\nfrom pytest_bdd import given, parsers\n\n\n@pytest.mark.slow\n" +
            "@given(parsers.parse(\"h {x:d}\"))\ndef h(\n    x: int,\n) -> None:\n    if x:\n\n        pass  # }\n" +
            "    return None\n\n\ndef other():\n    pass\n"
    equal(
        "@pytest.mark.slow\n@given(parsers.parse(\"h {x:d}\"))\ndef h(\n    x: int,\n) -> None:\n    if x:\n\n" +
            "        pass  # }\n    return None",
        one(Language.PYTHON, python, "h {x:d}"),
        "blocks: python decorators, multi-line header, blank line in body")
    equal(
        "import pytest\nfrom pytest_bdd import given, parsers\n\n\ndef other():\n    pass\n",
        deleteOne(Language.PYTHON, python, "h {x:d}"),
        "blocks: python deleted")
    equal(
        "@given(\"z\")\ndef z(): pass",
        one(Language.PYTHON, "@given(\"z\")\ndef z(): pass\n", "z"),
        "blocks: python one-liner")
    val ruby =
        "Given('r') do |x|\n  if x\n    foo { }\n  end\nend\n\nGiven('s') { |y| puts '}' }\nGiven('t') do end\n"
    equal(
        "Given('r') do |x|\n  if x\n    foo { }\n  end\nend",
        one(Language.RUBY, ruby, "r"),
        "blocks: ruby nested end")
    equal("Given('s') { |y| puts '}' }", one(Language.RUBY, ruby, "s"), "blocks: ruby braces")
    equal("Given('t') do end", one(Language.RUBY, ruby, "t"), "blocks: ruby one-liner")
    val rust =
        "#[given(\"a\")]\n#[allow(unused)]\nasync fn a(w: &mut W) -> Result<(), String> {\n    let s = \"}\";\n" +
            "    let c = '}';\n    Ok(())\n}\n\n#[when(\"b\")]\nfn b<'a>(w: &'a mut W) { let _ = '{'; }\n"
    equal(
        rust.substring(0, rust.indexOf("\n\n")),
        one(Language.RUST, rust, "a"),
        "blocks: rust attributes and literals")
    equal(
        "#[when(\"b\")]\nfn b<'a>(w: &'a mut W) { let _ = '{'; }",
        one(Language.RUST, rust, "b"),
        "blocks: rust lifetimes")
    val js = "Given(/^x\\/y\$/, function () {\n  const s = ')';\n  return `}\${1}`;\n});\n"
    equal(
        js.trimEnd(),
        one(Language.JAVASCRIPT, js, "^x\\/y\$"),
        "blocks: js regex and template literal")
    val cpp = "GIVEN(\"^a\$\") {\n  REGEX_PARAM(int, n);\n  if (n) { }\n}\n"
    equal(cpp.trimEnd(), one(Language.CPP, cpp, "^a\$"), "blocks: c++ macro")
    val scala = "Given(\"\"\"x\"\"\") { () =>\n  val s = \"}\"\n}\n"
    equal(scala.trimEnd(), one(Language.SCALA, scala, "x"), "blocks: scala multi-line closure")
    val javaText = texts["Steps.java"]!!
    equal(
        javaText.substring(0, javaText.indexOf("public class")) +
            "public class Steps {\n    // @Given(\"the java commented step\")\n}\n",
        blocks.delete(
            javaText,
            Language.JAVA.adapter().parse(javaText).map {
              blocks.find(Language.JAVA, javaText, it.offset)!!
            }),
        "blocks: all java definitions deleted")
  }

  // --- feature: completion ---------------------------------------------------

  private fun templateCase(
      kind: PatternKind,
      pattern: String,
      text: String?,
      display: String? = null,
      placeholders: String? = null,
  ) {
    val template = StepTextTemplates.render(kind, pattern)
    if (text == null) {
      equal(null, template?.text, "template $pattern: not renderable")
      return
    }
    equal(text, template?.text, "template $pattern: text")
    if (display != null) {
      equal(display, template?.display, "template $pattern: display")
    }
    if (placeholders != null && template != null) {
      val actual =
          template.placeholders.joinToString(", ") { p ->
            p.name +
                "=" +
                template.defaultValue(p) +
                (if (p.choices.isEmpty()) "" else p.choices.toString())
          }
      equal(placeholders, actual, "template $pattern: placeholders")
    }
    if (template != null) {
      val regex =
          Patterns.toRegex(kind, pattern).let {
            if (kind == PatternKind.REGEX) Patterns.fullMatch(it) else it
          }
      check(
          Pattern.compile(regex).matcher(template.text).find(),
          "template $pattern: '${template.text}' matches $regex")
    }
  }

  private fun stepTextTemplates(args: Array<String>) {
    val ce = PatternKind.CUCUMBER_EXPRESSION
    val re = PatternKind.REGEX
    templateCase(
        ce,
        "the user {string} sends {int} request(s)",
        "the user \"\" sends 0 requests",
        "the user \"<string>\" sends <int> request(s)",
        "string=, int=0")
    val user = StepTextTemplates.render(ce, "the user {string} sends {int} request(s)")!!
    equal(10, user.placeholders[0].start, "string placeholder sits inside the quotes")
    equal(10, user.placeholders[0].end, "string placeholder is empty")
    equal(
        "0",
        user.text.substring(user.placeholders[1].start, user.placeholders[1].end),
        "int placeholder range")
    templateCase(
        ce,
        "the java service is ready",
        "the java service is ready",
        "the java service is ready",
        "")
    templateCase(
        ce, "the light is on/off", "the light is on", "the light is on/off", "choice=on[on, off]")
    templateCase(
        ce,
        "I have {float} cucumber(s) in my belly/stomach",
        "I have 0.0 cucumbers in my belly",
        "I have <float> cucumber(s) in my belly/stomach",
        "float=0.0, choice=belly[belly, stomach]")
    templateCase(ce, "a/an apple(s)", "a apples", "a/an apple(s)", "choice=a[a, an]")
    templateCase(
        ce,
        "it costs \\{1\\} \\(approx\\) a\\/b",
        "it costs {1} (approx) a/b",
        "it costs {1} (approx) a/b",
        "")
    templateCase(
        ce,
        "the {color} car is {word}",
        "the color car is word",
        "the <color> car is <word>",
        "color=color, word=word")
    templateCase(
        ce, "anything {} goes", "anything param goes", "anything <param> goes", "param=param")
    templateCase(
        ce,
        "{biginteger} and {double} and {long}",
        "0 and 0.0 and 0",
        "<biginteger> and <double> and <long>")
    templateCase(ce, "{string}", "\"\"", "\"<string>\"", "string=")
    templateCase(ce, "   ", null)

    templateCase(
        re,
        "^the java user \"([^\"]*)\" exists$",
        "the java user \"\" exists",
        "the java user \"<string>\" exists",
        "string=")
    templateCase(
        re, "the java total is (\\d+)", "the java total is 0", "the java total is <int>", "int=0")
    templateCase(
        re, "^I wait (-?\\d+) seconds?$", "I wait 0 seconds", "I wait <int> second(s)", "int=0")
    templateCase(
        re, "^I pay (\\d+\\.\\d+) euros$", "I pay 0.0 euros", "I pay <float> euros", "float=0.0")
    templateCase(
        re,
        "^the (?:first|second) item is '(.*)'$",
        "the first item is ''",
        "the first/second item is '<string>'",
        "choice=first[first, second], string=")
    templateCase(
        re,
        "^the light is (on|off)$",
        "the light is on",
        "the light is on/off",
        "choice=on[on, off]")
    templateCase(
        re, "^the (?<state>on|off) light$", "the on light", "the on/off light", "state=on[on, off]")
    templateCase(
        re, "^I have (?P<count>\\d+) cukes$", "I have 0 cukes", "I have <count> cukes", "count=0")
    templateCase(
        re, "^I have (?<name>.+) here$", "I have name here", "I have <name> here", "name=name")
    templateCase(re, "^I see (.*)$", "I see param", "I see <param>", "param=param")
    templateCase(re, "^I am (?:a )?user$", "I am a user", "I am (a )user", "")
    templateCase(
        re,
        "^the user(?:s)? (?:is|are) (\\w+)$",
        "the users is word",
        "the user(s) is/are <word>",
        "choice=is[is, are], word=word")
    templateCase(
        re,
        "^the (?:big red|small blue) box$",
        "the big red box",
        "the (big red|small blue) box",
        "choice=big red[big red, small blue]")
    templateCase(
        re, "\\Athe price is \\$(\\d+)\\z", "the price is $0", "the price is $<int>", "int=0")
    templateCase(
        re, "^a\\.b \\(c\\) \\Q[x]\\E\\s+done$", "a.b (c) [x] done", "a.b (c) [x] done", "")
    templateCase(re, "^the end\\.$", "the end.", "the end.", "")
    templateCase(re, "^(?i)case insensitive$", "case insensitive", "case insensitive", "")
    templateCase(re, "^\\bword(?=s)s$", "words", "words", "")
    templateCase(re, "^it is [.] and [ ]ok$", "it is . and  ok", "it is . and  ok", "")
    templateCase(re, "^a (literal) group$", "a literal group", "a literal group", "")
    templateCase(
        re, "^optional (literal)? group$", "optional literal group", "optional (literal) group", "")
    templateCase(
        re,
        "^(\\d+) (?:cats|dogs) and ([^\"]*) more$",
        "0 cats and param more",
        "<int> cats/dogs and <param> more",
        "int=0, choice=cats[cats, dogs], param=param")
    templateCase(re, "^I .* something$", null)
    templateCase(re, "^I \\d+ times$", null)
    templateCase(re, "^I [abc] times$", null)
    templateCase(re, "^a|b$", null)
    templateCase(re, "^repeat{3}$", null)
    templateCase(re, "^back (\\w+) \\1$", null)
    templateCase(re, "^unclosed (group$", null)
    templateCase(re, "^(?:x (\\d+)|y)$", null)
    templateCase(PatternKind.EXACT, "a plain {step}", "a plain {step}", "a plain {step}", "")
    templateCase(
        PatternKind.PARSE_FORMAT,
        "I have {count:d} \"{name}\" and {price:f} {{x}} {}",
        "I have 0 \"name\" and 0.0 {x} param",
        "I have <count> \"<name>\" and <price> {x} <param>",
        "count=0, name=name, price=0.0, param=param")
    templateCase(
        re,
        "^say \"(.+)\" and \"(\\d+)\"$",
        "say \"string\" and \"0\"",
        "say \"<string>\" and \"<int>\"",
        "string=string, int=0")

    check(StepTextTemplates.startsWith("the java service", "the java "), "startsWith: prefix")
    check(StepTextTemplates.startsWith("The Java service", "the java"), "startsWith: ignores case")
    check(!StepTextTemplates.startsWith("a java service", "the java"), "startsWith: other text")
    check(
        StepTextTemplates.matchesWords("the java client sends 0 requests", ""),
        "words: empty prefix")
    check(
        StepTextTemplates.matchesWords("the java client sends 0 requests", "java sends"),
        "words: in order")
    check(
        StepTextTemplates.matchesWords("the java client sends 0 requests", "th ja cl"),
        "words: word prefixes")
    check(
        !StepTextTemplates.matchesWords("the java client sends 0 requests", "sends java"),
        "words: out of order")
    check(
        !StepTextTemplates.matchesWords("the java client sends 0 requests", "kotlin"),
        "words: missing word")
    check(StepTextTemplates.matchesWords("the java client", "ava cli"), "words: contains")
    check(StepTextTemplates.matchesWords("假如服务已就绪", "服务"), "words: CJK substring")

    // Every fixture definition renders to text that its own regex matches.
    val fixture = args.firstOrNull()?.let { Path.of(it).resolve("steps") } ?: return
    var rendered = 0
    var definitions = 0
    Files.list(fixture).use { files ->
      for (file in files.toList()) {
        val language = Language.forFileName(file.fileName.toString()) ?: continue
        val text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n")
        for (info in language.adapter().parse(text)) {
          definitions++
          val template = StepTextTemplates.render(info) ?: continue
          rendered++
          check(
              info.matches(template.text),
              "fixture template '${template.text}' matches ${info.pattern}")
          check(
              !template.display.contains('\\'),
              "fixture template display '${template.display}' is readable")
        }
      }
    }
    check(definitions > 30, "fixture definitions: $definitions")
    equal(definitions, rendered, "every fixture definition renders")
  }

  internal fun check(ok: Boolean, message: String) {
    checks++
    if (!ok) {
      failures++
      println("FAIL: $message")
    }
  }

  internal fun equal(expected: Any?, actual: Any?, what: String) {
    check(Objects.equals(expected, actual), "$what: expected <$expected> but was <$actual>")
  }

  private fun value(literal: Literals.Literal?): String? = literal?.value

  private fun isBlank(s: String): Boolean = s.all { Character.isWhitespace(it) }

  // ---------------------------------------------------------------------------

  private fun literals() {
    equal("a\"b", value(Literals.rust("\"a\\\"b\"", 0)), "rust escaped quote")
    equal("x \"y\" \\d", value(Literals.rust("r#\"x \"y\" \\d\"#", 0)), "rust raw with hashes")
    equal("ab", value(Literals.rust("\"a\\\n      b\"", 0)), "rust line continuation")
    equal("\\d+", value(Literals.python("r'\\d+'", 0)), "python raw")
    equal("\\d+", value(Literals.python("'\\d+'", 0)), "python keeps unknown escape")
    equal("it's", value(Literals.python("u\"it's\"", 0)), "python u prefix")
    equal("a\nb", value(Literals.python("'''a\nb'''", 0)), "python triple")
    check(Literals.python("f'x{y}'", 0) == null, "python f-string is dynamic")
    equal("say \"hi\"", value(Literals.cSharp("@\"say \"\"hi\"\"\"", 0)), "c# verbatim")
    check(Literals.cSharp("\$\"x {y}\"", 0) == null, "c# interpolated is dynamic")
    equal("plain", value(Literals.javaScript("`plain`", 0)), "js template")
    check(Literals.javaScript("`x \${y}`", 0) == null, "js template with substitution is dynamic")
    equal(
        "^a\\/b [/] (\\d+)\$", value(Literals.regex("/^a\\/b [/] (\\d+)\$/i", 0)), "regex literal")
    equal(
        "^a{2} (\\d+)\$",
        value(Literals.rubyPercentRegex("%r{^a{2} (\\d+)\$}", 0)),
        "ruby %r nested braces")
    equal("it's", value(Literals.ruby("'it\\'s'", 0)), "ruby single quote")
    equal(
        "one\n\"two\"",
        value(Literals.java("\"\"\"\n    one\n    \\\"two\\\"\"\"\"", 0)),
        "java text block")
    equal("^(\\w+)\$", value(Literals.kotlin("\"\"\"^(\\w+)\$\"\"\"", 0)), "kotlin raw")
    equal("^x\$", value(Literals.go("`^x\$`", 0)), "go raw")
    val url = "const url = 'https://example.test';"
    check(
        !Literals.inComment(url, url.indexOf(';'), "//", true, false, false),
        "comment marker inside string")
    val nested = "/* outer /* nested */ still comment */"
    check(
        Literals.inComment(nested, nested.indexOf("still"), "//", true, true, false),
        "nested block comment")
    val closed = "/* outer /* nested */ still */ code"
    check(
        !Literals.inComment(closed, closed.indexOf("code"), "//", true, true, false),
        "nested block comment ends correctly")
    val line = "const x = 1; // comment"
    check(Literals.inComment(line, line.length, "//", true, false, false), "line comment")
  }

  private fun patterns() {
    val expression =
        Patterns.cucumberExpression("I have {int} cucumber(s) in my belly/stomach \\(really\\)")
    check(
        Pattern.compile(expression).matcher("I have 3 cucumbers in my belly (really)").find(),
        "expression plural")
    check(
        Pattern.compile(expression).matcher("I have 1 cucumber in my stomach (really)").find(),
        "expression alternation")
    check(
        !Pattern.compile(expression).matcher("I have x cucumbers in my belly (really)").find(),
        "expression int")
    val strings = Patterns.cucumberExpression("the {string} and {string}")
    check(Pattern.compile(strings).matcher("the \"a b\" and 'c'").find(), "expression strings")
    check(
        Pattern.compile(Patterns.cucumberExpression("pay {float} {word}"))
            .matcher("pay -1.5 EUR")
            .find(),
        "expression float word")
    check(
        Pattern.compile(Patterns.cucumberExpression("total is {}"))
            .matcher("total is anything")
            .find(),
        "expression anonymous")
    check(
        !Pattern.compile(Patterns.cucumberExpression("ready")).matcher("not ready").find(),
        "expression anchored")

    val parse = Patterns.parseFormat("the user \"{name}\" has {count:d} items {{literal}}")
    check(
        Pattern.compile(parse).matcher("the user \"bob\" has 3 items {literal}").find(),
        "parse format")
    check(
        !Pattern.compile(parse).matcher("the user \"bob\" has x items {literal}").find(),
        "parse format :d")

    equal("(?<value>\\w+)", Patterns.normalizeRegex("(?P<value>\\w+)"), "python named group")
    equal("^(?:a (\\d+))\$", Patterns.fullMatch("a (\\d+)"), "full match wrap")
    equal("^a\$", Patterns.fullMatch("^a\$"), "full match keeps anchored")
    equal(
        listOf("value", "arg1"),
        Patterns.parameterNames(PatternKind.REGEX, "(?P<value>\\w+) (\\d+)(?:x)"),
        "regex parameter names")
  }

  private fun comments() {
    equal(
        1,
        JavaScriptAdapter.parse("const url = 'https://example.test'; Given('visible', fn);").size,
        "JavaScript URL string does not hide a same-line definition")
    equal(
        1,
        JavaScriptAdapter.parse("/* Given('hidden', fn); */ Given('visible', fn);").size,
        "JavaScript block-commented definition is ignored")
    equal(
        1,
        JvmAdapter.JAVA.parse("/* @Given(\"hidden\") */ @Given(\"visible\") void step() {}").size,
        "JVM block-commented definition is ignored")
    equal(
        1,
        RustAdapter.parse("/* #[given(\"hidden\")] */ #[given(\"visible\")] async fn step() {}")
            .size,
        "Rust block-commented definition is ignored")
  }

  private val SAMPLE_FILES: Map<Language, String> =
      mapOf(
          Language.RUST to "use cucumber::given;\n\n#[tokio::main]\nasync fn main() {}\n",
          Language.GO to
              "package steps\n\nfunc InitializeScenario(sc *godog.ScenarioContext) {\n\tsc.Step(`^x \"{\"\$`, x)\n}\n",
          Language.PYTHON to "from behave import given\n",
          Language.JAVASCRIPT to "const { Given } = require('@cucumber/cucumber');\n",
          Language.JAVA to "public class Steps {\n}\n",
          Language.KOTLIN to "class Steps {\n}\n",
          Language.GROOVY to "import static io.cucumber.groovy.EN.*\n",
          Language.SCALA to
              "import io.cucumber.scala.{EN, ScalaDsl}\nclass Steps extends ScalaDsl with EN\n",
          Language.CPP to "#include <cucumber-cpp/autodetect.hpp>\n",
          Language.CSHARP to "namespace Demo\n{\n    public class Steps\n    {\n    }\n}\n",
          Language.RUBY to "",
      )

  private fun snippetsRoundTrip() {
    val stepText = "the user \"bob\" has 3 apple(s) and 2.5 pears/plums"
    val request = Snippets.request("When", stepText, false, false)
    for (language in Language.entries) {
      roundTrip(
          language,
          SAMPLE_FILES.getValue(language),
          "steps." + language.extensions()[0],
          request,
          stepText)
    }
    roundTrip(
        Language.PYTHON,
        "from pytest_bdd import given, parsers\n",
        "test_steps.py",
        request,
        stepText)
    roundTrip(
        Language.PYTHON,
        "from behave import given, use_step_matcher\nuse_step_matcher('re')\n",
        "steps.py",
        request,
        stepText)
    roundTrip(Language.JAVASCRIPT, "", "steps.ts", request, stepText)
    val goFallback = Language.GO.adapter().snippet(request, "package steps\n", "steps.go")
    val goResult = goFallback.apply("package steps\n")
    check(
        goResult.contains("// Register in the scenario initializer: ctx.Step(") &&
            goResult.contains("func theUserHasAppleSAndPearsPlums("),
        "Go snippet without an initializer says where to register:\n$goResult")
    roundTrip(
        Language.CSHARP,
        "namespace Demo;\n\npublic class Steps\n{\n}\n",
        "Steps.cs",
        request,
        stepText)
    val plain = Snippets.request("Given", "a plain step", false, false)
    roundTrip(
        Language.PYTHON, "from pytest_bdd import given\n", "test_steps.py", plain, "a plain step")
    roundTrip(Language.RUST, "", "steps.rs", plain, "a plain step")
    roundTrip(Language.CPP, SAMPLE_FILES.getValue(Language.CPP), "steps.cpp", request, stepText)
  }

  private fun roundTrip(
      language: Language,
      fileText: String,
      fileName: String,
      request: Snippets.Request,
      stepText: String,
  ) {
    val snippet = language.adapter().snippet(request, fileText, fileName)
    val result = snippet.apply(fileText)
    val steps = language.adapter().parse(result)
    val matching = steps.count { it.matches(stepText) }
    check(matching == 1, "$language ($fileName) snippet round-trip: $matching matches in\n$result")
    val navigation = snippet.navigationOffset()
    check(
        navigation >= 0 && navigation < result.length && !isBlank(result.substring(navigation)),
        "$language ($fileName) navigation offset $navigation")
    if (language == Language.JAVA || language == Language.KOTLIN) {
      check(
          strip(result).endsWith("}") && result.indexOf("Steps {") < result.indexOf("@When"),
          "$language snippet inside the class:\n$result")
    }
    if (language == Language.CSHARP) {
      check(
          result.lastIndexOf("[When(") < result.lastIndexOf("    }\n"),
          "C# snippet inside the class:\n$result")
    }
    if (language == Language.RUST && fileText.contains("#[tokio::main]")) {
      check(
          result.indexOf("#[when(") < result.indexOf("#[tokio::main]"),
          "Rust snippet before main:\n$result")
    }
    if (language == Language.CPP) {
      check(result.contains("REGEX_PARAM("), "C++ snippet declares regex parameters:\n$result")
    }
  }

  private fun strip(s: String): String = s.trim { Character.isWhitespace(it) }

  // ---------------------------------------------------------------------------

  private data class FeatureStep(
      val file: Path,
      val line: Int,
      val text: String,
      val keyword: String
  )

  /** Every feature step under `root` must match exactly one step definition. */
  private fun resolveProject(root: Path, fixture: Boolean) {
    val definitions = ArrayList<StepDefinitionInfo>()
    val featureSteps = ArrayList<FeatureStep>()
    Files.walk(root).use { files ->
      for (file in files.filter { Files.isRegularFile(it) }.toList()) {
        if (IgnoredPaths.isIgnored(root.relativize(file).toString())) {
          continue
        }
        val name = file.fileName.toString()
        if (name.endsWith(".feature")) {
          featureSteps.addAll(this.featureSteps(file))
          continue
        }
        val language = Language.forFileName(name)
        if (language != null) {
          val text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n")
          if (language.adapter().mayContainSteps(text)) {
            definitions.addAll(language.adapter().parse(text))
          }
        }
      }
    }
    check(featureSteps.isNotEmpty(), "$root: no feature steps found")
    if (fixture) {
      val commented = definitions.map { it.pattern }.filter { it.contains("commented") }
      check(commented.isEmpty(), "$root: commented-out definitions were parsed: $commented")
    }
    var resolved = 0
    for (step in featureSteps) {
      val matches = definitions.filter { it.matches(step.text, step.keyword) }
      val ok = matches.size == 1
      check(
          ok,
          "${step.file.fileName}:${step.line} \"${step.text}\" matched ${matches.size} definitions ${matches.map { it.pattern }}")
      resolved += if (ok) 1 else 0
    }
    System.out.printf(
        "%s: %d/%d feature steps resolved to exactly one of %d definitions%n",
        root,
        resolved,
        featureSteps.size,
        definitions.size)
  }

  private fun featureSteps(file: Path): List<FeatureStep> {
    val steps = ArrayList<FeatureStep>()
    val lines = Files.readAllLines(file, StandardCharsets.UTF_8)
    val dialect = GherkinSyntax.dialect(lines)
    var fence: String? = null
    var previous: String? = null
    for (i in lines.indices) {
      val trimmed = lines[i].trim { it <= ' ' }
      if (fence != null) {
        if (trimmed.startsWith(fence)) {
          fence = null
        }
        continue
      }
      if (trimmed.startsWith("\"\"\"") || trimmed.startsWith("```")) {
        fence = trimmed.substring(0, 3)
        continue
      }
      if (GherkinSyntax.header(trimmed, dialect) != null) {
        previous = null
        continue
      }
      val step = GherkinSyntax.step(trimmed, dialect)
      if (step != null && step.text.isNotEmpty()) {
        val keyword = GherkinSyntax.effectiveKeyword(step.type, previous)
        previous = keyword
        steps.add(FeatureStep(file, i + 1, step.text, keyword))
      }
    }
    return steps
  }
}
