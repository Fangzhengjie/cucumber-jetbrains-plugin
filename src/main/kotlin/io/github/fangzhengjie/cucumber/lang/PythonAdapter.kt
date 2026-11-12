package io.github.fangzhengjie.cucumber.lang

import io.github.fangzhengjie.cucumber.core.Language
import io.github.fangzhengjie.cucumber.core.LanguageAdapter
import io.github.fangzhengjie.cucumber.core.Literals
import io.github.fangzhengjie.cucumber.core.PatternKind
import io.github.fangzhengjie.cucumber.core.Patterns
import io.github.fangzhengjie.cucumber.core.Snippets
import io.github.fangzhengjie.cucumber.core.StepDefinitionInfo
import java.util.Locale

/**
 * behave (`@given('parse format')`, `use_step_matcher("re")`) and pytest-bdd (`@given("exact
 * text")`, `parsers.parse/cfparse/re/string(...)`).
 */
object PythonAdapter : LanguageAdapter {

  private val DECORATOR =
      Regex("""@\s*(?:[A-Za-z_]\w*\.)*(given|when|then|step|Given|When|Then|Step)\s*\(\s*""")
  private val PARSER = Regex("""(?:[A-Za-z_]\w*\.)?(parse|cfparse|re|string)\s*\(\s*""")
  private val STEP_MATCHER = Regex("""(?:use_step_matcher|step_matcher)\s*\(\s*['"](\w+)['"]""")

  override fun language(): Language = Language.PYTHON

  override fun mayContainSteps(text: CharSequence): Boolean = DECORATOR.containsMatchIn(text)

  private fun isPytestBdd(text: String): Boolean = text.contains("pytest_bdd")

  /** behave's active step matcher at `offset` (`parse` by default). */
  private fun behaveMatcherAt(text: String, offset: Int): String {
    var matcher = "parse"
    for (m in STEP_MATCHER.findAll(text)) {
      if (m.range.first >= offset) {
        break
      }
      if (!Literals.inComment(text, m.range.first, "#", false, false, false)) {
        matcher = m.groupValues[1]
      }
    }
    return matcher
  }

  override fun parse(text: String): List<StepDefinitionInfo> {
    val steps = ArrayList<StepDefinitionInfo>()
    val pytestBdd = isPytestBdd(text)
    for (decorator in DECORATOR.findAll(text)) {
      if (Literals.inComment(text, decorator.range.first, "#", false, false, false)) {
        continue
      }
      var position = decorator.range.last + 1
      var parser: String? = null
      val wrapper = PARSER.matchAt(text, position)
      if (wrapper != null) {
        parser = wrapper.groupValues[1]
        position = wrapper.range.last + 1
      }
      val literal = Literals.python(text, position) ?: continue
      val mode = parser ?: if (pytestBdd) "string" else behaveMatcherAt(text, decorator.range.first)
      val kind =
          when (mode) {
            "re" -> PatternKind.REGEX
            "string" -> PatternKind.EXACT
            else -> PatternKind.PARSE_FORMAT
          }
      val regex =
          if (kind == PatternKind.REGEX) Patterns.fullMatch(literal.value)
          else Patterns.toRegex(kind, literal.value)
      steps.add(
          StepDefinitionInfo.of(
              Language.PYTHON,
              decorator.groupValues[1].lowercase(Locale.ROOT),
              kind,
              literal.value,
              regex,
              literal.start))
    }
    return steps
  }

  override fun snippet(
      request: Snippets.Request,
      fileText: String,
      fileName: String
  ): Snippets.Snippet {
    val names = Snippets.argumentNames(request)
    val code: String
    if (isPytestBdd(fileText)) {
      val pattern =
          if (names.isEmpty()) quote(textOf(request))
          else "parsers.parse(" + quote(Snippets.parseFormat(request)) + ")"
      val parameters = ArrayList(names)
      if (request.docString) {
        parameters.add("docstring")
      }
      if (request.dataTable) {
        parameters.add("datatable")
      }
      code =
          "@" +
              request.keyword +
              "(" +
              pattern +
              ")\ndef " +
              Snippets.snakeName(request) +
              "(" +
              parameters.joinToString(", ") +
              "):\n    raise NotImplementedError\n"
    } else {
      val pattern =
          if (behaveMatcherAt(fileText, fileText.length) == "re")
              "r" + quote(Snippets.regex(request))
          else quote(Snippets.parseFormat(request))
      val parameters = ArrayList<String>()
      parameters.add("context")
      parameters.addAll(names)
      code =
          "@" +
              request.keyword +
              "(" +
              pattern +
              ")\ndef step_impl(" +
              parameters.joinToString(", ") +
              "):\n    raise NotImplementedError\n"
    }
    return Snippets.Snippet.of(Snippets.separated(fileText, fileText.length, code), fileText.length)
  }

  private fun textOf(request: Snippets.Request): String = request.parts.joinToString("") { it.text }

  private fun quote(text: String): String = "'" + Snippets.escape(text, '\'') + "'"
}
