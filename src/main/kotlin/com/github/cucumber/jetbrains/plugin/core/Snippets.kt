package com.github.cucumber.jetbrains.plugin.core

import java.util.Locale
import java.util.regex.Pattern

/** Turns step text into the pieces every language's step-definition snippet is built from. */
object Snippets {

  enum class PartType {
    TEXT,
    STRING,
    INT,
    FLOAT,
  }

  /** A piece of step text. `STRING` text keeps its quotes. */
  data class Part(val type: PartType, val text: String)

  /**
   * What to generate.
   *
   * @property keyword `given`, `when` or `then`
   */
  data class Request(
      val keyword: String,
      val parts: List<Part>,
      val docString: Boolean,
      val dataTable: Boolean,
  ) {

    /** Parameters in order, excluding doc string / data table. */
    fun parameterTypes(): List<PartType> = parts.map { it.type }.filter { it != PartType.TEXT }
  }

  /** A text insertion. */
  data class Edit(val offset: Int, val code: String)

  /**
   * Insertions into the step-definition file; `primary` is the new step definition itself (where
   * the editor navigates afterwards).
   */
  data class Snippet(val edits: List<Edit>, val primary: Int) {

    /** `text` with every edit applied. */
    fun apply(text: String): String {
      val result = StringBuilder(text)
      edits
          .sortedByDescending { it.offset }
          .forEach { edit -> result.insert(edit.offset, edit.code) }
      return result.toString()
    }

    /** Offset of the primary insertion once every edit is applied. */
    fun navigationOffset(): Int {
      val target = edits[primary]
      var shift = 0
      for (edit in edits) {
        if (edit !== target && edit.offset <= target.offset) {
          shift += edit.code.length
        }
      }
      return target.offset + shift
    }

    companion object {
      @JvmStatic
      fun of(code: String, offset: Int): Snippet = Snippet(listOf(Edit(offset, code)), 0)
    }
  }

  private val TOKEN: Pattern =
      Pattern.compile(
          "\"[^\"]*\"|'[^']*'|(?<![\\w.])-?\\d+\\.\\d+(?![\\w.])|(?<![\\w.])-?\\d+(?![\\w.])")
  private val CUCUMBER_EXPRESSION_SPECIAL: Pattern = Pattern.compile("([\\\\(){}/])")
  private val REGEX_SPECIAL: Pattern = Pattern.compile("([\\\\^\$.|?*+()\\[\\]{}])")
  private val NON_WORD: Pattern = Pattern.compile("[^a-z0-9]+")

  @JvmStatic
  fun request(keyword: String?, stepText: String, docString: Boolean, dataTable: Boolean): Request {
    val parts = ArrayList<Part>()
    val m = TOKEN.matcher(stepText)
    var last = 0
    while (m.find()) {
      if (m.start() > last) {
        parts.add(Part(PartType.TEXT, stepText.substring(last, m.start())))
      }
      val token = m.group()
      val type =
          if (token.startsWith("\"") || token.startsWith("'")) PartType.STRING
          else if (token.contains(".")) PartType.FLOAT
          else PartType.INT
      parts.add(Part(type, token))
      last = m.end()
    }
    if (last < stepText.length) {
      parts.add(Part(PartType.TEXT, stepText.substring(last)))
    }
    return Request(normalizeKeyword(keyword), parts, docString, dataTable)
  }

  /** `given`, `when` or `then`; anything else becomes `given`. */
  @JvmStatic
  fun normalizeKeyword(keyword: String?): String {
    val k = keyword?.trim { it <= ' ' }?.lowercase(Locale.ROOT) ?: ""
    return if (k == "when" || k == "then") k else "given"
  }

  @JvmStatic
  fun capitalized(keyword: String): String =
      Character.toUpperCase(keyword[0]).toString() + keyword.substring(1)

  private fun words(request: Request): List<String> {
    val words = ArrayList<String>()
    for (part in request.parts) {
      if (part.type == PartType.TEXT) {
        for (word in NON_WORD.split(part.text.lowercase(Locale.ROOT))) {
          if (word.isNotEmpty()) {
            words.add(word)
          }
        }
      }
    }
    if (words.isEmpty() || Character.isDigit(words[0][0])) {
      words.add(0, "step")
    }
    return if (words.size > 8) words.subList(0, 8) else words
  }

  @JvmStatic fun snakeName(request: Request): String = words(request).joinToString("_")

  @JvmStatic
  fun camelName(request: Request): String {
    val pascal = pascalName(request)
    return Character.toLowerCase(pascal[0]).toString() + pascal.substring(1)
  }

  @JvmStatic
  fun pascalName(request: Request): String {
    val name = StringBuilder()
    for (word in words(request)) {
      name.append(Character.toUpperCase(word[0])).append(word.substring(1))
    }
    return name.toString()
  }

  @JvmStatic
  fun argumentNames(request: Request): List<String> {
    val names = ArrayList<String>()
    for (i in 0 until request.parameterTypes().size) {
      names.add("arg$i")
    }
    return names
  }

  @JvmStatic
  fun cucumberExpression(request: Request): String {
    val expression = StringBuilder()
    for (part in request.parts) {
      when (part.type) {
        PartType.TEXT ->
            expression.append(CUCUMBER_EXPRESSION_SPECIAL.matcher(part.text).replaceAll("\\\\\$1"))
        PartType.STRING -> expression.append("{string}")
        PartType.INT -> expression.append("{int}")
        PartType.FLOAT -> expression.append("{float}")
      }
    }
    return expression.toString()
  }

  /** An anchored regex in the common (Go, Ruby, JS, Java, Python) regex syntax. */
  @JvmStatic
  fun regex(request: Request): String {
    val regex = StringBuilder("^")
    for (part in request.parts) {
      when (part.type) {
        PartType.TEXT -> regex.append(REGEX_SPECIAL.matcher(part.text).replaceAll("\\\\\$1"))
        PartType.STRING -> {
          val quote = part.text[0]
          regex.append(quote).append("([^").append(quote).append("]*)").append(quote)
        }
        PartType.INT -> regex.append("(-?\\d+)")
        PartType.FLOAT -> regex.append("(-?\\d*\\.?\\d+)")
      }
    }
    return regex.append('$').toString()
  }

  /** A Python `parse` format with fields `arg0`, `arg1`, ... */
  @JvmStatic
  fun parseFormat(request: Request): String {
    val format = StringBuilder()
    var index = 0
    for (part in request.parts) {
      when (part.type) {
        PartType.TEXT -> format.append(part.text.replace("{", "{{").replace("}", "}}"))
        PartType.STRING -> {
          val quote = part.text[0]
          format.append(quote).append("{arg").append(index++).append('}').append(quote)
        }
        PartType.INT -> format.append("{arg").append(index++).append(":d}")
        PartType.FLOAT -> format.append("{arg").append(index++).append(":f}")
      }
    }
    return format.toString()
  }

  /** `text` escaped for a literal delimited by `quote` (backslash escapes). */
  @JvmStatic
  fun escape(text: String, quote: Char): String =
      text.replace("\\", "\\\\").replace(quote.toString(), "\\" + quote)

  /**
   * Offset of the start of the line holding the `n`-th last `}` (n = 1 is the last), or the end
   * of the text if there is none.
   */
  @JvmStatic
  fun beforeClosingBrace(text: String, n: Int): Int {
    var index = text.length
    for (i in 0 until n) {
      index = text.lastIndexOf('}', index - 1)
      if (index < 0) {
        return text.length
      }
    }
    return text.lastIndexOf('\n', index - 1) + 1
  }

  /** `code` preceded by a blank line unless `text` is empty up to `offset`. */
  @JvmStatic
  fun separated(text: String, offset: Int, code: String): String {
    val before = text.substring(0, offset)
    if (before.all { Character.isWhitespace(it) }) {
      return code
    }
    return (if (before.endsWith("\n\n")) "" else if (before.endsWith("\n")) "\n" else "\n\n") +
        code
  }
}
