package io.github.fangzhengjie.cucumber.core

/** Readers for the string and regex literal syntaxes step definitions are written in. */
object Literals {

  /**
   * A literal's decoded value.
   *
   * @property start offset of the literal's first character (prefix included)
   * @property end offset just after the literal
   */
  data class Literal(val value: String, val start: Int, val end: Int)

  /** What a backslash means inside a quoted literal. */
  enum class Escapes {
    /** C, Java, JS, Go, Rust, C#: known escapes decode, others drop the backslash. */
    C_LIKE,
    /** Python: unknown escapes such as `\d` keep the backslash. */
    KEEP_UNKNOWN,
    /** Python raw / Ruby single-quoted: only `\\` and the quote are special. */
    QUOTE_ONLY,
    /** Raw: backslashes are literal and do not escape anything. */
    NONE,
  }

  private const val NO_QUOTE = '\u0000'

  @JvmStatic
  fun skipWhitespace(text: String, position: Int): Int {
    var i = position
    while (i < text.length && Character.isWhitespace(text[i])) {
      i++
    }
    return i
  }

  /** Whether `offset` is inside a source comment. */
  @JvmStatic
  fun inComment(
      text: String,
      offset: Int,
      lineMarker: String,
      blockComments: Boolean,
      nestedBlocks: Boolean,
      regexLiterals: Boolean,
  ): Boolean {
    var blockDepth = 0
    var quote = NO_QUOTE
    var quoteLength = 0
    var verbatim = false
    var i = 0
    while (i < offset) {
      if (blockDepth > 0) {
        if (nestedBlocks && text.startsWith("/*", i)) {
          blockDepth++
          i += 2
        } else if (text.startsWith("*/", i)) {
          blockDepth--
          i += 2
        } else {
          i++
        }
      } else if (quote != NO_QUOTE) {
        if (verbatim && quote == '"' && text.startsWith("\"\"", i)) {
          i += 2
        } else if (text[i] == '\\' && !verbatim && i + 1 < offset) {
          i += 2
        } else if (
            if (quoteLength == 3) text.startsWith(quote.toString().repeat(3), i)
            else text[i] == quote) {
          i += quoteLength
          quote = NO_QUOTE
          quoteLength = 0
          verbatim = false
        } else {
          i++
        }
      } else if (text.startsWith(lineMarker, i)) {
        val newline = text.indexOf('\n', i + lineMarker.length)
        if (newline < 0 || offset <= newline) {
          return true
        }
        i = newline + 1
      } else if (blockComments && text.startsWith("/*", i)) {
        blockDepth = 1
        i += 2
      } else if (regexLiterals && text.startsWith("%r", i)) {
        val percentRegex = rubyPercentRegex(text, i)
        if (percentRegex == null || percentRegex.end >= offset) {
          return false
        }
        i = percentRegex.end
      } else if (regexLiterals && text[i] == '/' && regexMayStartAt(text, i)) {
        val slashRegex = regex(text, i)
        if (slashRegex != null && slashRegex.end >= offset) {
          return false
        }
        i = slashRegex?.end ?: (i + 1)
      } else if ((text[i] == 'r' || text.startsWith("br", i)) &&
          (i == 0 || !Character.isLetterOrDigit(text[i - 1]))) {
        val rawStart = if (text[i] == 'r') i else i + 1
        var delimiter = rawStart + 1
        while (delimiter < text.length && text[delimiter] == '#') {
          delimiter++
        }
        if (delimiter < text.length && text[delimiter] == '"') {
          val terminator = "\"" + "#".repeat(delimiter - rawStart - 1)
          val end = text.indexOf(terminator, delimiter + 1)
          if (end < 0 || end + terminator.length >= offset) {
            return false
          }
          i = end + terminator.length
        } else {
          i++
        }
      } else {
        val c = text[i]
        if (c == '\'' || c == '"' || c == '`') {
          quote = c
          quoteLength = if (text.startsWith(c.toString().repeat(3), i)) 3 else 1
          verbatim = c == '"' && i > 0 && text[i - 1] == '@'
          i += quoteLength
        } else {
          i++
        }
      }
    }
    return blockDepth > 0
  }

  private fun regexMayStartAt(text: String, position: Int): Boolean {
    var previous = position - 1
    while (previous >= 0 && Character.isWhitespace(text[previous])) {
      previous--
    }
    return previous < 0 || "([{:;,=~!?&|>".indexOf(text[previous]) >= 0
  }

  /** `quote ... quote`, starting at `position`. */
  @JvmStatic
  fun quoted(
      text: String,
      position: Int,
      quote: Char,
      escapes: Escapes,
      multiline: Boolean,
  ): Literal? {
    if (position >= text.length || text[position] != quote) {
      return null
    }
    val value = StringBuilder()
    var i = position + 1
    while (i < text.length) {
      val c = text[i]
      if (c == quote) {
        return Literal(value.toString(), position, i + 1)
      }
      if (c == '\n' && !multiline) {
        return null
      }
      if (c == '\\' && escapes != Escapes.NONE && i + 1 < text.length) {
        i = escape(text, i, quote, escapes, value)
        continue
      }
      value.append(c)
      i++
    }
    return null
  }

  /** `delimiter ... delimiter` for triple-quoted strings. */
  @JvmStatic
  fun triple(text: String, position: Int, delimiter: String, escapes: Escapes): Literal? {
    if (!text.startsWith(delimiter, position)) {
      return null
    }
    val value = StringBuilder()
    var i = position + delimiter.length
    while (i < text.length) {
      if (text.startsWith(delimiter, i)) {
        return Literal(value.toString(), position, i + delimiter.length)
      }
      val c = text[i]
      if (c == '\\' && escapes != Escapes.NONE && i + 1 < text.length) {
        i = escape(text, i, delimiter[0], escapes, value)
        continue
      }
      value.append(c)
      i++
    }
    return null
  }

  /** Decodes the escape at `text[i] == '\\'`; returns the index after it. */
  private fun escape(
      text: String,
      i: Int,
      quote: Char,
      escapes: Escapes,
      value: StringBuilder,
  ): Int {
    val next = text[i + 1]
    if (next == '\n' || next == '\r') {
      // Line continuation: drop the line break and the next line's indentation.
      var j = i + 1
      while (j < text.length && (text[j] == '\n' || text[j] == '\r')) {
        j++
      }
      return if (escapes == Escapes.C_LIKE) skipWhitespace(text, j) else j
    }
    when (escapes) {
      Escapes.QUOTE_ONLY -> {
        if (next == quote || next == '\\') {
          value.append(next)
        } else {
          value.append('\\').append(next)
        }
      }
      Escapes.KEEP_UNKNOWN -> {
        val decoded = decode(next)
        value.append(decoded ?: ("\\" + next))
      }
      else -> {
        val decoded = decode(next)
        value.append(decoded ?: next.toString())
      }
    }
    return i + 2
  }

  private fun decode(c: Char): String? =
      when (c) {
        'n' -> "\n"
        't' -> "\t"
        'r' -> "\r"
        '0' -> "\u0000"
        '\\' -> "\\"
        '\'' -> "'"
        '"' -> "\""
        '`' -> "`"
        else -> null
      }

  /** Rust: `"..."` (may span lines) or raw `r"..."` / `r#"..."#`. */
  @JvmStatic
  fun rust(text: String, position: Int): Literal? {
    if (position < text.length && text[position] == 'r') {
      var i = position + 1
      var hashes = 0
      while (i < text.length && text[i] == '#') {
        hashes++
        i++
      }
      if (i >= text.length || text[i] != '"') {
        return null
      }
      val terminator = "\"" + "#".repeat(hashes)
      val end = text.indexOf(terminator, i + 1)
      return if (end < 0) null
      else Literal(text.substring(i + 1, end), position, end + terminator.length)
    }
    return quoted(text, position, '"', Escapes.C_LIKE, true)
  }

  /** Python: optional `r/u/b` prefixes, single or triple quotes. f-strings are dynamic. */
  @JvmStatic
  fun python(text: String, position: Int): Literal? {
    var i = position
    var raw = false
    while (i < text.length && i - position < 2 && "rRuUbBfF".indexOf(text[i]) >= 0) {
      val c = Character.toLowerCase(text[i])
      if (c == 'f') {
        return null
      }
      raw = raw or (c == 'r')
      i++
    }
    if (i >= text.length || (text[i] != '\'' && text[i] != '"')) {
      return null
    }
    val quote = text[i]
    val escapes = if (raw) Escapes.QUOTE_ONLY else Escapes.KEEP_UNKNOWN
    val tripleQuote = quote.toString().repeat(3)
    val literal =
        if (text.startsWith(tripleQuote, i)) triple(text, i, tripleQuote, escapes)
        else quoted(text, i, quote, escapes, false)
    return if (literal == null) null else Literal(literal.value, position, literal.end)
  }

  /** Go: interpreted `"..."` or raw `` `...` ``. */
  @JvmStatic
  fun go(text: String, position: Int): Literal? {
    if (position < text.length && text[position] == '`') {
      val end = text.indexOf('`', position + 1)
      return if (end < 0) null else Literal(text.substring(position + 1, end), position, end + 1)
    }
    return quoted(text, position, '"', Escapes.C_LIKE, false)
  }

  /** JavaScript / TypeScript: `'...'`, `"..."` or a template without `${}`. */
  @JvmStatic
  fun javaScript(text: String, position: Int): Literal? {
    if (position >= text.length) {
      return null
    }
    val c = text[position]
    if (c == '`') {
      val literal = quoted(text, position, '`', Escapes.C_LIKE, true)
      return if (literal == null || text.substring(position, literal.end).contains("\${")) null
      else literal
    }
    return if (c == '\'' || c == '"') quoted(text, position, c, Escapes.C_LIKE, false) else null
  }

  /** `/regex/flags` (JS, Ruby, Groovy slashy). The body keeps its escapes. */
  @JvmStatic
  fun regex(text: String, position: Int): Literal? {
    if (position >= text.length || text[position] != '/') {
      return null
    }
    val value = StringBuilder()
    var inClass = false
    var i = position + 1
    while (i < text.length) {
      val c = text[i]
      if (c == '\n') {
        return null
      }
      if (c == '\\' && i + 1 < text.length) {
        value.append(c).append(text[i + 1])
        i += 2
        continue
      }
      if (c == '[') {
        inClass = true
      } else if (c == ']') {
        inClass = false
      } else if (c == '/' && !inClass) {
        var end = i + 1
        while (end < text.length && Character.isLetter(text[end])) {
          end++
        }
        return Literal(value.toString(), position, end)
      }
      value.append(c)
      i++
    }
    return null
  }

  /** Ruby `%r{...}` (any bracket or punctuation delimiter). */
  @JvmStatic
  fun rubyPercentRegex(text: String, position: Int): Literal? {
    if (!text.startsWith("%r", position) || position + 2 >= text.length) {
      return null
    }
    val open = text[position + 2]
    val close =
        when (open) {
          '{' -> '}'
          '(' -> ')'
          '[' -> ']'
          '<' -> '>'
          else -> open
        }
    val value = StringBuilder()
    var depth = 0
    var i = position + 3
    while (i < text.length) {
      val c = text[i]
      if (c == '\\' && i + 1 < text.length) {
        value.append(c).append(text[i + 1])
        i += 2
        continue
      }
      if (c == open && open != close) {
        depth++
      } else if (c == close) {
        if (depth == 0) {
          var end = i + 1
          while (end < text.length && Character.isLetter(text[end])) {
            end++
          }
          return Literal(value.toString(), position, end)
        }
        depth--
      }
      value.append(c)
      i++
    }
    return null
  }

  /** Ruby: `'...'`, `"..."`, `/regex/` or `%r{...}`. */
  @JvmStatic
  fun ruby(text: String, position: Int): Literal? {
    if (position >= text.length) {
      return null
    }
    return when (text[position]) {
      '\'' -> quoted(text, position, '\'', Escapes.QUOTE_ONLY, true)
      '"' -> quoted(text, position, '"', Escapes.C_LIKE, true)
      '/' -> regex(text, position)
      '%' -> rubyPercentRegex(text, position)
      else -> null
    }
  }

  /** Java: `"..."` or a `"""` text block. */
  @JvmStatic
  fun java(text: String, position: Int): Literal? {
    if (text.startsWith("\"\"\"", position)) {
      val contentStart = text.indexOf('\n', position + 3)
      if (contentStart < 0) {
        return null
      }
      var end = contentStart
      while (true) {
        end = text.indexOf("\"\"\"", end)
        if (end < 0) {
          return null
        }
        var backslashes = 0
        while (text[end - 1 - backslashes] == '\\') {
          backslashes++
        }
        if (backslashes % 2 == 0) {
          break
        }
        end++
      }
      var content = stripIndent(text.substring(contentStart + 1, end))
      try {
        content = translateEscapes(content)
      } catch (invalidEscape: IllegalArgumentException) {
        // Not valid Java; keep the raw text.
      }
      return Literal(content, position, end + 3)
    }
    return quoted(text, position, '"', Escapes.C_LIKE, false)
  }

  /** Kotlin: `"..."` or a raw `"""..."""`. */
  @JvmStatic
  fun kotlin(text: String, position: Int): Literal? {
    if (text.startsWith("\"\"\"", position)) {
      return triple(text, position, "\"\"\"", Escapes.NONE)
    }
    return quoted(text, position, '"', Escapes.C_LIKE, false)
  }

  /** Groovy: quotes, triple quotes or `/slashy/`. */
  @JvmStatic
  fun groovy(text: String, position: Int): Literal? {
    if (position >= text.length) {
      return null
    }
    if (text.startsWith("'''", position)) {
      return triple(text, position, "'''", Escapes.C_LIKE)
    }
    if (text.startsWith("\"\"\"", position)) {
      return triple(text, position, "\"\"\"", Escapes.C_LIKE)
    }
    val c = text[position]
    if (c == '/') {
      return regex(text, position)
    }
    return if (c == '\'' || c == '"') quoted(text, position, c, Escapes.C_LIKE, false) else null
  }

  /** C#: `"..."`, verbatim `@"..."` or raw `"""..."""`; interpolated is dynamic. */
  @JvmStatic
  fun cSharp(text: String, position: Int): Literal? {
    if (text.startsWith("@\"", position)) {
      val value = StringBuilder()
      var i = position + 2
      while (i < text.length) {
        val c = text[i]
        if (c == '"') {
          if (i + 1 < text.length && text[i + 1] == '"') {
            value.append('"')
            i += 2
            continue
          }
          return Literal(value.toString(), position, i + 1)
        }
        value.append(c)
        i++
      }
      return null
    }
    if (text.startsWith("\"\"\"", position)) {
      var quotes = 0
      while (position + quotes < text.length && text[position + quotes] == '"') {
        quotes++
      }
      val delimiter = "\"".repeat(quotes)
      val end = text.indexOf(delimiter, position + quotes)
      if (end < 0) {
        return null
      }
      var content = text.substring(position + quotes, end)
      if (content.startsWith("\n") || content.startsWith("\r\n")) {
        content = strip(stripIndent(content.substring(content.indexOf('\n') + 1)))
      }
      return Literal(content, position, end + quotes)
    }
    if (position < text.length && text[position] == '$') {
      return null
    }
    return quoted(text, position, '"', Escapes.C_LIKE, false)
  }

  // java.lang.String methods that kotlin.String does not expose; called for identical semantics.
  @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
  private fun stripIndent(s: String): String = (s as java.lang.String).stripIndent()

  @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
  private fun translateEscapes(s: String): String = (s as java.lang.String).translateEscapes()

  @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
  private fun strip(s: String): String = (s as java.lang.String).strip()
}
