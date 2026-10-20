package com.github.cucumber.jetbrains.plugin.core

/**
 * The source of a whole step definition, for deleting it: the annotated method / function
 * (Java, Kotlin, Groovy, Scala, C#, Rust, Python, with all its annotations) or the registration
 * call statement with its block (JavaScript / TypeScript, Ruby, Go, C++ macros, java8 / Kotlin /
 * Groovy / Scala lambdas). Pure text; anything that cannot be delimited safely yields null.
 */
object StepDefinitionBlocks {

  /**
   * `[start, end)` is the definition's source; `[deleteStart, deleteEnd)` is what to remove: whole
   * lines when the definition has its lines to itself, plus one blank line that would otherwise
   * be doubled.
   */
  data class Block(val start: Int, val end: Int, val deleteStart: Int, val deleteEnd: Int) {
    fun text(source: String): String = source.substring(start, end)
  }

  private enum class Form {
    /** `@Given("...")` / `[Given("...")]` / `#[given("...")]` / `@given('...')` on a declaration. */
    ANNOTATION,
    /** `Given("...", ...)` statement, possibly followed by a block. */
    CALL,
  }

  private class Head(val regex: Regex, val form: Form)

  private val JVM_ANNOTATION =
      Head(
          Regex("""@(?:[\w.]*\.)?(Given|When|Then|And|But|Step)\s*\(\s*(?:value\s*=\s*)?"""),
          Form.ANNOTATION)
  private val JVM_CALL =
      Head(Regex("""(?<![\w.@${'$'}])(Given|When|Then|And|But)\s*\(\s*(?:~\s*)?"""), Form.CALL)

  private fun heads(language: Language): List<Head> =
      when (language) {
        Language.JAVA,
        Language.KOTLIN,
        Language.GROOVY,
        Language.SCALA -> listOf(JVM_ANNOTATION, JVM_CALL)
        Language.CSHARP ->
            listOf(
                Head(
                    Regex(
                        """\[\s*(?:[\w.]*\.)?(Given|When|Then|StepDefinition)(?:Attribute)?\s*\(\s*"""),
                    Form.ANNOTATION))
        Language.RUST ->
            listOf(
                Head(
                    Regex("""#\[\s*(given|when|then)\s*\(\s*(?:(?:expr|regex)\s*=\s*)?"""),
                    Form.ANNOTATION))
        Language.PYTHON ->
            listOf(
                Head(
                    Regex(
                        """@\s*(?:[A-Za-z_]\w*\.)*(given|when|then|step|Given|When|Then|Step)\s*\(\s*""" +
                            """(?:(?:[A-Za-z_]\w*\.)?(?:parse|cfparse|re|string)\s*\(\s*)?"""),
                    Form.ANNOTATION))
        Language.JAVASCRIPT ->
            listOf(
                Head(
                    Regex(
                        """(?<![\w${'$'}.])(?:this\.)?(Given|When|Then|defineStep|Step)\s*(?:<[^<>()]*>)?\s*\(\s*"""),
                    Form.CALL))
        Language.GO ->
            listOf(
                Head(
                    Regex("""(?<![\w.])(?:[A-Za-z_]\w*\s*\.\s*)+(Step|Given|When|Then)\s*\(\s*"""),
                    Form.CALL))
        Language.CPP ->
            listOf(Head(Regex("""(?<!\w)(GIVEN|WHEN|THEN|AND|BUT|STEP)\s*\(\s*"""), Form.CALL))
        Language.RUBY ->
            listOf(
                Head(
                    Regex("""(?<![\w.:@${'$'}])(Given|When|Then|And|But|Step)\b\s*\(?\s*"""),
                    Form.CALL))
      }

  /**
   * The block of the step definition whose pattern literal starts at `offset` (a
   * [StepDefinitionInfo.offset] parsed from `text`), or null when it cannot be delimited safely.
   */
  @JvmStatic
  fun find(language: Language, text: String, offset: Int): Block? {
    if (offset < 0 || offset >= text.length) {
      return null
    }
    val definitions =
        try {
          language.adapter().parse(text)
        } catch (e: RuntimeException) {
          return null
        }
    if (definitions.none { it.offset == offset }) {
      return null
    }
    val literal = StepRename.literalAt(language, text, offset) ?: return null
    var head: MatchResult? = null
    var form = Form.CALL
    for (candidate in heads(language)) {
      head = candidate.regex.findAll(text).lastOrNull { it.range.last + 1 == offset }
      if (head != null) {
        form = candidate.form
        break
      }
    }
    if (head == null) {
      return null
    }
    val keywordEnd = head.groups[1]!!.range.last + 1
    val depth = text.substring(keywordEnd, offset).count { it == '(' }
    val scanner = Scanner(language, text)
    val range =
        when (form) {
          Form.CALL -> call(scanner, head.range.first, literal.end, depth)
          Form.ANNOTATION -> annotated(scanner, head.range.first, literal.end, depth, definitions, offset)
        } ?: return null
    // Never take another definition along.
    if (definitions.any { it.offset != offset && it.offset >= range.first && it.offset < range.second }) {
      return null
    }
    return block(text, range.first, range.second)
  }

  /** `text` with `blocks` deleted (blocks overlapping an already deleted one are skipped). */
  @JvmStatic
  fun delete(text: String, blocks: List<Block>): String {
    val result = StringBuilder(text)
    for ((start, end) in deletions(blocks)) {
      result.delete(start, end)
    }
    return result.toString()
  }

  /**
   * The `[start, end)` ranges to delete for `blocks`, last first (so each can be applied in turn);
   * blocks overlapping an already deleted one are skipped.
   */
  @JvmStatic
  fun deletions(blocks: List<Block>): List<Pair<Int, Int>> {
    val ranges = ArrayList<Pair<Int, Int>>()
    var limit = Int.MAX_VALUE
    for (block in blocks.sortedByDescending { it.deleteStart }) {
      // Adjacent blocks may both claim the blank lines between them.
      if (block.end <= limit) {
        ranges.add(block.deleteStart to minOf(block.deleteEnd, limit))
        limit = block.deleteStart
      }
    }
    return ranges
  }

  // --- forms ----------------------------------------------------------------------------------

  private fun call(scanner: Scanner, start: Int, literalEnd: Int, depth: Int): Pair<Int, Int>? {
    val text = scanner.text
    var end = literalEnd
    if (depth > 0) {
      end = scanner.close(literalEnd, '(', ')', depth)
      if (end < 0) {
        return null
      }
      end++
    }
    when (scanner.language) {
      Language.JAVASCRIPT,
      Language.GO -> {}
      Language.CPP -> {
        val open = Literals.skipWhitespace(text, end)
        if (open >= text.length || text[open] != '{') {
          return null
        }
        end = scanner.close(open + 1, '{', '}', 1).takeIf { it >= 0 }?.plus(1) ?: return null
        return start to end
      }
      Language.RUBY -> {
        val open = skipSpaces(text, end)
        val blockEnd =
            if (open < text.length && text[open] == '{') {
              scanner.close(open + 1, '{', '}', 1).takeIf { it >= 0 }?.plus(1)
            } else if (text.startsWith("do", open) && !isWord(text, open + 2)) {
              rubyEnd(text, start, open + 2)
            } else {
              null
            }
        return if (blockEnd == null) null else start to blockEnd
      }
      else -> {
        // java8 / Kotlin / Groovy / Scala: an optional trailing lambda on the same line.
        val open = skipSpaces(text, end)
        if (open < text.length && text[open] == '{') {
          end = scanner.close(open + 1, '{', '}', 1).takeIf { it >= 0 }?.plus(1) ?: return null
        }
      }
    }
    val semicolon = skipSpaces(text, end)
    if (semicolon < text.length && text[semicolon] == ';') {
      end = semicolon + 1
    }
    return start to end
  }

  private fun annotated(
      scanner: Scanner,
      start: Int,
      literalEnd: Int,
      depth: Int,
      definitions: List<StepDefinitionInfo>,
      offset: Int,
  ): Pair<Int, Int>? {
    val text = scanner.text
    var annotationEnd = scanner.close(literalEnd, '(', ')', depth)
    if (annotationEnd < 0) {
      return null
    }
    annotationEnd++
    if (scanner.language == Language.CSHARP || scanner.language == Language.RUST) {
      val bracket = Literals.skipWhitespace(text, annotationEnd)
      if (bracket >= text.length || text[bracket] != ']') {
        return null // e.g. `[Given("a"), When("b")]`
      }
      annotationEnd = bracket + 1
    }
    // The annotations before and after this one, each starting its own line before.
    var runStart = start
    while (true) {
      val lineStart = lineStart(text, runStart)
      if (!isBlank(text, lineStart, runStart) || lineStart == 0) {
        break
      }
      val previousStart = lineStart(text, lineStart - 1)
      val first = Literals.skipWhitespace(text, previousStart)
      if (first >= lineStart) {
        break
      }
      val end = annotationAt(scanner, first)
      if (end < 0 || end > runStart || text.substring(end, runStart).isNotBlank()) {
        break
      }
      runStart = first
    }
    var declaration = Literals.skipWhitespace(text, annotationEnd)
    while (declaration < text.length) {
      val end = annotationAt(scanner, declaration)
      if (end < 0) {
        break
      }
      declaration = Literals.skipWhitespace(text, end)
    }
    if (definitions.any { it.offset != offset && it.offset >= runStart && it.offset < declaration }) {
      // The declaration has other step definitions: remove only this annotation.
      return start to annotationEnd
    }
    val end =
        if (scanner.language == Language.PYTHON) pythonDefEnd(scanner, declaration)
        else braceBodyEnd(scanner, declaration)
    return if (end < 0) null else runStart to end
  }

  /** End of the annotation / attribute / decorator starting at `position`, else -1. */
  private fun annotationAt(scanner: Scanner, position: Int): Int {
    val text = scanner.text
    if (position >= text.length) {
      return -1
    }
    return when (scanner.language) {
      Language.CSHARP ->
          if (text[position] == '[') scanner.close(position + 1, '[', ']', 1).let { if (it < 0) -1 else it + 1 }
          else -1
      Language.RUST ->
          if (text.startsWith("#[", position)) {
            scanner.close(position + 2, '[', ']', 1).let { if (it < 0) -1 else it + 1 }
          } else -1
      else -> {
        val name = ANNOTATION_NAME.matchAt(text, position) ?: return -1
        if (name.groupValues[1] == "interface") {
          return -1
        }
        val open = skipSpaces(text, name.range.last + 1)
        if (open < text.length && text[open] == '(') {
          scanner.close(open + 1, '(', ')', 1).let { if (it < 0) -1 else it + 1 }
        } else {
          name.range.last + 1
        }
      }
    }
  }

  private val ANNOTATION_NAME = Regex("""@\s*([A-Za-z_][\w.:]*)""")
  private val PYTHON_DEF = Regex("""(?:async\s+)?def\b""")

  /** End of the method / function declared at `position` (its `{ }` body), else -1. */
  private fun braceBodyEnd(scanner: Scanner, position: Int): Int {
    val text = scanner.text
    var depth = 0
    var i = position
    while (i < text.length) {
      val skipped = scanner.skip(i)
      if (skipped >= 0) {
        i = maxOf(skipped, i + 1)
        continue
      }
      val c = text[i]
      if (c == '(' || c == '[') {
        depth++
      } else if (c == ')' || c == ']') {
        depth--
      } else if (depth == 0) {
        when (c) {
          '{' -> return scanner.close(i + 1, '{', '}', 1).let { if (it < 0) -1 else it + 1 }
          ';',
          '}' -> return -1
          '=' -> {
            if (scanner.language != Language.CSHARP || !text.startsWith("=>", i)) {
              return -1 // e.g. a Kotlin expression body: no reliable end
            }
            val semicolon = scanner.find(i + 2, ';')
            return if (semicolon < 0) -1 else semicolon + 1
          }
        }
      }
      i++
    }
    return -1
  }

  /** End of the last line of the Python `def` at `position` (its indented body), else -1. */
  private fun pythonDefEnd(scanner: Scanner, position: Int): Int {
    val text = scanner.text
    if (PYTHON_DEF.matchAt(text, position) == null) {
      return -1
    }
    val lineStart = lineStart(text, position)
    if (!isBlank(text, lineStart, position)) {
      return -1
    }
    val indent = position - lineStart
    // The header ends at the `:` outside brackets.
    var depth = 0
    var i = position
    var colon = -1
    while (i < text.length) {
      val skipped = scanner.skip(i)
      if (skipped >= 0) {
        i = maxOf(skipped, i + 1)
        continue
      }
      val c = text[i]
      if (c == '(' || c == '[' || c == '{') {
        depth++
      } else if (c == ')' || c == ']' || c == '}') {
        depth--
      } else if (c == ':' && depth == 0) {
        colon = i
        break
      }
      i++
    }
    if (colon < 0) {
      return -1
    }
    val headerEnd = lineEnd(text, colon)
    val rest = text.substring(colon + 1, headerEnd).trim()
    if (rest.isNotEmpty() && !rest.startsWith("#")) {
      return headerEnd // `def f(): pass`
    }
    var end = -1
    var line = headerEnd + 1
    while (line < text.length) {
      val lineEnd = lineEnd(text, line)
      val first = skipSpaces(text, line)
      if (first < lineEnd) {
        if (first - line <= indent) {
          break
        }
        end = lineEnd
      }
      line = lineEnd + 1
    }
    return end
  }

  /** End of the `end` closing a Ruby `do` block whose `do` ends at `afterDo`, else null. */
  private fun rubyEnd(text: String, start: Int, afterDo: Int): Int? {
    val lineStart = lineStart(text, start)
    if (!isBlank(text, lineStart, start)) {
      return null
    }
    val indent = start - lineStart
    val firstLineEnd = lineEnd(text, afterDo)
    val oneLiner = RUBY_TRAILING_END.find(text.substring(afterDo, firstLineEnd))
    if (oneLiner != null) {
      return afterDo + oneLiner.range.first + 3
    }
    var line = firstLineEnd + 1
    while (line < text.length) {
      val lineEnd = lineEnd(text, line)
      val first = skipSpaces(text, line)
      if (first < lineEnd && first - line <= indent) {
        return if (text.startsWith("end", first) && !isWord(text, first + 3)) first + 3 else null
      }
      line = lineEnd + 1
    }
    return null
  }

  private val RUBY_TRAILING_END = Regex("""(?<![\w.])end\s*$""")

  // --- deletion range -------------------------------------------------------------------------

  private fun block(text: String, start: Int, end: Int): Block {
    val lineStart = lineStart(text, start)
    val lineEnd = lineEnd(text, end)
    if (!isBlank(text, lineStart, start) || !isBlank(text, end, lineEnd)) {
      return Block(start, end, start, end)
    }
    var deleteStart = lineStart
    var deleteEnd = minOf(lineEnd + 1, text.length)
    val previousBlank = deleteStart > 0 && isBlankLine(text, lineStart(text, deleteStart - 1))
    val nextBlank = deleteEnd < text.length && isBlankLine(text, deleteEnd)
    val previousOpens =
        deleteStart == 0 || text.substring(lineStart(text, deleteStart - 1), deleteStart).trimEnd().endsWith("{")
    if (nextBlank && (previousBlank || previousOpens)) {
      while (deleteEnd < text.length && isBlankLine(text, deleteEnd)) {
        deleteEnd = minOf(lineEnd(text, deleteEnd) + 1, text.length)
      }
    } else if (previousBlank) {
      val next = text.substring(deleteEnd, lineEnd(text, deleteEnd)).trim()
      if (deleteEnd >= text.length || next.startsWith("}") || next == "end") {
        while (deleteStart > 0 && isBlankLine(text, lineStart(text, deleteStart - 1))) {
          deleteStart = lineStart(text, deleteStart - 1)
        }
      }
    }
    return Block(start, end, deleteStart, deleteEnd)
  }

  // --- text helpers ---------------------------------------------------------------------------

  private fun lineStart(text: String, position: Int): Int = text.lastIndexOf('\n', position - 1) + 1

  private fun lineEnd(text: String, position: Int): Int =
      text.indexOf('\n', position).let { if (it < 0) text.length else it }

  private fun isBlank(text: String, from: Int, to: Int): Boolean =
      (from until to).all { text[it] == ' ' || text[it] == '\t' || text[it] == '\r' }

  private fun isBlankLine(text: String, lineStart: Int): Boolean =
      isBlank(text, lineStart, lineEnd(text, lineStart))

  private fun skipSpaces(text: String, position: Int): Int {
    var i = position
    while (i < text.length && (text[i] == ' ' || text[i] == '\t')) {
      i++
    }
    return i
  }

  private fun isWord(text: String, position: Int): Boolean =
      position < text.length && (Character.isLetterOrDigit(text[position]) || text[position] == '_')

  /** Bracket matching that skips the language's string literals and comments. */
  private class Scanner(val language: Language, val text: String) {

    private val hashComments = language == Language.PYTHON || language == Language.RUBY

    /** Index of the `close` that brings `depth` open brackets to zero, scanning from `from`. */
    fun close(from: Int, open: Char, close: Char, depth: Int): Int {
      var level = depth
      var i = from
      while (i < text.length) {
        val skipped = skip(i)
        if (skipped >= 0) {
          i = maxOf(skipped, i + 1)
          continue
        }
        val c = text[i]
        if (c == open) {
          level++
        } else if (c == close && --level == 0) {
          return i
        }
        i++
      }
      return -1
    }

    /** Index of the first `target` outside literals and brackets, from `from`. */
    fun find(from: Int, target: Char): Int {
      var depth = 0
      var i = from
      while (i < text.length) {
        val skipped = skip(i)
        if (skipped >= 0) {
          i = maxOf(skipped, i + 1)
          continue
        }
        val c = text[i]
        if (c == target && depth == 0) {
          return i
        } else if (c == '(' || c == '[' || c == '{') {
          depth++
        } else if (c == ')' || c == ']' || c == '}') {
          depth--
        }
        i++
      }
      return -1
    }

    /** End of the string literal or comment starting at `i`, or -1 if none starts there. */
    fun skip(i: Int): Int {
      val c = text[i]
      val next = if (i + 1 < text.length) text[i + 1] else '\u0000'
      if (hashComments) {
        if (c == '#') {
          return lineEnd(text, i)
        }
      } else if (c == '/' && next == '/') {
        return lineEnd(text, i)
      } else if (c == '/' && next == '*') {
        val end = text.indexOf("*/", i + 2)
        return if (end < 0) text.length else end + 2
      }
      if (c == '"' || (c == '\'' && language == Language.PYTHON)) {
        val triple = c.toString().repeat(3)
        if (text.startsWith(triple, i) && language in TRIPLE_QUOTES) {
          val end = text.indexOf(triple, i + 3)
          return if (end < 0) text.length else end + 3
        }
      }
      if (language == Language.CSHARP && c == '@' && next == '"') {
        var j = i + 2
        while (j < text.length) {
          if (text[j] == '"') {
            if (j + 1 < text.length && text[j + 1] == '"') {
              j += 2
              continue
            }
            return j + 1
          }
          j++
        }
        return text.length
      }
      if (language == Language.RUST && c == 'r' && (next == '"' || next == '#') && (i == 0 || !isWord(text, i - 1))) {
        var j = i + 1
        while (j < text.length && text[j] == '#') {
          j++
        }
        if (j < text.length && text[j] == '"') {
          val terminator = "\"" + "#".repeat(j - i - 1)
          val end = text.indexOf(terminator, j + 1)
          return if (end < 0) text.length else end + terminator.length
        }
        return -1
      }
      return when (c) {
        '"' -> quoted(i, '"')
        '`' ->
            when (language) {
              Language.GO -> text.indexOf('`', i + 1).let { if (it < 0) text.length else it + 1 }
              Language.JAVASCRIPT -> quoted(i, '`')
              else -> -1
            }
        '\'' ->
            when (language) {
              Language.JAVASCRIPT,
              Language.PYTHON,
              Language.RUBY,
              Language.GROOVY -> quoted(i, '\'')
              else -> characterLiteral(i)
            }
        else -> -1
      }
    }

    private fun quoted(i: Int, quote: Char): Int {
      var j = i + 1
      while (j < text.length) {
        val c = text[j]
        if (c == '\\') {
          j += 2
          continue
        }
        if (c == quote) {
          return j + 1
        }
        if (c == '\n' && quote != '`') {
          return j
        }
        j++
      }
      return text.length
    }

    /** `'x'` or `'\n'`; a lone quote (a Rust lifetime, a Scala symbol) is not a literal. */
    private fun characterLiteral(i: Int): Int {
      if (i + 2 < text.length && text[i + 1] != '\\' && text[i + 2] == '\'') {
        return i + 3
      }
      if (i + 1 < text.length && text[i + 1] == '\\') {
        val end = text.indexOf('\'', i + 2)
        if (end in (i + 2)..(i + 10)) {
          return end + 1
        }
      }
      return -1
    }
  }

  private val TRIPLE_QUOTES =
      setOf(Language.JAVA, Language.KOTLIN, Language.SCALA, Language.GROOVY, Language.PYTHON, Language.CSHARP)
}
