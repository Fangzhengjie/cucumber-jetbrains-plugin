package io.github.fangzhengjie.cucumber.lang

import io.github.fangzhengjie.cucumber.core.Language
import io.github.fangzhengjie.cucumber.core.LanguageAdapter
import io.github.fangzhengjie.cucumber.core.Literals
import io.github.fangzhengjie.cucumber.core.PatternKind
import io.github.fangzhengjie.cucumber.core.Patterns
import io.github.fangzhengjie.cucumber.core.Snippets
import io.github.fangzhengjie.cucumber.core.StepDefinitionInfo
import io.github.fangzhengjie.cucumber.core.StepWords
import java.util.Locale

/**
 * cucumber-jvm for Java and Kotlin (`@Given("...")` annotations and java8/Kotlin lambda
 * `Given("...") { }`) and cucumber-groovy (`Given(~/regex/) { }`).
 *
 * Strings follow cucumber-jvm: `^...` or `...$` (or `/.../`) is a regex, anything else a Cucumber
 * Expression.
 */
class JvmAdapter private constructor(private val language: Language) : LanguageAdapter {

  override fun language(): Language = language

  override fun mayContainSteps(text: CharSequence): Boolean =
      StepWords.containsCall(text.toString(), CALL_WORDS, ".@$") || ANNOTATION.containsMatchIn(text)

  override fun parse(text: String): List<StepDefinitionInfo> {
    val steps = ArrayList<StepDefinitionInfo>()
    for (syntax in listOf(ANNOTATION, CALL)) {
      for (m in syntax.findAll(text)) {
        if (!Literals.inComment(
            text, m.range.first, "//", true, false, language == Language.GROOVY)) {
          val step = step(text, m.groupValues[1], m.range.last + 1)
          if (step != null) {
            steps.add(step)
          }
        }
      }
    }
    steps.sortBy { it.offset }
    return steps
  }

  private fun step(text: String, keyword: String, start: Int): StepDefinitionInfo? {
    var position = start
    var forcedRegex = false
    if (language == Language.GROOVY && position < text.length && text[position] == '~') {
      forcedRegex = true
      position = Literals.skipWhitespace(text, position + 1)
    }
    val literal =
        when (language) {
          Language.KOTLIN -> Literals.kotlin(text, position)
          Language.GROOVY -> Literals.groovy(text, position)
          Language.SCALA -> Literals.kotlin(text, position)
          else -> Literals.java(text, position)
        } ?: return null
    var pattern = literal.value
    val slashy = text[literal.start] == '/'
    val kind: PatternKind
    if (forcedRegex || slashy || pattern.startsWith("^") || pattern.endsWith("\$")) {
      kind = PatternKind.REGEX
    } else if (pattern.length > 1 && pattern.startsWith("/") && pattern.endsWith("/")) {
      kind = PatternKind.REGEX
      pattern = pattern.substring(1, pattern.length - 1)
    } else {
      kind = PatternKind.CUCUMBER_EXPRESSION
    }
    return StepDefinitionInfo.of(
        language,
        keyword.lowercase(Locale.ROOT),
        kind,
        pattern,
        // Cucumber-JVM matches a regex against the whole step (Matcher.matches()), anchored or not.
        if (kind == PatternKind.REGEX) Patterns.fullMatch(pattern)
        else Patterns.toRegex(kind, pattern),
        literal.start)
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
      parameters.add(parameter(names[i], types[i]))
    }
    if (request.docString) {
      parameters.add(if (language == Language.KOTLIN) "docString: String" else "String docString")
    }
    if (request.dataTable) {
      parameters.add(
          if (language == Language.KOTLIN) "dataTable: io.cucumber.datatable.DataTable"
          else "io.cucumber.datatable.DataTable dataTable")
    }
    val keyword = Snippets.capitalized(request.keyword)
    val expression = Snippets.escape(Snippets.cucumberExpression(request), '"')
    return when (language) {
      Language.GROOVY -> {
        val regex = Snippets.regex(request).replace("/", "\\/")
        val code =
            keyword +
                "(~/" +
                regex +
                "/) { " +
                (if (parameters.isEmpty()) "" else parameters.joinToString(", ") + " ") +
                "->\n    throw new io.cucumber.groovy.PendingException()\n}\n"
        Snippets.Snippet.of(Snippets.separated(fileText, fileText.length, code), fileText.length)
      }
      Language.KOTLIN -> {
        val inClass = fileText.contains("class ")
        val indent = if (inClass) "    " else ""
        val code =
            indent +
                "@" +
                keyword +
                "(\"" +
                expression.replace("\$", "\\\$") +
                "\")\n" +
                indent +
                "fun " +
                Snippets.camelName(request) +
                "(" +
                parameters.joinToString(", ") +
                ") {\n" +
                indent +
                "    throw io.cucumber.java.PendingException()\n" +
                indent +
                "}\n"
        val offset = if (inClass) Snippets.beforeClosingBrace(fileText, 1) else fileText.length
        Snippets.Snippet.of(Snippets.separated(fileText, offset, code), offset)
      }
      Language.SCALA -> {
        val scalaParameters = ArrayList<String>()
        for (i in names.indices) {
          scalaParameters.add(names[i] + ": " + scalaType(types[i]))
        }
        if (request.docString) {
          scalaParameters.add("docString: String")
        }
        if (request.dataTable) {
          scalaParameters.add("dataTable: io.cucumber.datatable.DataTable")
        }
        val lambdaParameters =
            if (scalaParameters.isEmpty()) "()" else "(" + scalaParameters.joinToString(", ") + ")"
        val code =
            keyword +
                "(\"\"\"" +
                Snippets.cucumberExpression(request) +
                "\"\"\") { " +
                lambdaParameters +
                " =>\n  ???\n}\n"
        Snippets.Snippet.of(Snippets.separated(fileText, fileText.length, code), fileText.length)
      }
      else -> {
        val code =
            "    @" +
                keyword +
                "(\"" +
                expression +
                "\")\n    public void " +
                Snippets.camelName(request) +
                "(" +
                parameters.joinToString(", ") +
                ") {\n        throw new io.cucumber.java.PendingException();\n    }\n"
        val offset = Snippets.beforeClosingBrace(fileText, 1)
        Snippets.Snippet.of(Snippets.separated(fileText, offset, code), offset)
      }
    }
  }

  private fun parameter(name: String, type: Snippets.PartType): String {
    if (language == Language.KOTLIN) {
      return name +
          ": " +
          when (type) {
            Snippets.PartType.INT -> "Int"
            Snippets.PartType.FLOAT -> "Double"
            else -> "String"
          }
    }
    return when (type) {
      Snippets.PartType.INT -> "int"
      Snippets.PartType.FLOAT -> "double"
      else -> "String"
    } + " " + name
  }

  companion object {
    @JvmField val JAVA = JvmAdapter(Language.JAVA)
    @JvmField val KOTLIN = JvmAdapter(Language.KOTLIN)
    @JvmField val GROOVY = JvmAdapter(Language.GROOVY)
    @JvmField val SCALA = JvmAdapter(Language.SCALA)

    private val ANNOTATION =
        Regex("""@(?:[\w.]*\.)?(Given|When|Then|And|But|Step)\s*\(\s*(?:value\s*=\s*)?""")
    private val CALL = Regex("""(?<![\w.@${'$'}])(Given|When|Then|And|But)\s*\(\s*""")
    // CALL's keywords and excluded preceding characters, for the fast StepWords.containsCall.
    private val CALL_WORDS = listOf("Given", "When", "Then", "And", "But")

    private fun scalaType(type: Snippets.PartType): String =
        when (type) {
          Snippets.PartType.INT -> "Int"
          Snippets.PartType.FLOAT -> "Double"
          else -> "String"
        }
  }
}
