package io.github.fangzhengjie.cucumber.lang

import io.github.fangzhengjie.cucumber.core.Language
import io.github.fangzhengjie.cucumber.core.LanguageAdapter
import io.github.fangzhengjie.cucumber.core.Literals
import io.github.fangzhengjie.cucumber.core.PatternKind
import io.github.fangzhengjie.cucumber.core.Patterns
import io.github.fangzhengjie.cucumber.core.Snippets
import io.github.fangzhengjie.cucumber.core.StepDefinitionInfo
import java.util.Locale

/** godog: ``ctx.Step(`^regex$`, handler)`` and `.Given/.When/.Then`; always regex. */
object GoAdapter : LanguageAdapter {

  private val CALL = Regex("""\.\s*(Step|Given|When|Then)\s*\(\s*""")

  /** `func X(ctx *godog.ScenarioContext) {}` — group 1 is the context parameter name. */
  private val INITIALIZER =
      Regex("""func\s+\w+\s*\(\s*(\w+)\s+\*godog\.ScenarioContext\s*\)\s*\{""")

  private val CTX_PREFIX = Regex("^ctx")

  override fun language(): Language = Language.GO

  override fun mayContainSteps(text: CharSequence): Boolean = CALL.containsMatchIn(text)

  override fun parse(text: String): List<StepDefinitionInfo> {
    val steps = ArrayList<StepDefinitionInfo>()
    for (call in CALL.findAll(text)) {
      if (Literals.inComment(text, call.range.first, "//", true, false, false)) {
        continue
      }
      val literal = Literals.go(text, call.range.last + 1)
      if (literal != null) {
        steps.add(
            StepDefinitionInfo.of(
                Language.GO,
                call.groupValues[1].lowercase(Locale.ROOT),
                PatternKind.REGEX,
                literal.value,
                Patterns.normalizeRegex(literal.value),
                literal.start))
      }
    }
    return steps
  }

  override fun snippet(
      request: Snippets.Request,
      fileText: String,
      fileName: String
  ): Snippets.Snippet {
    val regex = Snippets.regex(request)
    val pattern = if (regex.contains("`")) "\"" + Snippets.escape(regex, '"') + "\"" else "`$regex`"
    val name = Snippets.camelName(request)
    val parameters = ArrayList<String>()
    val names = Snippets.argumentNames(request)
    val types = request.parameterTypes()
    for (i in names.indices) {
      parameters.add(names[i] + " " + goType(types[i]))
    }
    if (request.docString) {
      parameters.add("docString *godog.DocString")
    }
    if (request.dataTable) {
      parameters.add("table *godog.Table")
    }
    val function =
        "func " +
            name +
            "(" +
            parameters.joinToString(", ") +
            ") error {\n\treturn godog.ErrPending\n}\n"
    val registration = "ctx.Step($pattern, $name)"
    val end = fileText.length
    val initializer = INITIALIZER.find(fileText)
    val close = if (initializer != null) matchingBrace(fileText, initializer.range.last) else -1
    if (initializer == null || close < 0) {
      // No scenario initializer to register in: say where the handler must be registered.
      val code = "// Register in the scenario initializer: $registration\n$function"
      return Snippets.Snippet.of(Snippets.separated(fileText, end, code), end)
    }
    val context = initializer.groupValues[1]
    val lineStart = fileText.lastIndexOf('\n', close - 1) + 1
    val register =
        Snippets.Edit(lineStart, "\t" + CTX_PREFIX.replaceFirst(registration, context) + "\n")
    val handler = Snippets.Edit(end, Snippets.separated(fileText, end, function))
    return Snippets.Snippet(listOf(register, handler), 1)
  }

  /** Index of the brace closing the one at `open`, skipping strings; -1 if unbalanced. */
  private fun matchingBrace(text: String, open: Int): Int {
    var depth = 0
    var i = open
    while (i < text.length) {
      val c = text[i]
      if (c == '"' || c == '`' || c == '\'') {
        val literal = if (c == '\'') null else Literals.go(text, i)
        val end = literal?.end ?: (text.indexOf(c, i + 1) + 1)
        if (end <= 0) {
          return -1
        }
        i = end - 1
      } else if (c == '{') {
        depth++
      } else if (c == '}' && --depth == 0) {
        return i
      }
      i++
    }
    return -1
  }

  private fun goType(type: Snippets.PartType): String =
      when (type) {
        Snippets.PartType.INT -> "int"
        Snippets.PartType.FLOAT -> "float64"
        else -> "string"
      }
}
