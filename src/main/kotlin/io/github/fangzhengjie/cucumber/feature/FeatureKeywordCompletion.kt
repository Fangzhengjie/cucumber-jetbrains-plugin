package io.github.fangzhengjie.cucumber.feature

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import io.github.fangzhengjie.cucumber.core.GherkinDialects

/** Completion of `# language:` codes and of the file dialect's keywords at a line start. */
internal object FeatureKeywordCompletion {

  private val LANGUAGE_PREFIX =
      Regex("""^\s*#\s*language\s*:\s*([A-Za-z0-9_-]*)$""", RegexOption.IGNORE_CASE)
  private val WORD_PREFIX = Regex("""^\s*(\S*)$""")

  /** Adds completions; true when the caret is in a `# language:` header (nothing else applies). */
  fun addCompletions(parameters: CompletionParameters, result: CompletionResultSet): Boolean {
    val text = parameters.originalFile.viewProvider.contents
    val offset = parameters.offset.coerceIn(0, text.length)
    var lineStart = offset
    while (lineStart > 0 && text[lineStart - 1] != '\n') {
      lineStart--
    }
    val linePrefix = text.subSequence(lineStart, offset).toString()
    val language = LANGUAGE_PREFIX.find(linePrefix)
    if (language != null) {
      val matcher = result.withPrefixMatcher(language.groupValues[1]).caseInsensitive()
      for (dialect in GherkinDialects.all) {
        matcher.addElement(
            LookupElementBuilder.create(dialect.code)
                .withLookupString(dialect.name)
                .withTailText("  " + dialect.nativeName, true)
                .withTypeText(dialect.name, true))
      }
      return true
    }
    if (linePrefix.trimStart().startsWith("#")) {
      return false
    }
    val word = WORD_PREFIX.find(linePrefix) ?: return false
    val dialect = GherkinDialects.dialectFor(text)
    val matcher = result.withPrefixMatcher(word.groupValues[1]).caseInsensitive()
    for (keyword in dialect.stepKeywords()) {
      if (keyword == "*") {
        continue
      }
      val insert = if (dialect.needsSpace(keyword)) "$keyword " else keyword
      matcher.addElement(
          LookupElementBuilder.create(insert)
              .withPresentableText(keyword)
              .withBoldness(true)
              .withTypeText("step", true))
    }
    for (keyword in dialect.sectionKeywords()) {
      matcher.addElement(
          LookupElementBuilder.create("$keyword: ")
              .withPresentableText("$keyword:")
              .withTypeText("section", true))
    }
    return false
  }
}
