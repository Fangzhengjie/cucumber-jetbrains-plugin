package io.github.fangzhengjie.cucumber.core

/**
 * Line classification of Gherkin in any dialect (see [GherkinDialects]), shared by the formatter,
 * outlines, run markers and the tool window. Keywords come from the file's `# language:` header;
 * section keywords may end with a full-width `：` and Asian step keywords need no trailing space.
 */
object GherkinSyntax {

  /**
   * A section header line such as `Scenario: x` / `场景：x`: [type] is its canonical kind (never a
   * step kind), [keyword] the source keyword without colon, [keywordEnd] the index after the colon.
   */
  class Header(
      val type: GherkinKeywordType,
      val keyword: String,
      val title: String,
      val keywordEnd: Int
  )

  /** A step line: [keyword] as written (`Given`, `假如`, `*`), [text] the trimmed step text. */
  class Step(
      val type: GherkinKeywordType,
      val keyword: String,
      val text: String,
      val keywordEnd: Int
  )

  /** The dialect of a feature file given as lines (only the leading comment block is read). */
  @JvmStatic
  fun dialect(lines: List<String>): Dialect {
    val head = StringBuilder()
    for (line in lines) {
      val trimmed = line.trim()
      if (trimmed.isNotEmpty() && !isComment(trimmed.removePrefix("\uFEFF"))) {
        break
      }
      head.append(line).append('\n')
    }
    return GherkinDialects.dialectFor(head)
  }

  /** Section header of a trimmed line, e.g. `Scenario: x`. */
  @JvmStatic
  fun header(trimmed: String, dialect: Dialect): Header? {
    val match = dialect.matchSection(trimmed) ?: return null
    return Header(match.type, match.keyword, trimmed.substring(match.end).trim(), match.end)
  }

  /** Step of a trimmed line that is not a section header (check [header] first), or null. */
  @JvmStatic
  fun step(trimmed: String, dialect: Dialect): Step? {
    val match = dialect.matchStep(trimmed) ?: return null
    return Step(match.type, match.keyword, trimmed.substring(match.end).trim(), match.end)
  }

  /**
   * Lower-case English `given`/`when`/`then`/`and`/`but` (or `*`) for a step keyword of any
   * dialect.
   */
  @JvmStatic
  fun canonicalStepKeyword(keyword: String): String {
    val trimmed = keyword.trim()
    if (trimmed == "*") {
      return trimmed
    }
    return GherkinDialects.stepType(trimmed)?.stepKeyword() ?: trimmed.lowercase()
  }

  /**
   * The effective lower-case English keyword (`given` / `when` / `then`) of a step of kind `type`:
   * `And`, `But` and `*` continue `previous`, the effective keyword of the step before it in the
   * same scenario or background (null at its start, which counts as `given`).
   */
  @JvmStatic
  fun effectiveKeyword(type: GherkinKeywordType, previous: String?): String =
      if (type == GherkinKeywordType.AND || type == GherkinKeywordType.BUT) previous ?: "given"
      else type.stepKeyword()

  @JvmStatic
  fun isDocStringDelimiter(trimmed: String): Boolean =
      trimmed.startsWith("\"\"\"") || trimmed.startsWith("```")

  @JvmStatic fun isComment(trimmed: String): Boolean = trimmed.startsWith("#")

  @JvmStatic fun isTag(trimmed: String): Boolean = trimmed.startsWith("@")

  @JvmStatic fun isTableRow(trimmed: String): Boolean = trimmed.startsWith("|")

  /** Index after a table that starts at [start]; comment lines between rows belong to the table. */
  @JvmStatic
  fun tableEnd(lines: List<String>, start: Int): Int {
    var end = start
    var i = start
    while (i < lines.size) {
      val trimmed = lines[i].trim()
      if (isTableRow(trimmed)) {
        i++
        end = i
      } else if (isComment(trimmed)) {
        i++
      } else {
        break
      }
    }
    return end
  }

  /**
   * Index after a doc string that opens at [start] (the closing delimiter must match the opening
   * one).
   */
  @JvmStatic
  fun docStringEnd(lines: List<String>, start: Int): Int {
    val delimiter = lines[start].trim().substring(0, 3)
    var i = start + 1
    while (i < lines.size) {
      if (lines[i].trim().startsWith(delimiter)) {
        return i + 1
      }
      i++
    }
    return lines.size
  }

  /**
   * Cells of a trimmed table row (`\` escapes the next character), or null if it lacks the final
   * `|`.
   */
  @JvmStatic
  fun cells(row: String): List<String>? {
    val cells = ArrayList<String>()
    val current = StringBuilder()
    var i = 1
    while (i < row.length) {
      val c = row[i]
      if (c == '\\' && i + 1 < row.length) {
        current.append(c).append(row[i + 1])
        i += 2
        continue
      }
      if (c == '|') {
        cells.add(current.toString().trim())
        current.setLength(0)
      } else {
        current.append(c)
      }
      i++
    }
    return if (current.isBlank()) cells else null
  }
}
