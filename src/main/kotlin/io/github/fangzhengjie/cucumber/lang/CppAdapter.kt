package io.github.fangzhengjie.cucumber.lang

import io.github.fangzhengjie.cucumber.core.Language
import io.github.fangzhengjie.cucumber.core.LanguageAdapter
import io.github.fangzhengjie.cucumber.core.Literals
import io.github.fangzhengjie.cucumber.core.PatternKind
import io.github.fangzhengjie.cucumber.core.Patterns
import io.github.fangzhengjie.cucumber.core.Snippets
import io.github.fangzhengjie.cucumber.core.StepDefinitionInfo
import java.util.Locale

/** Cucumber-CPP's `GIVEN("regex") { ... }` and related registration macros. */
object CppAdapter : LanguageAdapter {

  private val DEFINITION = Regex("""\b(GIVEN|WHEN|THEN|AND|BUT|STEP)\s*\(\s*""")

  override fun language(): Language = Language.CPP

  override fun mayContainSteps(text: CharSequence): Boolean = DEFINITION.containsMatchIn(text)

  override fun parse(text: String): List<StepDefinitionInfo> {
    val steps = ArrayList<StepDefinitionInfo>()
    for (definition in DEFINITION.findAll(text)) {
      if (Literals.inComment(text, definition.range.first, "//", true, false, false)) {
        continue
      }
      val literal = Literals.java(text, definition.range.last + 1) ?: continue
      val keyword = definition.groupValues[1].lowercase(Locale.ROOT)
      steps.add(
          StepDefinitionInfo.of(
              Language.CPP,
              if (keyword == "step") "step" else keyword,
              PatternKind.REGEX,
              literal.value,
              Patterns.fullMatch(literal.value),
              literal.start))
    }
    return steps
  }

  override fun snippet(request: Snippets.Request, fileText: String, fileName: String): Snippets.Snippet {
    val code =
        StringBuilder()
            .append(Snippets.capitalized(request.keyword).uppercase(Locale.ROOT))
            .append("(\"")
            .append(Snippets.escape(Snippets.regex(request), '"'))
            .append("\") {\n")
    val names = Snippets.argumentNames(request)
    val types = request.parameterTypes()
    for (i in names.indices) {
      code.append("  REGEX_PARAM(").append(cppType(types[i])).append(", ").append(names[i]).append(");\n")
    }
    if (request.docString) {
      code.append("  REGEX_PARAM(std::string, docString);\n")
    }
    if (request.dataTable) {
      code.append("  REGEX_PARAM(std::string, dataTable);\n")
    }
    code.append("  // TODO: implement this step\n}\n")
    return Snippets.Snippet.of(
        Snippets.separated(fileText, fileText.length, code.toString()), fileText.length)
  }

  private fun cppType(type: Snippets.PartType): String =
      when (type) {
        Snippets.PartType.INT -> "int"
        Snippets.PartType.FLOAT -> "double"
        else -> "std::string"
      }
}
