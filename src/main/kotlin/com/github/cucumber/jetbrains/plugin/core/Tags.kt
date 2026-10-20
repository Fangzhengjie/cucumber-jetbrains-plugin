package com.github.cucumber.jetbrains.plugin.core

/** Tags (`@smoke`) of a `.feature` text. */
object Tags {

  /** A tag occurrence; `start` is the offset of the `@`. */
  data class Tag(val name: String, val start: Int) {
    val end: Int
      get() = start + name.length
  }

  @JvmStatic
  fun find(text: CharSequence): List<Tag> {
    val tags = ArrayList<Tag>()
    var docDelimiter: String? = null
    var lineStart = 0
    while (lineStart <= text.length) {
      var lineEnd = lineStart
      while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
      var i = lineStart
      while (i < lineEnd && Character.isWhitespace(text[i])) i++
      val rest = text.subSequence(i, lineEnd).toString()
      if (docDelimiter != null) {
        if (rest.startsWith(docDelimiter)) docDelimiter = null
      } else if (rest.startsWith("\"\"\"") || rest.startsWith("```")) {
        docDelimiter = rest.substring(0, 3)
      } else if (rest.startsWith("@")) {
        while (i < lineEnd) {
          val c = text[i]
          if (Character.isWhitespace(c)) {
            i++
            continue
          }
          if (c == '#') break
          var end = i
          while (end < lineEnd && !Character.isWhitespace(text[end])) end++
          if (c == '@' && end > i + 1) {
            tags.add(Tag(text.subSequence(i, end).toString(), i))
          }
          i = end
        }
      }
      lineStart = lineEnd + 1
    }
    return tags
  }
}
