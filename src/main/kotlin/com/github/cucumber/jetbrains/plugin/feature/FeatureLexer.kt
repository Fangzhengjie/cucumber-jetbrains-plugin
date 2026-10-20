package com.github.cucumber.jetbrains.plugin.feature

import com.github.cucumber.jetbrains.plugin.core.Dialect
import com.github.cucumber.jetbrains.plugin.core.GherkinDialects
import com.intellij.lexer.LexerBase
import com.intellij.lexer.RestartableLexer
import com.intellij.lexer.TokenIterator
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import java.util.regex.Pattern

/**
 * Token-level Gherkin lexer. The lexer works line by line; only the first token of a line carries a
 * line state ([NORMAL], [IN_DOC_QUOTES] or [IN_DOC_BACKTICKS]), every other token reports
 * [MID_LINE]; incremental highlighting restarts only at [NORMAL] line starts. A doc string closes
 * only on the delimiter that opened it. Keywords come from the dialect of the `# language:`
 * header; its index is part of every line state, so changing the header changes the state of the
 * first line and the highlighter re-lexes the rest of the file.
 */
internal class FeatureLexer : LexerBase(), RestartableLexer {

  private data class Token(val start: Int, val end: Int, val type: IElementType)

  private var buffer: CharSequence = ""
  private var bufferEnd = 0
  private var lineStart = 0
  /** [NORMAL], [IN_DOC_QUOTES] or [IN_DOC_BACKTICKS]. */
  private var mode = NORMAL
  private var lineState = 0
  private val tokens = ArrayList<Token>()
  private var index = 0
  private var dialect: Dialect = GherkinDialects.ENGLISH

  override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
    this.buffer = buffer
    this.bufferEnd = endOffset
    this.lineStart = startOffset
    val initialMode = initialState and MODE_MASK
    this.mode = if (initialMode == MID_LINE) NORMAL else initialMode
    this.dialect = GherkinDialects.dialectFor(buffer)
    tokens.clear()
    index = 0
    lexLine()
  }

  override fun start(
      buffer: CharSequence,
      startOffset: Int,
      endOffset: Int,
      initialState: Int,
      tokenIterator: TokenIterator?,
  ) {
    start(buffer, startOffset, endOffset, initialState)
  }

  override fun getStartState(): Int = NORMAL

  override fun isRestartableState(state: Int): Boolean = (state and MODE_MASK) == NORMAL

  private fun lexLine() {
    tokens.clear()
    index = 0
    lineState = mode or (dialect.index shl DIALECT_SHIFT)
    if (lineStart >= bufferEnd) {
      return
    }
    var lineEnd = lineStart
    while (lineEnd < bufferEnd && buffer[lineEnd] != '\n') {
      lineEnd++
    }
    val next = if (lineEnd < bufferEnd) lineEnd + 1 else lineEnd
    var contentEnd = lineEnd
    while (contentEnd > lineStart && Character.isWhitespace(buffer[contentEnd - 1])) {
      contentEnd--
    }
    var contentStart = lineStart
    while (contentStart < contentEnd && Character.isWhitespace(buffer[contentStart])) {
      contentStart++
    }
    add(lineStart, contentStart, TokenType.WHITE_SPACE)
    if (contentStart < contentEnd) {
      lexContent(contentStart, contentEnd)
    }
    add(contentEnd, next, TokenType.WHITE_SPACE)
    lineStart = next
  }

  private fun lexContent(start: Int, end: Int) {
    val content = buffer.subSequence(start, end).toString()
    if (mode != NORMAL) {
      add(start, end, FeatureTokenTypes.DOC_STRING)
      if (docStringMode(content) == mode) {
        mode = NORMAL
      }
      return
    }
    val opening = docStringMode(content)
    if (opening != NORMAL) {
      add(start, end, FeatureTokenTypes.DOC_STRING)
      mode = opening
      return
    }
    val first = content[0]
    if (first == '#') {
      add(start, end, FeatureTokenTypes.COMMENT)
      return
    }
    if (first == '@') {
      lexTags(start, end)
      return
    }
    if (first == '|') {
      lexTable(start, end)
      return
    }
    val keyword = dialect.matchSection(buffer, start, end)
    if (keyword != null) {
      val keywordEnd = keyword.end
      add(start, keywordEnd, FeatureTokenTypes.KEYWORD)
      val titleStart = skipSpaces(keywordEnd, end)
      add(keywordEnd, titleStart, TokenType.WHITE_SPACE)
      add(titleStart, end, FeatureTokenTypes.TITLE)
      return
    }
    val step = dialect.matchStep(buffer, start, end)
    if (step != null) {
      val keywordEnd = step.end
      add(start, keywordEnd, FeatureTokenTypes.STEP_KEYWORD)
      val textStart = skipSpaces(keywordEnd, end)
      add(keywordEnd, textStart, TokenType.WHITE_SPACE)
      lexStepText(textStart, end)
      return
    }
    add(start, end, FeatureTokenTypes.TEXT)
  }

  private fun lexStepText(start: Int, end: Int) {
    val matcher = STEP_PART.matcher(buffer).region(start, end)
    var cursor = start
    while (matcher.find()) {
      add(cursor, matcher.start(), FeatureTokenTypes.STEP_TEXT)
      val c = buffer[matcher.start()]
      val type =
          when (c) {
            '"' -> FeatureTokenTypes.QUOTED
            '<' -> FeatureTokenTypes.OUTLINE_PARAM
            else -> FeatureTokenTypes.NUMBER
          }
      add(matcher.start(), matcher.end(), type)
      cursor = matcher.end()
    }
    add(cursor, end, FeatureTokenTypes.STEP_TEXT)
  }

  private fun lexTags(start: Int, end: Int) {
    var cursor = start
    while (cursor < end) {
      val c = buffer[cursor]
      if (Character.isWhitespace(c)) {
        val wsEnd = skipSpaces(cursor, end)
        add(cursor, wsEnd, TokenType.WHITE_SPACE)
        cursor = wsEnd
      } else if (c == '#') {
        add(cursor, end, FeatureTokenTypes.COMMENT)
        return
      } else {
        var wordEnd = cursor
        while (wordEnd < end && !Character.isWhitespace(buffer[wordEnd])) {
          wordEnd++
        }
        add(cursor, wordEnd, if (c == '@') FeatureTokenTypes.TAG else FeatureTokenTypes.TEXT)
        cursor = wordEnd
      }
    }
  }

  private fun lexTable(start: Int, end: Int) {
    var cursor = start
    while (cursor < end) {
      if (buffer[cursor] == '|') {
        add(cursor, cursor + 1, FeatureTokenTypes.TABLE)
        cursor++
        continue
      }
      var cellEnd = cursor
      while (cellEnd < end && buffer[cellEnd] != '|') {
        cellEnd += if (buffer[cellEnd] == '\\' && cellEnd + 1 < end) 2 else 1
      }
      val textStart = skipSpaces(cursor, cellEnd)
      var textEnd = cellEnd
      while (textEnd > textStart && Character.isWhitespace(buffer[textEnd - 1])) {
        textEnd--
      }
      add(cursor, textStart, TokenType.WHITE_SPACE)
      add(textStart, textEnd, FeatureTokenTypes.TABLE_CELL)
      add(textEnd, cellEnd, TokenType.WHITE_SPACE)
      cursor = cellEnd
    }
  }

  private fun skipSpaces(from: Int, end: Int): Int {
    var position = from
    while (position < end && Character.isWhitespace(buffer[position])) {
      position++
    }
    return position
  }

  private fun add(start: Int, end: Int, type: IElementType) {
    if (start >= end) {
      return
    }
    if (tokens.isNotEmpty()) {
      val last = tokens[tokens.size - 1]
      if (last.type == type && last.end == start) {
        tokens[tokens.size - 1] = Token(last.start, end, type)
        return
      }
    }
    tokens.add(Token(start, end, type))
  }

  override fun getState(): Int = if (index == 0) lineState else MID_LINE

  override fun getTokenType(): IElementType? = if (index < tokens.size) tokens[index].type else null

  override fun getTokenStart(): Int = if (index < tokens.size) tokens[index].start else bufferEnd

  override fun getTokenEnd(): Int = if (index < tokens.size) tokens[index].end else bufferEnd

  override fun advance() {
    index++
    if (index >= tokens.size) {
      lexLine()
    }
  }

  override fun getBufferSequence(): CharSequence = buffer

  override fun getBufferEnd(): Int = bufferEnd

  companion object {
    const val NORMAL = 0
    const val IN_DOC_QUOTES = 1
    const val MID_LINE = 2
    const val IN_DOC_BACKTICKS = 3
    private const val MODE_MASK = 3
    private const val DIALECT_SHIFT = 2

    private val STEP_PART: Pattern =
        Pattern.compile(
            "\"(?:[^\"\\\\]|\\\\.)*\"?" +
                "|<[^<>\\s][^<>]*>" +
                "|(?<![\\w.])-?\\d+(?:\\.\\d+)?(?![\\w.])")

    /** The doc string mode a delimiter line opens or closes, or [NORMAL] if it is no delimiter. */
    private fun docStringMode(content: String): Int =
        when {
          content.startsWith("\"\"\"") -> IN_DOC_QUOTES
          content.startsWith("```") -> IN_DOC_BACKTICKS
          else -> NORMAL
        }
  }
}
