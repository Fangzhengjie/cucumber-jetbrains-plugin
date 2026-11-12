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
 * Reqnroll / SpecFlow: `[Given("...")]`, `[StepDefinition(@"...")]`. Like Reqnroll, a pattern that
 * looks like a regex (anchors, `(.*)`, `\d`, ...) is a full-match regex; anything else is a
 * Cucumber Expression.
 */
object CSharpAdapter : LanguageAdapter {

  private val ATTRIBUTE =
      Regex("""(?:\[|,)\s*(?:[\w.]*\.)?(Given|When|Then|StepDefinition)(?:Attribute)?\s*\(\s*""")
  private val REGEX_HINT =
      Regex("""^\^|\${'$'}${'$'}|\(\.[*+]\??\)|\\[dwsDWSb]|\[\^|\(\?|\[[^\]]*-[^\]]*]""")
  private val BLOCK_NAMESPACE = Regex("""namespace\s+[\w.]+\s*\{""")

  override fun language(): Language = Language.CSHARP

  override fun mayContainSteps(text: CharSequence): Boolean = ATTRIBUTE.containsMatchIn(text)

  override fun parse(text: String): List<StepDefinitionInfo> {
    val steps = ArrayList<StepDefinitionInfo>()
    for (attribute in ATTRIBUTE.findAll(text)) {
      if (Literals.inComment(text, attribute.range.first, "//", true, false, false)) {
        continue
      }
      val literal = Literals.cSharp(text, attribute.range.last + 1) ?: continue
      val pattern = literal.value
      val regex = REGEX_HINT.containsMatchIn(pattern)
      val name = attribute.groupValues[1]
      steps.add(
          StepDefinitionInfo.of(
              Language.CSHARP,
              if (name == "StepDefinition") "step" else name.lowercase(Locale.ROOT),
              if (regex) PatternKind.REGEX else PatternKind.CUCUMBER_EXPRESSION,
              pattern,
              if (regex) Patterns.fullMatch(pattern) else Patterns.cucumberExpression(pattern),
              literal.start))
    }
    return steps
  }

  override fun snippet(
      request: Snippets.Request,
      fileText: String,
      fileName: String
  ): Snippets.Snippet {
    val parameters = ArrayList<String>()
    val names = Snippets.argumentNames(request)
    val types = request.parameterTypes()
    for (i in names.indices) {
      val type =
          when (types[i]) {
            Snippets.PartType.INT -> "int"
            Snippets.PartType.FLOAT -> "double"
            else -> "string"
          }
      parameters.add(type + " " + names[i])
    }
    if (request.docString) {
      parameters.add("string multilineText")
    }
    if (request.dataTable) {
      parameters.add("DataTable dataTable")
    }
    val blockNamespace = BLOCK_NAMESPACE.containsMatchIn(fileText)
    val indent = if (blockNamespace) "        " else "    "
    val code =
        indent +
            "[" +
            Snippets.capitalized(request.keyword) +
            "(\"" +
            Snippets.escape(Snippets.cucumberExpression(request), '"') +
            "\")]\n" +
            indent +
            "public void " +
            Snippets.pascalName(request) +
            "(" +
            parameters.joinToString(", ") +
            ")\n" +
            indent +
            "{\n" +
            indent +
            "    throw new PendingStepException();\n" +
            indent +
            "}\n"
    val offset = Snippets.beforeClosingBrace(fileText, if (blockNamespace) 2 else 1)
    return Snippets.Snippet.of(Snippets.separated(fileText, offset, code), offset)
  }
}
