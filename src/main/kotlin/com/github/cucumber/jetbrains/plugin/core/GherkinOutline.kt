package com.github.cucumber.jetbrains.plugin.core

/**
 * IDE-free outline of a feature file: the Feature / Rule / Background / Scenario / Examples / step
 * hierarchy plus doc string and data table blocks, with text offsets (used by the structure view and
 * code folding).
 */
object GherkinOutline {

  enum class Kind(val level: Int) {
    FEATURE(0),
    RULE(1),
    BACKGROUND(2),
    SCENARIO(2),
    SCENARIO_OUTLINE(2),
    EXAMPLES(3),
    STEP(4),
  }

  /**
   * A section or step. [startOffset] is the keyword start, [headerEnd] the end of the header line text,
   * [endOffset] the end of its last content line (trailing tags, comments and blank lines excluded).
   */
  class Node(
      val kind: Kind,
      val keyword: String,
      val title: String,
      val startOffset: Int,
      val headerEnd: Int,
  ) {
    var endOffset: Int = headerEnd
      internal set

    val children: MutableList<Node> = ArrayList()

    override fun toString(): String = if (title.isEmpty()) keyword else "$keyword: $title"
  }

  enum class BlockKind {
    DOC_STRING,
    TABLE,
  }

  /** A doc string or data table; [firstLineEnd] is the end of its first line text. */
  class Block(
      val kind: BlockKind,
      val startOffset: Int,
      val firstLineEnd: Int,
      val endOffset: Int,
      val firstLine: String,
  )

  class Outline(val roots: List<Node>, val blocks: List<Block>) {
    fun all(): List<Node> {
      val result = ArrayList<Node>()
      fun visit(node: Node) {
        result.add(node)
        node.children.forEach(::visit)
      }
      roots.forEach(::visit)
      return result
    }
  }

  @JvmStatic
  fun parse(text: CharSequence): Outline {
    val lines = ArrayList<String>()
    val starts = ArrayList<Int>()
    var start = 0
    for (i in 0..text.length) {
      if (i == text.length || text[i] == '\n') {
        lines.add(text.subSequence(start, i).toString())
        starts.add(start)
        start = i + 1
      }
    }
    val roots = ArrayList<Node>()
    val blocks = ArrayList<Block>()
    val dialect = GherkinSyntax.dialect(lines)
    val stack = ArrayList<Node>()
    fun textStart(i: Int): Int = starts[i] + (lines[i].length - lines[i].trimStart().length)
    fun textEnd(i: Int): Int = starts[i] + lines[i].trimEnd().length
    fun extend(i: Int) {
      val end = textEnd(i)
      for (node in stack) {
        node.endOffset = maxOf(node.endOffset, end)
      }
    }
    fun open(node: Node) {
      while (stack.isNotEmpty() && stack[stack.size - 1].kind.level >= node.kind.level) {
        stack.removeAt(stack.size - 1)
      }
      (if (stack.isEmpty()) roots else stack[stack.size - 1].children).add(node)
      stack.add(node)
    }

    var i = 0
    while (i < lines.size) {
      val trimmed = lines[i].trim()
      when {
        trimmed.isEmpty() || GherkinSyntax.isComment(trimmed) || GherkinSyntax.isTag(trimmed) -> i++
        GherkinSyntax.isDocStringDelimiter(trimmed) || GherkinSyntax.isTableRow(trimmed) -> {
          val docString = GherkinSyntax.isDocStringDelimiter(trimmed)
          val end =
              if (docString) GherkinSyntax.docStringEnd(lines, i) else GherkinSyntax.tableEnd(lines, i)
          blocks.add(
              Block(
                  if (docString) BlockKind.DOC_STRING else BlockKind.TABLE,
                  textStart(i),
                  textEnd(i),
                  maxOf(textEnd(end - 1), textEnd(i)),
                  trimmed,
              ))
          for (j in i until end) {
            extend(j)
          }
          i = end
        }
        else -> {
          val header = GherkinSyntax.header(trimmed, dialect)
          val step = if (header == null) GherkinSyntax.step(trimmed, dialect) else null
          if (header != null) {
            open(Node(kindOf(header.type), header.keyword, header.title, textStart(i), textEnd(i)))
          } else if (step != null) {
            open(Node(Kind.STEP, step.keyword, step.text, textStart(i), textEnd(i)))
          }
          extend(i)
          i++
        }
      }
    }
    return Outline(roots, blocks)
  }

  private fun kindOf(type: GherkinKeywordType): Kind =
      when (type) {
        GherkinKeywordType.FEATURE -> Kind.FEATURE
        GherkinKeywordType.RULE -> Kind.RULE
        GherkinKeywordType.BACKGROUND -> Kind.BACKGROUND
        GherkinKeywordType.SCENARIO -> Kind.SCENARIO
        GherkinKeywordType.SCENARIO_OUTLINE -> Kind.SCENARIO_OUTLINE
        GherkinKeywordType.EXAMPLES -> Kind.EXAMPLES
        else -> Kind.STEP
      }
}
