package io.github.fangzhengjie.cucumber.lang

import io.github.fangzhengjie.cucumber.core.Language
import io.github.fangzhengjie.cucumber.core.LanguageAdapter
import io.github.fangzhengjie.cucumber.core.Literals
import io.github.fangzhengjie.cucumber.core.PatternKind
import io.github.fangzhengjie.cucumber.core.Patterns
import io.github.fangzhengjie.cucumber.core.Snippets
import io.github.fangzhengjie.cucumber.core.StepDefinitionInfo
import java.util.Locale

/** cucumber-ruby: `Given('expression') do`, `Given(/regex/) do`, `%r{...}`. */
object RubyAdapter : LanguageAdapter {

  private val CALL = Regex("""(?<![\w.:@${'$'}])(Given|When|Then|And|But|Step)\b\s*\(?\s*""")

  override fun language(): Language = Language.RUBY

  override fun mayContainSteps(text: CharSequence): Boolean = CALL.containsMatchIn(text)

  override fun parse(text: String): List<StepDefinitionInfo> {
    val steps = ArrayList<StepDefinitionInfo>()
    for (call in CALL.findAll(text)) {
      if (Literals.inComment(text, call.range.first, "#", false, false, true)) {
        continue
      }
      val literal = Literals.ruby(text, call.range.last + 1) ?: continue
      val first = text[literal.start]
      val kind = if (first == '/' || first == '%') PatternKind.REGEX else PatternKind.CUCUMBER_EXPRESSION
      steps.add(
          StepDefinitionInfo.of(
              Language.RUBY,
              call.groupValues[1].lowercase(Locale.ROOT),
              kind,
              literal.value,
              Patterns.toRegex(kind, literal.value),
              literal.start))
    }
    return steps
  }

  override fun snippet(request: Snippets.Request, fileText: String, fileName: String): Snippets.Snippet {
    val parameters = ArrayList(Snippets.argumentNames(request))
    if (request.docString) {
      parameters.add("doc_string")
    }
    if (request.dataTable) {
      parameters.add("table")
    }
    val code =
        Snippets.capitalized(request.keyword) +
            "('" +
            Snippets.escape(Snippets.cucumberExpression(request), '\'') +
            "') do" +
            (if (parameters.isEmpty()) "" else " |" + parameters.joinToString(", ") + "|") +
            "\n  pending\nend\n"
    return Snippets.Snippet.of(Snippets.separated(fileText, fileText.length, code), fileText.length)
  }
}
