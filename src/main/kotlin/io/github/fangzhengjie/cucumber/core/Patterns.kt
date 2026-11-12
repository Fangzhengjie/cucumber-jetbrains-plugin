package io.github.fangzhengjie.cucumber.core

import java.util.regex.Pattern

/** Converts each pattern dialect to a Java regex used to match feature step text. */
object Patterns {

  private val EXPRESSION_PARAMETER: Pattern = Pattern.compile("\\{([^{}]*)}")
  private val PARSE_FIELD: Pattern = Pattern.compile("(?<!\\{)\\{([^{}:]*)(?::[^{}]*)?}")
  private val REGEX_GROUP: Pattern =
      Pattern.compile("(?<!\\\\)\\((?:\\?<([A-Za-z]\\w*)>|\\?P<(\\w+)>|(?!\\?))")
  private val PYTHON_BACKREFERENCE: Pattern = Pattern.compile("\\(\\?P=(\\w+)\\)")

  @JvmStatic
  fun toRegex(kind: PatternKind, pattern: String): String =
      when (kind) {
        PatternKind.EXACT -> "^" + Pattern.quote(pattern) + "\$"
        PatternKind.CUCUMBER_EXPRESSION -> cucumberExpression(pattern)
        PatternKind.PARSE_FORMAT -> parseFormat(pattern)
        PatternKind.REGEX -> normalizeRegex(pattern)
      }

  /** Python / Go named groups in Java syntax. */
  @JvmStatic
  fun normalizeRegex(regex: String): String =
      PYTHON_BACKREFERENCE.matcher(regex.replace("(?P<", "(?<")).replaceAll("\\\\k<\$1>")

  /** A regex that must match the whole step (frameworks using full-match semantics). */
  @JvmStatic
  fun fullMatch(regex: String): String {
    val normalized = normalizeRegex(regex)
    val anchoredStart = normalized.startsWith("^") || normalized.startsWith("\\A")
    val anchoredEnd =
        (normalized.endsWith("\$") && !normalized.endsWith("\\\$")) || normalized.endsWith("\\z")
    return if (anchoredStart && anchoredEnd) normalized else "^(?:$normalized)\$"
  }

  @JvmStatic
  fun parameterNames(kind: PatternKind, pattern: String): List<String> {
    val names = ArrayList<String>()
    when (kind) {
      PatternKind.CUCUMBER_EXPRESSION -> {
        val m = EXPRESSION_PARAMETER.matcher(pattern)
        while (m.find()) {
          names.add(if (m.group(1).isEmpty()) "arg" + names.size else m.group(1))
        }
      }
      PatternKind.PARSE_FORMAT -> {
        val m = PARSE_FIELD.matcher(pattern)
        while (m.find()) {
          names.add(if (m.group(1).isEmpty()) "arg" + names.size else m.group(1))
        }
      }
      PatternKind.REGEX -> {
        val m = REGEX_GROUP.matcher(pattern)
        while (m.find()) {
          val name: String? = if (m.group(1) != null) m.group(1) else m.group(2)
          names.add(name ?: ("arg" + names.size))
        }
      }
      PatternKind.EXACT -> {}
    }
    return names
  }

  private fun expressionParameter(type: String): String =
      when (type) {
        "int",
        "byte",
        "short",
        "long",
        "biginteger" -> "(-?\\d+)"
        "float",
        "double",
        "bigdecimal" -> "(-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)"
        "word" -> "([^\\s]+)"
        "string" -> "(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*')"
        else -> "(.*)"
      }

  /** A Cucumber Expression as an anchored regex. */
  @JvmStatic
  fun cucumberExpression(expression: String): String {
    val regex = StringBuilder("^")
    val alternatives = ArrayList<String>()
    val piece = StringBuilder()
    var i = 0
    while (i < expression.length) {
      val c = expression[i]
      if (c == '\\' && i + 1 < expression.length) {
        piece.append(Pattern.quote(expression[i + 1].toString()))
        i += 2
      } else if (c == '{' && expression.indexOf('}', i) > 0) {
        val close = expression.indexOf('}', i)
        piece.append(expressionParameter(expression.substring(i + 1, close)))
        i = close + 1
      } else if (c == '(' && expression.indexOf(')', i) > 0) {
        val close = expression.indexOf(')', i)
        piece.append("(?:").append(Pattern.quote(expression.substring(i + 1, close))).append(")?")
        i = close + 1
      } else if (c == '/') {
        alternatives.add(piece.toString())
        piece.setLength(0)
        i++
      } else if (Character.isWhitespace(c)) {
        flushWord(regex, alternatives, piece)
        regex.append(Pattern.quote(c.toString()))
        i++
      } else {
        piece.append(Pattern.quote(c.toString()))
        i++
      }
    }
    flushWord(regex, alternatives, piece)
    return regex.append('$').toString()
  }

  private fun flushWord(
      regex: StringBuilder,
      alternatives: MutableList<String>,
      piece: StringBuilder,
  ) {
    if (alternatives.isEmpty()) {
      regex.append(piece)
    } else {
      alternatives.add(piece.toString())
      regex.append("(?:").append(alternatives.joinToString("|")).append(')')
      alternatives.clear()
    }
    piece.setLength(0)
  }

  private fun parseType(spec: String): String {
    val type = if (spec.isEmpty()) ' ' else spec[spec.length - 1]
    return when (type) {
      'd',
      'n',
      'b',
      'o' -> "(-?\\d+)"
      'x',
      'X' -> "([0-9a-fA-F]+)"
      'f',
      'F',
      'e',
      'E',
      'g',
      'G',
      '%' -> "(-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)"
      'w' -> "(\\w+)"
      'W' -> "(\\W+)"
      's' -> "(\\s+)"
      'S' -> "(\\S+)"
      'l' -> "([a-z]+)"
      else -> "(.+?)"
    }
  }

  /** A Python `parse` format as an anchored regex (`parse` matches the whole text). */
  @JvmStatic
  fun parseFormat(format: String): String {
    val regex = StringBuilder("^")
    var i = 0
    while (i < format.length) {
      val c = format[i]
      if ((c == '{' || c == '}') && i + 1 < format.length && format[i + 1] == c) {
        regex.append(Pattern.quote(c.toString()))
        i += 2
      } else if (c == '{' && format.indexOf('}', i) > 0) {
        val close = format.indexOf('}', i)
        val field = format.substring(i + 1, close)
        val colon = field.indexOf(':')
        regex.append(parseType(if (colon < 0) "" else field.substring(colon + 1)))
        i = close + 1
      } else {
        var j = i + 1
        while (j < format.length && format[j] != '{' && format[j] != '}') {
          j++
        }
        regex.append(Pattern.quote(format.substring(i, j)))
        i = j
      }
    }
    return regex.append('$').toString()
  }
}
