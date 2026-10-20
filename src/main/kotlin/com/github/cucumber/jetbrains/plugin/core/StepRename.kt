package com.github.cucumber.jetbrains.plugin.core

import java.util.regex.Pattern

/**
 * Renaming a step: which part of a step definition pattern a step text change touches, the new
 * pattern, the new text of other steps using it, and the rewritten pattern literal in source.
 *
 * Only the literal parts of a pattern are renamed; a change touching a parameter, or a pattern
 * this cannot take apart safely (regex classes, quantifiers, optional text, alternatives...) is
 * refused (null).
 */
object StepRename {

  /** A literal piece of a pattern: the text it matches and how it is written in the pattern. */
  data class Text(val text: String, val source: String)

  /**
   * A pattern taken apart: `prefix texts[0] parameters[0] texts[1] ... texts[n] suffix`, with
   * `texts.size == parameters.size + 1`. Prefix and suffix are anchors that match no text.
   */
  data class Template(
      val prefix: String,
      val texts: List<Text>,
      val parameters: List<String>,
      val suffix: String,
  )

  /**
   * A literal-only rename of a step definition pattern.
   *
   * @property oldTexts the literal step text pieces before the rename (between parameters)
   * @property newTexts the literal step text pieces after the rename
   */
  data class Plan(val newPattern: String, val oldTexts: List<String>, val newTexts: List<String>)

  /** The replacement of the pattern literal `[start, end)` in the step definition source. */
  data class DefinitionEdit(
      val start: Int,
      val end: Int,
      val replacement: String,
      val plan: Plan,
  )

  private val CE_SPECIAL: Pattern = Pattern.compile("([\\\\(){}/])")
  private val REGEX_SPECIAL: Pattern = Pattern.compile("([\\\\^\$.|?*+()\\[\\]{}])")
  private val OUTLINE_PARAM: Pattern = Pattern.compile("<[^<>]+>")

  @JvmStatic
  fun template(kind: PatternKind, pattern: String): Template? =
      when (kind) {
        PatternKind.EXACT -> Template("", listOf(Text(pattern, pattern)), emptyList(), "")
        PatternKind.CUCUMBER_EXPRESSION -> cucumberExpression(pattern)
        PatternKind.PARSE_FORMAT -> parseFormat(pattern)
        PatternKind.REGEX -> regex(pattern)
      }

  /** The step text a pattern without parameters matches, or null. */
  @JvmStatic
  fun literalText(kind: PatternKind, pattern: String): String? {
    val template = template(kind, pattern) ?: return null
    return if (template.parameters.isEmpty()) template.texts[0].text else null
  }

  /** `text` written as literal text of a `kind` pattern. */
  @JvmStatic
  fun escape(kind: PatternKind, text: String): String =
      when (kind) {
        PatternKind.EXACT -> text
        PatternKind.CUCUMBER_EXPRESSION -> CE_SPECIAL.matcher(text).replaceAll("\\\\\$1")
        PatternKind.PARSE_FORMAT -> text.replace("{", "{{").replace("}", "}}")
        PatternKind.REGEX -> REGEX_SPECIAL.matcher(text).replaceAll("\\\\\$1")
      }

  private class Builder {
    val texts = ArrayList<Text>()
    val parameters = ArrayList<String>()
    val text = StringBuilder()
    val source = StringBuilder()

    fun literal(value: String, written: String) {
      text.append(value)
      source.append(written)
    }

    fun parameter(written: String) {
      texts.add(Text(text.toString(), source.toString()))
      parameters.add(written)
      text.setLength(0)
      source.setLength(0)
    }

    fun build(prefix: String, suffix: String): Template {
      val all = ArrayList(texts)
      all.add(Text(text.toString(), source.toString()))
      return Template(prefix, all, parameters, suffix)
    }
  }

  private fun cucumberExpression(pattern: String): Template? {
    val builder = Builder()
    var i = 0
    while (i < pattern.length) {
      val c = pattern[i]
      when {
        c == '\\' && i + 1 < pattern.length -> {
          builder.literal(pattern[i + 1].toString(), pattern.substring(i, i + 2))
          i += 2
        }
        c == '{' -> {
          val close = pattern.indexOf('}', i)
          if (close < 0) {
            return null
          }
          builder.parameter(pattern.substring(i, close + 1))
          i = close + 1
        }
        c == '(' || c == ')' || c == '/' || c == '}' || c == '\\' -> return null
        else -> {
          builder.literal(c.toString(), c.toString())
          i++
        }
      }
    }
    return builder.build("", "")
  }

  private fun parseFormat(pattern: String): Template? {
    val builder = Builder()
    var i = 0
    while (i < pattern.length) {
      val c = pattern[i]
      when {
        (c == '{' || c == '}') && i + 1 < pattern.length && pattern[i + 1] == c -> {
          builder.literal(c.toString(), pattern.substring(i, i + 2))
          i += 2
        }
        c == '{' -> {
          val close = pattern.indexOf('}', i)
          if (close < 0) {
            return null
          }
          builder.parameter(pattern.substring(i, close + 1))
          i = close + 1
        }
        c == '}' -> return null
        else -> {
          builder.literal(c.toString(), c.toString())
          i++
        }
      }
    }
    return builder.build("", "")
  }

  private fun regex(pattern: String): Template? {
    var start = 0
    var end = pattern.length
    var prefix = ""
    var suffix = ""
    if (pattern.startsWith("^")) {
      prefix = "^"
      start = 1
    }
    if (end > start && pattern.endsWith("\$") && !escaped(pattern, end - 1)) {
      suffix = "\$"
      end--
    }
    val builder = Builder()
    var i = start
    while (i < end) {
      val c = pattern[i]
      when {
        c == '\\' -> {
          if (i + 1 >= end || Character.isLetterOrDigit(pattern[i + 1])) {
            return null
          }
          builder.literal(pattern[i + 1].toString(), pattern.substring(i, i + 2))
          i += 2
        }
        c == '(' -> {
          val close = closingParenthesis(pattern, i, end)
          if (close < 0) {
            return null
          }
          builder.parameter(pattern.substring(i, close + 1))
          i = close + 1
        }
        "^\$.|?*+)[]{}".indexOf(c) >= 0 -> return null
        else -> {
          builder.literal(c.toString(), c.toString())
          i++
        }
      }
    }
    return builder.build(prefix, suffix)
  }

  private fun escaped(text: String, index: Int): Boolean {
    var backslashes = 0
    var i = index - 1
    while (i >= 0 && text[i] == '\\') {
      backslashes++
      i--
    }
    return backslashes % 2 == 1
  }

  private fun closingParenthesis(pattern: String, open: Int, end: Int): Int {
    var depth = 0
    var inClass = false
    var i = open
    while (i < end) {
      val c = pattern[i]
      if (c == '\\') {
        i += 2
        continue
      }
      if (inClass) {
        if (c == ']') {
          inClass = false
        }
      } else if (c == '[') {
        inClass = true
      } else if (c == '(') {
        depth++
      } else if (c == ')' && --depth == 0) {
        return i
      }
      i++
    }
    return -1
  }

  /** Spans `[start, end)` of the literal pieces of `stepText`, or null if `regex` does not cover it. */
  private fun textSpans(regex: String, stepText: String, parameters: Int): List<IntArray>? {
    val matcher =
        try {
          Pattern.compile(regex).matcher(stepText)
        } catch (invalid: RuntimeException) {
          return null
        }
    if (!matcher.find() ||
        matcher.start() != 0 ||
        matcher.end() != stepText.length ||
        matcher.groupCount() != parameters) {
      return null
    }
    val spans = ArrayList<IntArray>()
    var position = 0
    for (group in 1..matcher.groupCount()) {
      if (matcher.start(group) < position) {
        return null
      }
      spans.add(intArrayOf(position, matcher.start(group)))
      position = matcher.end(group)
    }
    spans.add(intArrayOf(position, stepText.length))
    return spans
  }

  /**
   * Spans of the literal pieces `texts` in a Scenario Outline step such as `I have <n> cukes`,
   * whose `<param>`s stand where the pattern has parameters (so `regex` cannot match it): the text
   * must read `texts[0] any texts[1] ... any texts[n]`. Null for steps without `<param>`s.
   */
  private fun outlineSpans(texts: List<String>, stepText: String): List<IntArray>? {
    if (!OUTLINE_PARAM.matcher(stepText).find()) {
      return null
    }
    val regex = StringBuilder("^")
    for (i in texts.indices) {
      if (i > 0) {
        regex.append("(.*?)")
      }
      regex.append(Pattern.quote(texts[i]))
    }
    regex.append('$')
    val matcher = Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(stepText)
    if (!matcher.matches()) {
      return null
    }
    val spans = ArrayList<IntArray>()
    var position = 0
    for (group in 1..matcher.groupCount()) {
      spans.add(intArrayOf(position, matcher.start(group)))
      position = matcher.end(group)
    }
    spans.add(intArrayOf(position, stepText.length))
    return spans
  }

  /**
   * How renaming `oldText` (a step matched by `regex`, the regex of `kind` `pattern`) to `newText`
   * changes the pattern; null when the change touches a parameter or the pattern is not simple.
   */
  @JvmStatic
  fun plan(kind: PatternKind, pattern: String, regex: String, oldText: String, newText: String): Plan? {
    if (oldText == newText || newText.isBlank()) {
      return null
    }
    val template = template(kind, pattern) ?: return null
    val spans =
        textSpans(regex, oldText, template.parameters.size)
            ?: outlineSpans(template.texts.map { it.text }, oldText)
            ?: return null
    for (i in spans.indices) {
      if (oldText.substring(spans[i][0], spans[i][1]) != template.texts[i].text) {
        return null
      }
    }
    val shortest = minOf(oldText.length, newText.length)
    var commonPrefix = 0
    while (commonPrefix < shortest && oldText[commonPrefix] == newText[commonPrefix]) {
      commonPrefix++
    }
    var commonSuffix = 0
    while (commonSuffix < shortest &&
        oldText[oldText.length - 1 - commonSuffix] == newText[newText.length - 1 - commonSuffix]) {
      commonSuffix++
    }
    // The changed region is ambiguous when prefix and suffix overlap; try both alignments.
    val alignments =
        listOf(
            commonPrefix to minOf(commonSuffix, shortest - commonPrefix),
            minOf(commonPrefix, shortest - commonSuffix) to commonSuffix)
    for ((prefix, suffix) in alignments) {
      val from = prefix
      val to = oldText.length - suffix
      val replacement = newText.substring(prefix, newText.length - suffix)
      val segment = spans.indexOfFirst { span -> span[0] <= from && to <= span[1] }
      if (segment < 0) {
        continue
      }
      val oldTexts = template.texts.map { it.text }
      val newTexts = ArrayList(oldTexts)
      val span = spans[segment]
      val old = oldTexts[segment]
      newTexts[segment] = old.substring(0, from - span[0]) + replacement + old.substring(to - span[0])
      val newPattern = StringBuilder(template.prefix)
      for (i in newTexts.indices) {
        newPattern.append(
            if (i == segment) escape(kind, newTexts[i]) else template.texts[i].source)
        if (i < template.parameters.size) {
          newPattern.append(template.parameters[i])
        }
      }
      newPattern.append(template.suffix)
      return Plan(newPattern.toString(), oldTexts, newTexts)
    }
    return null
  }

  /**
   * The new text of a step matched by the renamed definition's old `regex`: its parameter values
   * (or Scenario Outline `<param>`s) kept, the literal parts renamed. Null when `stepText` is not
   * fully matched.
   */
  @JvmStatic
  fun rewriteStep(plan: Plan, regex: String, stepText: String): String? {
    val spans =
        textSpans(regex, stepText, plan.oldTexts.size - 1)
            ?: outlineSpans(plan.oldTexts, stepText)
            ?: return null
    val result = StringBuilder()
    for (i in spans.indices) {
      if (stepText.substring(spans[i][0], spans[i][1]) != plan.oldTexts[i]) {
        return null
      }
      result.append(plan.newTexts[i])
      if (i + 1 < spans.size) {
        result.append(stepText, spans[i][1], spans[i + 1][0])
      }
    }
    return result.toString()
  }

  /** The pattern literal starting at `offset` in a `language` source text. */
  @JvmStatic
  fun literalAt(language: Language, text: String, offset: Int): Literals.Literal? {
    if (offset < 0 || offset >= text.length) {
      return null
    }
    return when (language) {
      Language.JAVA, Language.CPP -> Literals.java(text, offset)
      Language.KOTLIN, Language.SCALA -> Literals.kotlin(text, offset)
      Language.GROOVY -> Literals.groovy(text, offset)
      Language.JAVASCRIPT ->
          if (text[offset] == '/') Literals.regex(text, offset) else Literals.javaScript(text, offset)
      Language.PYTHON -> Literals.python(text, offset)
      Language.GO -> Literals.go(text, offset)
      Language.RUST -> Literals.rust(text, offset)
      Language.CSHARP -> Literals.cSharp(text, offset)
      Language.RUBY -> Literals.ruby(text, offset)
    }
  }

  /** `value` written in the same literal syntax as `original` (a literal's source), or null. */
  @JvmStatic
  fun encodeLiteral(language: Language, original: String, value: String): String? {
    if (original.isEmpty()) {
      return null
    }
    var i = 0
    var raw = false
    when (language) {
      Language.PYTHON ->
          while (i < 2 && i < original.length && "rRuUbB".indexOf(original[i]) >= 0) {
            raw = raw || original[i] == 'r' || original[i] == 'R'
            i++
          }
      Language.RUST ->
          if (original[0] == 'r') {
            val quote = original.indexOf('"')
            if (quote < 0) {
              return null
            }
            val terminator = "\"" + original.substring(1, quote)
            return if (value.contains(terminator)) null
            else original.substring(0, quote + 1) + value + terminator
          }
      Language.CSHARP ->
          if (original.startsWith("@\"")) {
            return "@\"" + value.replace("\"", "\"\"") + "\""
          } else if (original.startsWith("\"\"\"")) {
            return null
          }
      else -> {}
    }
    val prefix = original.substring(0, i)
    val rest = original.substring(i)
    if (rest.isEmpty()) {
      return null
    }
    if (rest[0] == '/') {
      if (value.contains('\n')) {
        return null
      }
      val flags = rest.substring(rest.lastIndexOf('/') + 1)
      return prefix + "/" + escapeSlashes(value) + "/" + flags
    }
    if (rest.startsWith("%r")) {
      if (rest.length < 3) {
        return null
      }
      val open = rest[2]
      val close =
          when (open) {
            '{' -> '}'
            '(' -> ')'
            '[' -> ']'
            '<' -> '>'
            else -> open
          }
      if (value.indexOf(open) >= 0 || value.indexOf(close) >= 0) {
        return null
      }
      val flags = rest.substring(rest.lastIndexOf(close) + 1)
      return "%r$open$value$close$flags"
    }
    if (rest.startsWith("\"\"\"") || rest.startsWith("'''")) {
      if (language == Language.JAVA || language == Language.CSHARP || language == Language.CPP) {
        return null
      }
      val delimiter = rest.substring(0, 3)
      if (value.contains(delimiter) || value.endsWith(delimiter[0]) || value.endsWith("\\")) {
        return null
      }
      return when {
        language == Language.KOTLIN || language == Language.SCALA ->
            if (value.contains('$') && language == Language.KOTLIN) null
            else prefix + delimiter + value + delimiter
        raw -> prefix + delimiter + value + delimiter
        language == Language.GROOVY && delimiter[0] == '"' ->
            prefix + delimiter + value.replace("\\", "\\\\").replace("$", "\\$") + delimiter
        else -> prefix + delimiter + value.replace("\\", "\\\\") + delimiter
      }
    }
    val quote = rest[0]
    if (quote == '`') {
      if (language == Language.GO) {
        return if (value.contains('`')) null else "`$value`"
      }
      return "`" + cLike(value, quote).replace("\${", "\\\${") + "`"
    }
    if (quote != '"' && quote != '\'') {
      return null
    }
    if (raw) {
      return if (value.indexOf(quote) >= 0 ||
          value.contains("\\\\") ||
          value.endsWith("\\") ||
          value.contains('\n')) null
      else prefix + quote + value + quote
    }
    if (language == Language.RUBY && quote == '\'') {
      return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
    }
    var body = cLike(value, quote)
    if (language == Language.KOTLIN || (language == Language.GROOVY && quote == '"')) {
      body = body.replace("$", "\\$")
    }
    if (language == Language.RUBY && quote == '"') {
      body = body.replace("#{", "\\#{")
    }
    return prefix + quote + body + quote
  }

  private fun cLike(value: String, quote: Char): String {
    val result = StringBuilder()
    for (c in value) {
      when (c) {
        '\\' -> result.append("\\\\")
        '\n' -> result.append("\\n")
        '\t' -> result.append("\\t")
        '\r' -> result.append("\\r")
        quote -> result.append('\\').append(c)
        else -> result.append(c)
      }
    }
    return result.toString()
  }

  /** Escapes each `/` not already escaped (regex literal bodies keep their escapes). */
  private fun escapeSlashes(value: String): String {
    val result = StringBuilder()
    var i = 0
    while (i < value.length) {
      val c = value[i]
      if (c == '\\' && i + 1 < value.length) {
        result.append(c).append(value[i + 1])
        i += 2
        continue
      }
      if (c == '/') {
        result.append('\\')
      }
      result.append(c)
      i++
    }
    return result.toString()
  }

  /**
   * Renames the literal parts of `definition` (found in `sourceText`) so that it matches `newText`
   * instead of `oldText`. The result is verified by parsing the edited source again; null when the
   * pattern or its literal cannot be rewritten safely.
   *
   * `matchText` is the concrete text `oldText` stands for: for a Scenario Outline step
   * `I have <n> cukes` it is the text with Examples values substituted (`I have 5 cukes`).
   */
  @JvmStatic
  fun renameDefinition(
      sourceText: String,
      definition: StepDefinitionInfo,
      oldText: String,
      newText: String,
      matchText: String = oldText,
  ): DefinitionEdit? {
    val plan =
        plan(definition.kind, definition.pattern, definition.regex, oldText, newText) ?: return null
    val newMatchText =
        if (matchText == oldText) newText
        else rewriteStep(plan, definition.regex, matchText) ?: return null
    val language = definition.language
    val literal = literalAt(language, sourceText, definition.offset) ?: return null
    if (literal.start != definition.offset) {
      return null
    }
    val newValue =
        when (literal.value) {
          definition.pattern -> plan.newPattern
          "/" + definition.pattern + "/" -> "/" + plan.newPattern + "/"
          else -> return null
        }
    val original = sourceText.substring(literal.start, literal.end)
    val replacement = encodeLiteral(language, original, newValue) ?: return null
    val updated =
        sourceText.substring(0, literal.start) + replacement + sourceText.substring(literal.end)
    val reparsed =
        language.adapter().parse(updated).firstOrNull { it.offset == definition.offset }
            ?: return null
    if (reparsed.pattern.replace("\\/", "/") != plan.newPattern.replace("\\/", "/") ||
        reparsed.kind != definition.kind ||
        !reparsed.matches(newMatchText) ||
        reparsed.matches(matchText)) {
      return null
    }
    return DefinitionEdit(literal.start, literal.end, replacement, plan)
  }
}
