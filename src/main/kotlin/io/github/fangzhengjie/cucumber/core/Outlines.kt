package io.github.fangzhengjie.cucumber.core

import java.util.regex.Pattern

/**
 * Scenario Outline structure of a `.feature` text: `<param>` occurrences, Examples tables and
 * parameter substitution. All offsets are absolute offsets into the parsed text.
 */
object Outlines {

  /** A `<name>` occurrence; `start`/`end` cover the angle brackets. */
  data class Param(val name: String, val start: Int, val end: Int)

  /** A table cell; `start`/`end` cover the trimmed raw text, `text` is unescaped. */
  data class Cell(val text: String, val start: Int, val end: Int)

  data class Examples(
      val start: Int,
      val end: Int,
      val header: List<Cell>,
      val rows: List<List<Cell>>
  )

  enum class Severity {
    WARNING,
    WEAK_WARNING,
  }

  data class Problem(val start: Int, val end: Int, val message: String, val severity: Severity)

  /**
   * A Scenario / Scenario Outline from its keyword line up to the next block keyword.
   *
   * @property outlineKeyword written with a Scenario Outline keyword (`Scenario Outline:`, `场景大纲:`,
   *   ...)
   * @property params `<param>` occurrences in the title, steps, doc strings and step tables
   */
  data class Scenario(
      val outlineKeyword: Boolean,
      val keywordStart: Int,
      val keywordEnd: Int,
      val start: Int,
      val end: Int,
      val params: List<Param>,
      val examples: List<Examples>,
  ) {
    /** Gherkin treats any scenario with Examples as an outline. */
    val isOutline: Boolean
      get() = outlineKeyword || examples.isNotEmpty()

    fun contains(offset: Int): Boolean = offset in start until end

    fun headerNames(): Set<String> = examples.flatMap { e -> e.header.map { it.text } }.toSet()

    /** Values of the first Examples row (header name -> cell text). */
    fun firstRowValues(): Map<String, String> {
      val examples = examples.firstOrNull { it.rows.isNotEmpty() } ?: return emptyMap()
      val values = LinkedHashMap<String, String>()
      examples.header.forEachIndexed { i, cell ->
        val value = examples.rows[0].getOrNull(i)
        if (value != null) {
          values.putIfAbsent(cell.text, value.text)
        }
      }
      return values
    }

    /** `text` with each `<param>` replaced by its first Examples row value. */
    fun substitute(text: String): String =
        if (isOutline && text.contains('<')) Outlines.substitute(text, firstRowValues()) else text

    fun paramAt(offset: Int): Param? = params.firstOrNull { offset >= it.start && offset <= it.end }

    fun headerCellAt(offset: Int): Cell? =
        examples.flatMap { it.header }.firstOrNull { offset >= it.start && offset <= it.end }

    fun usages(name: String): List<Param> = params.filter { it.name == name }

    fun headerCells(name: String): List<Cell> =
        examples.flatMap { it.header }.filter { it.text == name }
  }

  private val PARAM: Pattern = Pattern.compile("<([^<>\\s][^<>]*)>")

  /** `<param>` occurrences in `text[from, to)`. */
  @JvmStatic
  fun params(text: CharSequence, from: Int = 0, to: Int = text.length): List<Param> {
    val result = ArrayList<Param>()
    val m = PARAM.matcher(text).region(from, to)
    while (m.find()) {
      result.add(Param(m.group(1), m.start(), m.end()))
    }
    return result
  }

  /** Replaces each `<name>` that has a value; unknown parameters stay as written. */
  @JvmStatic
  fun substitute(text: String, values: Map<String, String>): String {
    val m = PARAM.matcher(text)
    val out = StringBuilder()
    var last = 0
    while (m.find()) {
      val value = values[m.group(1)] ?: continue
      out.append(text, last, m.start()).append(value)
      last = m.end()
    }
    return out.append(text, last, text.length).toString()
  }

  /** Cells of a table row `| a | b \| c |` in `text[from, to)` (the line content). */
  @JvmStatic
  fun row(text: CharSequence, from: Int, to: Int): List<Cell> {
    val cells = ArrayList<Cell>()
    var i = from
    while (i < to && text[i] != '|') {
      i++
    }
    if (i >= to) {
      return cells
    }
    i++
    while (i < to) {
      var end = i
      while (end < to && text[end] != '|') {
        end += if (text[end] == '\\' && end + 1 < to) 2 else 1
      }
      if (end >= to) {
        break
      }
      var s = i
      var e = end
      while (s < e && Character.isWhitespace(text[s])) s++
      while (e > s && Character.isWhitespace(text[e - 1])) e--
      cells.add(Cell(unescape(text.subSequence(s, e)), s, e))
      i = end + 1
    }
    return cells
  }

  private fun unescape(cell: CharSequence): String {
    val out = StringBuilder()
    var i = 0
    while (i < cell.length) {
      val c = cell[i]
      if (c == '\\' && i + 1 < cell.length) {
        when (val next = cell[i + 1]) {
          'n' -> out.append('\n')
          '|',
          '\\' -> out.append(next)
          else -> out.append(c).append(next)
        }
        i += 2
      } else {
        out.append(c)
        i++
      }
    }
    return out.toString()
  }

  private class Builder(val outlineKeyword: Boolean, val keywordStart: Int, val keywordEnd: Int) {
    val params = ArrayList<Param>()
    val examples = ArrayList<Examples>()
    var examplesStart = -1
    var examplesEnd = -1
    var header: List<Cell>? = null
    val rows = ArrayList<List<Cell>>()

    fun closeExamples() {
      if (examplesStart >= 0) {
        examples.add(Examples(examplesStart, examplesEnd, header ?: emptyList(), rows.toList()))
      }
      examplesStart = -1
      header = null
      rows.clear()
    }

    fun build(end: Int): Scenario {
      closeExamples()
      return Scenario(
          outlineKeyword,
          keywordStart,
          keywordEnd,
          keywordStart,
          end,
          params.toList(),
          examples.toList())
    }
  }

  /** Every Scenario / Scenario Outline of a feature file (Backgrounds are not included). */
  @JvmStatic
  fun scenarios(text: CharSequence): List<Scenario> {
    val dialect = GherkinDialects.dialectFor(text)
    val result = ArrayList<Scenario>()
    var current: Builder? = null
    var docDelimiter: String? = null
    var lineStart = 0
    while (lineStart <= text.length) {
      var lineEnd = lineStart
      while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
      var s = lineStart
      var e = lineEnd
      while (s < e && Character.isWhitespace(text[s])) s++
      while (e > s && Character.isWhitespace(text[e - 1])) e--
      val content = text.subSequence(s, e).toString()
      val builder = current
      if (docDelimiter != null) {
        if (content.startsWith(docDelimiter)) {
          docDelimiter = null
        } else if (builder != null && builder.examplesStart < 0) {
          builder.params.addAll(params(text, s, e))
        }
      } else if (content.startsWith("\"\"\"") || content.startsWith("```")) {
        docDelimiter = content.substring(0, 3)
      } else if (content.isEmpty() || content.startsWith("#") || content.startsWith("@")) {
        // blank lines, comments and tags do not change the structure
      } else if (content.startsWith("|")) {
        if (builder != null) {
          if (builder.examplesStart >= 0) {
            val cells = row(text, s, e)
            if (builder.header == null) builder.header = cells else builder.rows.add(cells)
            builder.examplesEnd = e
          } else {
            builder.params.addAll(params(text, s, e))
          }
        }
      } else {
        val header = GherkinSyntax.header(content, dialect)
        if (header != null) {
          val type = header.type
          if (type == GherkinKeywordType.EXAMPLES) {
            if (builder != null) {
              builder.closeExamples()
              builder.examplesStart = s
              builder.examplesEnd = e
            }
          } else {
            if (builder != null) {
              result.add(builder.build(s))
            }
            current =
                if (type == GherkinKeywordType.SCENARIO ||
                    type == GherkinKeywordType.SCENARIO_OUTLINE) {
                  val keywordEnd = s + header.keywordEnd
                  Builder(type == GherkinKeywordType.SCENARIO_OUTLINE, s, keywordEnd).also {
                    it.params.addAll(params(text, keywordEnd, e))
                  }
                } else {
                  null
                }
          }
        } else if (builder != null &&
            builder.examplesStart < 0 &&
            GherkinSyntax.step(content, dialect) != null) {
          builder.params.addAll(params(text, s, e))
        }
      }
      lineStart = lineEnd + 1
    }
    current?.let { result.add(it.build(text.length)) }
    return result
  }

  @JvmStatic
  fun scenarioAt(scenarios: List<Scenario>, offset: Int): Scenario? =
      scenarios.lastOrNull { it.contains(offset) }

  /** Outline problems: unknown / unused parameters, missing Examples, `<x>` in plain scenarios. */
  @JvmStatic
  fun problems(scenarios: List<Scenario>): List<Problem> {
    val problems = ArrayList<Problem>()
    for (scenario in scenarios) {
      if (!scenario.isOutline) {
        for (param in scenario.params) {
          problems.add(
              Problem(
                  param.start,
                  param.end,
                  "Parameter <${param.name}> is only substituted in a Scenario Outline with Examples",
                  Severity.WEAK_WARNING))
        }
        continue
      }
      if (scenario.examples.isEmpty()) {
        problems.add(
            Problem(
                scenario.keywordStart,
                scenario.keywordEnd,
                "Scenario Outline has no Examples",
                Severity.WARNING))
        continue
      }
      val headers = scenario.headerNames()
      for (param in scenario.params) {
        if (param.name !in headers) {
          problems.add(
              Problem(
                  param.start,
                  param.end,
                  "Parameter <${param.name}> is not a column of any Examples table",
                  Severity.WARNING))
        }
      }
      val used = scenario.params.map { it.name }.toSet()
      for (cell in scenario.examples.flatMap { it.header }) {
        if (cell.text.isNotEmpty() && cell.text !in used) {
          problems.add(
              Problem(
                  cell.start,
                  cell.end,
                  "Examples column '${cell.text}' is not used in the Scenario Outline",
                  Severity.WEAK_WARNING))
        }
      }
    }
    return problems
  }
}
