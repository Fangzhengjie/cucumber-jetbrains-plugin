package com.github.cucumber.jetbrains.plugin.core

/**
 * IDE-free Gherkin formatter: normalizes indentation, trims trailing whitespace and aligns data
 * table columns. Every input line maps to exactly one output line (the line count never changes) and
 * only whitespace is changed, so the result can be applied line by line.
 */
object GherkinFormatter {

  @JvmStatic
  @JvmOverloads
  fun format(text: String, indentSize: Int = 2): String {
    val separator = if (text.contains("\r\n")) "\r\n" else "\n"
    val lines = text.split('\n').map { it.removeSuffix("\r") }
    val out = Normalizer(lines, indentSize, GherkinSyntax.dialect(lines)).run()
    return out.joinToString(separator)
  }

  /** Data table rows with aligned columns, each prefixed with [indent]; comment lines are kept. */
  private fun alignTable(rows: List<String>, indent: String): List<String> {
    val parsed = rows.map { val t = it.trim(); if (GherkinSyntax.isTableRow(t)) GherkinSyntax.cells(t) else null }
    val widths = ArrayList<Int>()
    for (cells in parsed) {
      cells?.forEachIndexed { column, cell ->
        if (column == widths.size) widths.add(0)
        widths[column] = maxOf(widths[column], cell.length)
      }
    }
    return rows.mapIndexed { i, row ->
      val cells = parsed[i]
      when {
        cells == null -> indent + row.trim()
        cells.isEmpty() -> "$indent|"
        else ->
            cells.mapIndexed { column, cell -> cell.padEnd(widths[column]) }
                .joinToString(" | ", "$indent| ", " |")
      }
    }
  }

  private fun leadingWhitespace(line: String): Int {
    var i = 0
    while (i < line.length && (line[i] == ' ' || line[i] == '\t')) {
      i++
    }
    return i
  }

  private class Normalizer(
      private val lines: List<String>,
      indentSize: Int,
      private val dialect: Dialect,
  ) {
    private val out = arrayOfNulls<String>(lines.size)
    private val unit = " ".repeat(indentSize.coerceAtLeast(0))
    private val pending = ArrayList<Int>()
    private var ruleOpen = false
    private var scenarioLevel = 1
    private var stepLevel = 2
    private var bodyLevel = 1
    private var attachmentLevel = 1
    private var lastLevel = 0

    fun run(): List<String> {
      var i = 0
      while (i < lines.size) {
        val trimmed = lines[i].trim()
        i =
            when {
              trimmed.isEmpty() -> {
                out[i] = ""
                i + 1
              }
              GherkinSyntax.isDocStringDelimiter(trimmed) -> docString(i)
              GherkinSyntax.isComment(trimmed) || GherkinSyntax.isTag(trimmed) -> {
                pending.add(i)
                i + 1
              }
              GherkinSyntax.isTableRow(trimmed) -> table(i)
              else -> {
                emit(i, levelOf(trimmed), trimmed)
                i + 1
              }
            }
      }
      flush(lastLevel)
      return out.map { it ?: "" }
    }

    private fun levelOf(trimmed: String): Int {
      val header = GherkinSyntax.header(trimmed, dialect)
      if (header != null) {
        val level =
            when (header.type) {
              GherkinKeywordType.FEATURE -> {
                ruleOpen = false
                scenarioLevel = 1
                0
              }
              GherkinKeywordType.RULE -> {
                ruleOpen = true
                scenarioLevel = 2
                1
              }
              GherkinKeywordType.EXAMPLES -> scenarioLevel + 1
              else -> {
                scenarioLevel = if (ruleOpen) 2 else 1
                scenarioLevel
              }
            }
        bodyLevel = level + 1
        stepLevel = if (header.type == GherkinKeywordType.EXAMPLES) scenarioLevel + 1 else bodyLevel
        if (header.type == GherkinKeywordType.FEATURE || header.type == GherkinKeywordType.RULE) {
          stepLevel = bodyLevel + 1
        }
        attachmentLevel = bodyLevel
        return level
      }
      if (GherkinSyntax.step(trimmed, dialect) != null) {
        attachmentLevel = stepLevel + 1
        return stepLevel
      }
      return bodyLevel
    }

    private fun emit(index: Int, level: Int, trimmed: String) {
      flush(level)
      out[index] = indent(level) + trimmed
      lastLevel = level
    }

    private fun flush(level: Int) {
      for (index in pending) {
        out[index] = indent(level) + lines[index].trim()
      }
      pending.clear()
    }

    private fun indent(level: Int): String = unit.repeat(level)

    private fun table(start: Int): Int {
      val end = GherkinSyntax.tableEnd(lines, start)
      flush(attachmentLevel)
      val aligned = alignTable(lines.subList(start, end), indent(attachmentLevel))
      for (i in aligned.indices) {
        out[start + i] = aligned[i]
      }
      lastLevel = attachmentLevel
      return end
    }

    private fun docString(start: Int): Int {
      val end = GherkinSyntax.docStringEnd(lines, start)
      flush(attachmentLevel)
      val indent = indent(attachmentLevel)
      val opening = leadingWhitespace(lines[start])
      out[start] = indent + lines[start].trim()
      for (i in start + 1 until end) {
        val line = lines[i]
        out[i] =
            when {
              line.isBlank() -> ""
              i == end - 1 && line.trim().startsWith(lines[start].trim().substring(0, 3)) ->
                  indent + line.trim()
              else -> indent + line.substring(minOf(opening, leadingWhitespace(line)))
            }
      }
      lastLevel = attachmentLevel
      return end
    }
  }
}
