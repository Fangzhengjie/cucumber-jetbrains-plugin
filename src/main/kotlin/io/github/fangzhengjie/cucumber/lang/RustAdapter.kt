package io.github.fangzhengjie.cucumber.lang

import io.github.fangzhengjie.cucumber.core.Language
import io.github.fangzhengjie.cucumber.core.LanguageAdapter
import io.github.fangzhengjie.cucumber.core.Literals
import io.github.fangzhengjie.cucumber.core.PatternKind
import io.github.fangzhengjie.cucumber.core.Patterns
import io.github.fangzhengjie.cucumber.core.Snippets
import io.github.fangzhengjie.cucumber.core.StepDefinitionInfo

/**
 * cucumber-rs: `#[given("text")]` (matched exactly), `#[given(expr = "...")]` and `#[given(regex =
 * r"...")]`.
 */
object RustAdapter : LanguageAdapter {

  private val ATTRIBUTE = Regex("""#\[\s*(given|when|then)\s*\(""")
  private val QUALIFIER = Regex("""\s*(expr|regex)\s*=\s*""")
  private val WORLD_PARAMETER = Regex("""\w+\s*:\s*&mut\s+([A-Z]\w*)""")

  override fun language(): Language = Language.RUST

  override fun mayContainSteps(text: CharSequence): Boolean = ATTRIBUTE.containsMatchIn(text)

  override fun parse(text: String): List<StepDefinitionInfo> {
    val steps = ArrayList<StepDefinitionInfo>()
    for (attribute in ATTRIBUTE.findAll(text)) {
      if (Literals.inComment(text, attribute.range.first, "//", true, true, false)) {
        continue
      }
      var position = attribute.range.last + 1
      var kind = PatternKind.EXACT
      val qualifier = QUALIFIER.matchAt(text, position)
      if (qualifier != null) {
        kind =
            if (qualifier.groupValues[1] == "expr") PatternKind.CUCUMBER_EXPRESSION
            else PatternKind.REGEX
        position = qualifier.range.last + 1
      }
      val literal = Literals.rust(text, Literals.skipWhitespace(text, position))
      if (literal != null) {
        steps.add(
            StepDefinitionInfo.of(
                Language.RUST,
                attribute.groupValues[1],
                kind,
                literal.value,
                Patterns.toRegex(kind, literal.value),
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
    val world = WORLD_PARAMETER.find(fileText)
    val signature = StringBuilder("world: &mut ").append(world?.groupValues?.get(1) ?: "World")
    val names = Snippets.argumentNames(request)
    val types = request.parameterTypes()
    for (i in names.indices) {
      signature.append(", ").append(names[i]).append(": ").append(rustType(types[i]))
    }
    if (request.docString || request.dataTable) {
      signature.append(", step: &cucumber::gherkin::Step")
    }
    val code =
        "#[" +
            request.keyword +
            "(expr = \"" +
            Snippets.escape(Snippets.cucumberExpression(request), '"') +
            "\")]\nasync fn " +
            Snippets.snakeName(request) +
            "(" +
            signature +
            ") {\n    todo!()\n}\n"
    val main = fileText.indexOf("#[tokio::main]")
    if (main >= 0) {
      val offset = fileText.lastIndexOf('\n', main - 1) + 1
      return Snippets.Snippet.of(code + "\n", offset)
    }
    return Snippets.Snippet.of(Snippets.separated(fileText, fileText.length, code), fileText.length)
  }

  private fun rustType(type: Snippets.PartType): String =
      when (type) {
        Snippets.PartType.INT -> "i64"
        Snippets.PartType.FLOAT -> "f64"
        else -> "String"
      }
}
