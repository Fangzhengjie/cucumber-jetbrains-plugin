package io.github.fangzhengjie.cucumber.core

import java.util.Locale

/**
 * Renders step definition patterns as step text with parameter placeholders, for step completion:
 * `the user {string} sends {int} request(s)` becomes the text `the user "" sends 0 requests` (shown
 * as `the user "<string>" sends <int> request(s)`) with a placeholder inside the quotes and one on
 * `0`.
 */
object StepTextTemplates {

  /**
   * A parameter: `text.substring(start, end)` is its default value.
   *
   * @property choices the alternatives of an alternation (`on/off`, `(?:a|b)`), empty otherwise
   */
  data class Placeholder(val name: String, val start: Int, val end: Int, val choices: List<String>)

  /**
   * @property text the step text to insert (placeholders hold their default values)
   * @property display the readable form shown in the completion list
   */
  data class Template(val text: String, val display: String, val placeholders: List<Placeholder>) {

    fun defaultValue(placeholder: Placeholder): String =
        text.substring(placeholder.start, placeholder.end)
  }

  /**
   * A piece of rendered text; a placeholder when [placeholder] is set ([insert] is then the default
   * value, or null to choose one from the name and the surrounding quotes).
   */
  private class Piece(
      val insert: String?,
      val display: String,
      val placeholder: Boolean = false,
      val name: String? = null,
      val choices: List<String> = emptyList(),
      val mayBeEmpty: Boolean = true,
  ) {
    val literal: Boolean
      get() = !placeholder
  }

  private fun literal(text: String) = Piece(text, text)

  private fun optional(text: String) = Piece(text, "($text)")

  private fun parameter(name: String?, default: String?, mayBeEmpty: Boolean = true) =
      Piece(default, "", true, name, mayBeEmpty = mayBeEmpty)

  private fun choice(name: String?, alternatives: List<String>, display: String) =
      Piece(alternatives[0], display, true, name, alternatives)

  /** The template of a step definition, or null when its pattern cannot be shown as text. */
  @JvmStatic fun render(info: StepDefinitionInfo): Template? = render(info.kind, info.pattern)

  @JvmStatic
  fun render(kind: PatternKind, pattern: String): Template? {
    val pieces =
        when (kind) {
          PatternKind.EXACT -> listOf(literal(pattern))
          PatternKind.CUCUMBER_EXPRESSION -> cucumberExpression(pattern)
          PatternKind.PARSE_FORMAT -> parseFormat(pattern)
          PatternKind.REGEX -> regex(pattern)
        } ?: return null
    return build(pieces)
  }

  private fun build(pieces: List<Piece>): Template? {
    val text = StringBuilder()
    val display = StringBuilder()
    val placeholders = ArrayList<Placeholder>()
    for ((index, piece) in pieces.withIndex()) {
      if (piece.literal) {
        text.append(piece.insert)
        display.append(piece.display)
        continue
      }
      val before = text.lastOrNull()
      val after = pieces.getOrNull(index + 1)?.takeIf { it.literal }?.insert?.firstOrNull()
      val quoted = (before == '"' || before == '\'') && before == after
      val name: String
      val value: String
      if (piece.choices.isNotEmpty()) {
        name = piece.name ?: "choice"
        value = piece.insert!!
        display.append(piece.display)
      } else {
        name = piece.name ?: if (quoted) "string" else "param"
        value = piece.insert ?: if (quoted && piece.mayBeEmpty) "" else name
        display.append('<').append(name).append('>')
      }
      placeholders.add(Placeholder(name, text.length, text.length + value.length, piece.choices))
      text.append(value)
    }
    if (text.isBlank()) {
      return null
    }
    return Template(text.toString(), display.toString(), placeholders)
  }

  // --- Cucumber Expressions ------------------------------------------------------------------

  private fun expressionParameter(type: String): List<Piece> =
      when (type) {
        "int",
        "byte",
        "short",
        "long",
        "biginteger" -> listOf(parameter(type, "0"))
        "float",
        "double",
        "bigdecimal" -> listOf(parameter(type, "0.0"))
        "word" -> listOf(parameter(type, "word"))
        "string" -> listOf(literal("\""), parameter(type, ""), literal("\""))
        "" -> listOf(parameter(null, null))
        else -> listOf(parameter(type, type))
      }

  private fun cucumberExpression(expression: String): List<Piece>? {
    val pieces = ArrayList<Piece>()
    val alternatives = ArrayList<List<Piece>>()
    var word = ArrayList<Piece>()
    fun flush(): Boolean {
      if (alternatives.isEmpty()) {
        pieces.addAll(word)
      } else {
        alternatives.add(word)
        if (alternatives.any { alternative -> alternative.any { it.placeholder } }) {
          return false
        }
        if (alternatives.any { it.isEmpty() }) {
          for ((index, alternative) in alternatives.withIndex()) {
            if (index > 0) {
              pieces.add(literal("/"))
            }
            pieces.addAll(alternative)
          }
        } else {
          val texts =
              alternatives.map { alternative -> alternative.joinToString("") { it.insert!! } }
          val display =
              alternatives.joinToString("/") { alternative ->
                alternative.joinToString("") { it.display }
              }
          pieces.add(choice(null, texts, display))
        }
        alternatives.clear()
      }
      word = ArrayList()
      return true
    }
    var i = 0
    while (i < expression.length) {
      val c = expression[i]
      if (c == '\\' && i + 1 < expression.length) {
        word.add(literal(expression[i + 1].toString()))
        i += 2
      } else if (c == '{' && expression.indexOf('}', i) > 0) {
        val close = expression.indexOf('}', i)
        word.addAll(expressionParameter(expression.substring(i + 1, close).trim()))
        i = close + 1
      } else if (c == '(' && expression.indexOf(')', i) > 0) {
        val close = expression.indexOf(')', i)
        val text = expression.substring(i + 1, close).replace(Regex("\\\\(.)"), "$1")
        if (text.isNotEmpty()) {
          word.add(optional(text))
        }
        i = close + 1
      } else if (c == '/') {
        alternatives.add(word)
        word = ArrayList()
        i++
      } else if (Character.isWhitespace(c)) {
        if (!flush()) {
          return null
        }
        pieces.add(literal(c.toString()))
        i++
      } else {
        word.add(literal(c.toString()))
        i++
      }
    }
    return if (flush()) pieces else null
  }

  // --- Python parse formats ------------------------------------------------------------------

  private fun parseFormat(format: String): List<Piece> {
    val pieces = ArrayList<Piece>()
    var i = 0
    while (i < format.length) {
      val c = format[i]
      if ((c == '{' || c == '}') && i + 1 < format.length && format[i + 1] == c) {
        pieces.add(literal(c.toString()))
        i += 2
      } else if (c == '{' && format.indexOf('}', i) > 0) {
        val close = format.indexOf('}', i)
        val field = format.substring(i + 1, close)
        val colon = field.indexOf(':')
        val name = (if (colon < 0) field else field.substring(0, colon)).trim().ifEmpty { null }
        val type = if (colon < 0) "" else field.substring(colon + 1)
        pieces.add(
            when (type.lastOrNull()) {
              'd',
              'n',
              'b',
              'o' -> parameter(name ?: "int", "0")
              'f',
              'F',
              'e',
              'E',
              'g',
              'G',
              '%' -> parameter(name ?: "float", "0.0")
              'w',
              'S',
              'l' -> parameter(name ?: "word", name ?: "word")
              else -> parameter(name, null, mayBeEmpty = false)
            })
        i = close + 1
      } else {
        pieces.add(literal(c.toString()))
        i++
      }
    }
    return pieces
  }

  // --- Regular expressions -------------------------------------------------------------------

  private fun regex(pattern: String): List<Piece>? {
    var start = 0
    var end = pattern.length
    if (pattern.startsWith("^")) {
      start = 1
    } else if (pattern.startsWith("\\A")) {
      start = 2
    }
    if (end > start && pattern.endsWith("$") && !escaped(pattern, end - 1)) {
      end--
    } else if (end - 2 >= start &&
        (pattern.endsWith("\\z") || pattern.endsWith("\\Z")) &&
        !escaped(pattern, end - 2)) {
      end -= 2
    }
    val parser = RegexParser(pattern.substring(start, end))
    val alternatives = parser.alternatives() ?: return null
    if (!parser.atEnd() || alternatives.size != 1) {
      return null
    }
    return alternatives[0]
  }

  /** Whether the character at `index` is preceded by an odd number of backslashes. */
  private fun escaped(text: String, index: Int): Boolean {
    var count = 0
    var i = index - 1
    while (i >= 0 && text[i] == '\\') {
      count++
      i--
    }
    return count % 2 == 1
  }

  private val INT_GROUP = Regex("""[-+]?\??(\\d|\[0-9]|\[\\d])([+*]|\{\d+(,\d*)?})?\??""")
  private val FLOAT_GROUP = Regex(""".*(\\d|\[0-9]).*(\\\.|\[.,]|\[,.]|\[.]).*""")
  private val WORD_GROUP = Regex("""(\\w|\\S|\[\^\\s]|\[\^ ]|\[a-zA-Z]|\[A-Za-z])[+*]\??""")
  private val NAMED_GROUP = Regex("""^\?P?<([A-Za-z_]\w*)>""")

  /** The parameter for a capturing group, guessing its type from the group's regex. */
  private fun groupParameter(name: String?, body: String): Piece =
      when {
        INT_GROUP.matches(body) -> parameter(name ?: "int", "0")
        FLOAT_GROUP.matches(body) -> parameter(name ?: "float", "0.0")
        WORD_GROUP.matches(body) -> parameter(name ?: "word", name ?: "word")
        else -> parameter(name, null, mayBeEmpty = body.endsWith("*") || body.endsWith("*?"))
      }

  private class RegexParser(val p: String) {
    var i = 0

    fun atEnd() = i >= p.length

    /** Sequences separated by `|`, up to an unmatched `)` or the end; null if not renderable. */
    fun alternatives(): List<List<Piece>>? {
      val alternatives = ArrayList<List<Piece>>()
      while (true) {
        alternatives.add(sequence() ?: return null)
        if (i < p.length && p[i] == '|') {
          i++
        } else {
          return alternatives
        }
      }
    }

    private fun sequence(): List<Piece>? {
      val pieces = ArrayList<Piece>()
      while (i < p.length && p[i] != ')' && p[i] != '|') {
        val c = p[i]
        when (c) {
          '\\' -> if (!escape(pieces)) return null
          '(' -> if (!group(pieces)) return null
          '[' -> pieces.add(characterClass() ?: return null)
          '?',
          '*',
          '+' -> {
            i++
            skipLazy()
            val last = pieces.removeLastOrNull() ?: return null
            pieces.add(quantified(last, c))
          }
          '{' -> {
            val m = Regex("""\{\d+(,\d*)?}""").matchAt(p, i)
            if (m != null) {
              return null
            }
            pieces.add(literal("{"))
            i++
          }
          '.',
          '^',
          '$' -> return null
          else -> {
            pieces.add(literal(c.toString()))
            i++
          }
        }
      }
      return pieces
    }

    private fun skipLazy() {
      if (i < p.length && (p[i] == '?' || p[i] == '+')) {
        i++
      }
    }

    private fun quantified(last: Piece, quantifier: Char): Piece {
      if (last.placeholder || quantifier == '+') {
        return last
      }
      val text = last.insert!!
      if (text.isBlank()) {
        return last
      }
      return if (last.display.startsWith("(")) last else optional(text)
    }

    private fun escape(pieces: MutableList<Piece>): Boolean {
      if (i + 1 >= p.length) {
        return false
      }
      val n = p[i + 1]
      i += 2
      when (n) {
        's' -> pieces.add(literal(" "))
        'b',
        'B',
        'G' -> {}
        'Q' -> {
          val end = p.indexOf("\\E", i).let { if (it < 0) p.length else it }
          for (ch in p.substring(i, end)) {
            pieces.add(literal(ch.toString()))
          }
          i = minOf(p.length, end + 2)
        }
        'u' -> {
          val hex = p.substring(i, minOf(p.length, i + 4))
          val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16) ?: return false
          pieces.add(literal(code.toChar().toString()))
          i += 4
        }
        'x' -> {
          val hex = p.substring(i, minOf(p.length, i + 2))
          val code = hex.takeIf { it.length == 2 }?.toIntOrNull(16) ?: return false
          pieces.add(literal(code.toChar().toString()))
          i += 2
        }
        else -> {
          if (n.isLetterOrDigit()) {
            return false
          }
          pieces.add(literal(n.toString()))
        }
      }
      return true
    }

    /** A character class standing for one known character (`[.]`, `[ ]`), else null. */
    private fun characterClass(): Piece? {
      val close = classEnd(i)
      if (close < 0) {
        return null
      }
      val body = p.substring(i + 1, close)
      i = close + 1
      return when {
        body.length == 1 && body != "^" -> literal(body)
        body.length == 2 && body[0] == '\\' && !body[1].isLetterOrDigit() ->
            literal(body.substring(1))
        else -> null
      }
    }

    /** Index of the `]` closing the class opened at `open`, or -1. */
    private fun classEnd(open: Int): Int {
      var j = open + 1
      if (j < p.length && p[j] == '^') {
        j++
      }
      if (j < p.length && p[j] == ']') {
        j++
      }
      while (j < p.length) {
        when (p[j]) {
          '\\' -> j += 2
          ']' -> return j
          else -> j++
        }
      }
      return -1
    }

    /** Index of the `)` closing the group opened at `open`, or -1. */
    private fun groupEnd(open: Int): Int {
      var depth = 0
      var j = open
      while (j < p.length) {
        when (p[j]) {
          '\\' -> j++
          '[' -> {
            val close = classEnd(j)
            if (close < 0) {
              return -1
            }
            j = close
          }
          '(' -> depth++
          ')' -> {
            depth--
            if (depth == 0) {
              return j
            }
          }
        }
        j++
      }
      return -1
    }

    private fun group(pieces: MutableList<Piece>): Boolean {
      val open = i
      val close = groupEnd(open)
      if (close < 0) {
        return false
      }
      var bodyStart = open + 1
      var capturing = true
      var name: String? = null
      if (p.startsWith("?", bodyStart)) {
        val rest = p.substring(bodyStart, close)
        val named = NAMED_GROUP.find(rest)
        when {
          named != null -> {
            name = named.groupValues[1]
            bodyStart += named.value.length
          }
          rest.startsWith("?:") || rest.startsWith("?>") -> {
            capturing = false
            bodyStart += 2
          }
          rest.startsWith("?=") ||
              rest.startsWith("?!") ||
              rest.startsWith("?<=") ||
              rest.startsWith("?<!") -> {
            i = close + 1
            skipQuantifier()
            return true
          }
          Regex("""\?[a-zA-Z-]+""").matches(rest) -> {
            i = close + 1
            return true
          }
          Regex("""\?[a-zA-Z-]+:.*""", RegexOption.DOT_MATCHES_ALL).matches(rest) -> {
            capturing = false
            bodyStart += rest.indexOf(':') + 1
          }
          else -> return false
        }
      }
      val body = p.substring(bodyStart, close)
      val inner = RegexParser(body)
      val alternatives = inner.alternatives()?.takeIf { inner.atEnd() }
      i = close + 1
      val quantifier = if (i < p.length && p[i] in "?*+") p[i] else null
      skipQuantifier()
      val literalAlternatives =
          alternatives?.takeIf { all -> all.all { alternative -> alternative.all { it.literal } } }
      if (capturing) {
        val texts =
            literalAlternatives?.map { alternative -> alternative.joinToString("") { it.insert!! } }
        if (texts != null && texts.size > 1 && texts.all { it.isNotEmpty() }) {
          pieces.add(choice(name, texts, choiceDisplay(literalAlternatives)))
        } else if (texts != null && texts.size == 1 && texts[0].isNotEmpty()) {
          if (quantifier == '?' || quantifier == '*') {
            pieces.add(optional(texts[0]))
          } else {
            pieces.addAll(literalAlternatives[0])
          }
        } else {
          pieces.add(groupParameter(name, body))
        }
        return true
      }
      if (alternatives == null) {
        return false
      }
      if (literalAlternatives == null) {
        if (alternatives.size != 1) {
          return false
        }
        pieces.addAll(alternatives[0])
        return true
      }
      val nonEmpty = literalAlternatives.filter { it.isNotEmpty() }
      val texts = nonEmpty.map { alternative -> alternative.joinToString("") { it.insert!! } }
      when {
        texts.isEmpty() -> {}
        texts.size == 1 &&
            (quantifier == '?' || quantifier == '*' || nonEmpty.size < literalAlternatives.size) ->
            pieces.add(optional(texts[0]))
        texts.size == 1 -> nonEmpty[0].forEach { pieces.add(it) }
        else -> pieces.add(choice(null, texts, choiceDisplay(nonEmpty)))
      }
      return true
    }

    private fun choiceDisplay(alternatives: List<List<Piece>>): String {
      val texts = alternatives.map { alternative -> alternative.joinToString("") { it.display } }
      return if (texts.any { text -> text.any { it.isWhitespace() } })
          texts.joinToString("|", "(", ")")
      else texts.joinToString("/")
    }

    private fun skipQuantifier() {
      if (i < p.length && p[i] in "?*+") {
        i++
        skipLazy()
      }
    }
  }

  // --- Matching typed text -------------------------------------------------------------------

  private val WORD = Regex("""[\p{L}\p{N}_]+""")

  /** Whether `candidate` starts with `prefix`, ignoring case and leading whitespace. */
  @JvmStatic
  fun startsWith(candidate: String, prefix: String): Boolean =
      candidate.lowercase(Locale.ROOT).startsWith(prefix.trimStart().lowercase(Locale.ROOT))

  /**
   * Whether every word typed in `prefix` starts a word of `candidate`, in order (ignoring case), or
   * `candidate` contains `prefix`.
   */
  @JvmStatic
  fun matchesWords(candidate: String, prefix: String): Boolean {
    val typed = prefix.trim().lowercase(Locale.ROOT)
    if (typed.isEmpty()) {
      return true
    }
    val text = candidate.lowercase(Locale.ROOT)
    if (text.contains(typed)) {
      return true
    }
    val words = WORD.findAll(text).map { it.value }.toList()
    var next = 0
    for (token in WORD.findAll(typed).map { it.value }) {
      while (next < words.size && !words[next].startsWith(token)) {
        next++
      }
      if (next == words.size) {
        return false
      }
      next++
    }
    return true
  }
}
