package io.github.fangzhengjie.cucumber.core

/**
 * Cucumber tag expressions: `@a and not (@b or @c)`. Precedence (lowest first): `or`, `and`, `not`.
 * A backslash escapes `(`, `)`, `\` and whitespace inside a tag.
 */
object TagExpressions {

  sealed interface Expression {
    fun evaluate(tags: Collection<String>): Boolean
  }

  private class Tag(val name: String) : Expression {
    override fun evaluate(tags: Collection<String>): Boolean = tags.contains(name)

    override fun toString(): String = name
  }

  private class Not(val operand: Expression) : Expression {
    override fun evaluate(tags: Collection<String>): Boolean = !operand.evaluate(tags)

    override fun toString(): String = "not ($operand)"
  }

  private class And(val left: Expression, val right: Expression) : Expression {
    override fun evaluate(tags: Collection<String>): Boolean =
        left.evaluate(tags) && right.evaluate(tags)

    override fun toString(): String = "($left and $right)"
  }

  private class Or(val left: Expression, val right: Expression) : Expression {
    override fun evaluate(tags: Collection<String>): Boolean =
        left.evaluate(tags) || right.evaluate(tags)

    override fun toString(): String = "($left or $right)"
  }

  private object True : Expression {
    override fun evaluate(tags: Collection<String>): Boolean = true

    override fun toString(): String = "true"
  }

  /** Parses [text]; a blank expression matches everything. Throws [IllegalArgumentException]. */
  fun parse(text: String): Expression {
    val tokens = tokenize(text)
    if (tokens.isEmpty()) {
      return True
    }
    val parser = Parser(tokens)
    val result = parser.or()
    if (parser.position != tokens.size) {
      throw IllegalArgumentException("Unexpected '${tokens[parser.position]}' in tag expression")
    }
    return result
  }

  /** The parsed expression, or null when [text] is not a valid tag expression. */
  fun parseOrNull(text: String): Expression? =
      try {
        parse(text)
      } catch (e: IllegalArgumentException) {
        null
      }

  /**
   * Whether a filter text is meant as a tag expression: it starts with `@`, `(` or `not ` and
   * parses.
   */
  fun looksLikeExpression(text: String): Boolean {
    val trimmed = text.trim()
    if (!(trimmed.startsWith("@") || trimmed.startsWith("(") || trimmed.startsWith("not "))) {
      return false
    }
    return trimmed.contains('@') && parseOrNull(trimmed) != null
  }

  /** The tags (`@x`) an expression mentions. */
  fun tags(text: String): List<String> =
      try {
        tokenize(text).filter { it.startsWith("@") }
      } catch (e: IllegalArgumentException) {
        emptyList()
      }

  private fun tokenize(text: String): List<String> {
    val tokens = ArrayList<String>()
    val current = StringBuilder()
    var i = 0
    fun flush() {
      if (current.isNotEmpty()) {
        tokens.add(current.toString())
        current.setLength(0)
      }
    }
    while (i < text.length) {
      val c = text[i]
      when {
        c == '\\' -> {
          if (i + 1 >= text.length) {
            throw IllegalArgumentException("Tag expression ends with an escape")
          }
          current.append(text[i + 1])
          i++
        }
        c.isWhitespace() -> flush()
        c == '(' || c == ')' -> {
          flush()
          tokens.add(c.toString())
        }
        else -> current.append(c)
      }
      i++
    }
    flush()
    return tokens
  }

  private class Parser(val tokens: List<String>) {
    var position = 0

    fun or(): Expression {
      var left = and()
      while (peek() == "or") {
        position++
        left = Or(left, and())
      }
      return left
    }

    fun and(): Expression {
      var left = not()
      while (peek() == "and") {
        position++
        left = And(left, not())
      }
      return left
    }

    fun not(): Expression {
      if (peek() == "not") {
        position++
        return Not(not())
      }
      return primary()
    }

    fun primary(): Expression {
      val token = peek() ?: throw IllegalArgumentException("Incomplete tag expression")
      position++
      return when (token) {
        "(" -> {
          val inner = or()
          if (peek() != ")") {
            throw IllegalArgumentException("Missing ')' in tag expression")
          }
          position++
          inner
        }
        ")",
        "and",
        "or" -> throw IllegalArgumentException("Unexpected '$token' in tag expression")
        else -> {
          if (!token.startsWith("@")) {
            throw IllegalArgumentException("Tags must start with @: '$token'")
          }
          Tag(token)
        }
      }
    }

    private fun peek(): String? = if (position < tokens.size) tokens[position] else null
  }
}
