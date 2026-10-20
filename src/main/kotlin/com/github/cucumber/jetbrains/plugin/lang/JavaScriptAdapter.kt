package com.github.cucumber.jetbrains.plugin.lang

import com.github.cucumber.jetbrains.plugin.core.Language
import com.github.cucumber.jetbrains.plugin.core.LanguageAdapter
import com.github.cucumber.jetbrains.plugin.core.Literals
import com.github.cucumber.jetbrains.plugin.core.PatternKind
import com.github.cucumber.jetbrains.plugin.core.Patterns
import com.github.cucumber.jetbrains.plugin.core.Snippets
import com.github.cucumber.jetbrains.plugin.core.StepDefinitionInfo
import java.util.Locale

/**
 * cucumber-js: `Given('cucumber expression', fn)` and `Given(/regex/, fn)`, also `defineStep`,
 * `this.Given` and TypeScript generics (`Given<World>(...)`).
 */
object JavaScriptAdapter : LanguageAdapter {

  private val CALL =
      Regex("""(?<![\w${'$'}.])(?:this\.)?(Given|When|Then|defineStep|Step)\s*(?:<[^<>()]*>)?\s*\(\s*""")
  private val TYPESCRIPT_FILE = Regex("""(?i).*\.(ts|tsx|mts|cts)${'$'}""")

  override fun language(): Language = Language.JAVASCRIPT

  override fun mayContainSteps(text: CharSequence): Boolean = CALL.containsMatchIn(text)

  override fun parse(text: String): List<StepDefinitionInfo> {
    val steps = ArrayList<StepDefinitionInfo>()
    for (call in CALL.findAll(text)) {
      if (Literals.inComment(text, call.range.first, "//", true, false, true)) {
        continue
      }
      val end = call.range.last + 1
      val regex = end < text.length && text[end] == '/'
      val literal = (if (regex) Literals.regex(text, end) else Literals.javaScript(text, end)) ?: continue
      val kind = if (regex) PatternKind.REGEX else PatternKind.CUCUMBER_EXPRESSION
      val keyword = call.groupValues[1].lowercase(Locale.ROOT)
      steps.add(
          StepDefinitionInfo.of(
              Language.JAVASCRIPT,
              if (keyword == "definestep") "step" else keyword,
              kind,
              literal.value,
              Patterns.toRegex(kind, literal.value),
              literal.start))
    }
    return steps
  }

  override fun snippet(request: Snippets.Request, fileText: String, fileName: String): Snippets.Snippet {
    val typeScript = fileName.matches(TYPESCRIPT_FILE)
    val parameters = ArrayList<String>()
    val names = Snippets.argumentNames(request)
    val types = request.parameterTypes()
    for (i in names.indices) {
      val type = if (types[i] == Snippets.PartType.STRING) "string" else "number"
      parameters.add(if (typeScript) names[i] + ": " + type else names[i])
    }
    if (request.docString) {
      parameters.add(if (typeScript) "docString: string" else "docString")
    }
    if (request.dataTable) {
      parameters.add(if (typeScript) "dataTable: DataTable" else "dataTable")
    }
    val code =
        Snippets.capitalized(request.keyword) +
            "('" +
            Snippets.escape(Snippets.cucumberExpression(request), '\'') +
            "', async function (" +
            parameters.joinToString(", ") +
            ") {\n  return 'pending';\n});\n"
    return Snippets.Snippet.of(Snippets.separated(fileText, fileText.length, code), fileText.length)
  }
}
