package io.github.fangzhengjie.cucumber.core

/**
 * The words a file must contain for its language adapter to find step definitions in it.
 *
 * Every adapter's [LanguageAdapter.mayContainSteps] needs one of these as a whole word (an
 * identifier-like token, see [tokens]), so a word index (the IDE's IdIndex) can rule out files
 * without reading them. Keep this in sync with the adapters' definition patterns: SelfTest checks
 * that whenever an adapter's pre-check accepts a text, the text contains one of these words.
 */
object StepWords {

  private val JVM = setOf("Given", "When", "Then", "And", "But", "Step")

  @JvmStatic
  fun of(language: Language): Set<String> =
      when (language) {
        Language.RUST -> setOf("given", "when", "then")
        Language.GO -> setOf("Step", "Given", "When", "Then")
        Language.PYTHON -> setOf("given", "when", "then", "step", "Given", "When", "Then", "Step")
        Language.JAVASCRIPT -> setOf("Given", "When", "Then", "defineStep", "Step")
        Language.JAVA,
        Language.KOTLIN,
        Language.GROOVY,
        Language.SCALA -> JVM
        Language.CPP -> setOf("GIVEN", "WHEN", "THEN", "AND", "BUT", "STEP")
        Language.CSHARP ->
            setOf(
                "Given",
                "When",
                "Then",
                "StepDefinition",
                "GivenAttribute",
                "WhenAttribute",
                "ThenAttribute",
                "StepDefinitionAttribute")
        Language.RUBY -> JVM
      }

  /** The words of all `languages`. */
  @JvmStatic
  fun of(languages: Collection<Language>): Set<String> =
      languages.flatMapTo(LinkedHashSet()) { of(it) }

  /**
   * The whole words of `text` the way a word index splits it: maximal runs of Java identifier
   * characters (letters, digits, `_`, `$`).
   */
  @JvmStatic
  fun tokens(text: CharSequence): Set<String> {
    val words = HashSet<String>()
    var start = -1
    for (i in 0..text.length) {
      val part = i < text.length && Character.isJavaIdentifierPart(text[i])
      if (part && start < 0) {
        start = i
      } else if (!part && start >= 0) {
        words.add(text.substring(start, i))
        start = -1
      }
    }
    return words
  }

  /** Whether `text` contains one of `language`'s words. */
  @JvmStatic
  fun mayContainSteps(language: Language, text: CharSequence): Boolean {
    val words = of(language)
    return tokens(text).any { it in words }
  }

  /**
   * Whether `text` has a call of one of `words`: the word, not preceded by an ASCII word character
   * or one of `notAfter`, then optional whitespace and `(`. Same result as finding
   * `(?<![\w<notAfter>])(word1|word2...)\s*\(`, an order of magnitude faster on large files.
   */
  @JvmStatic
  fun containsCall(text: String, words: List<String>, notAfter: String): Boolean {
    for (word in words) {
      var at = text.indexOf(word)
      while (at >= 0) {
        if (at == 0 || !isAsciiWord(text[at - 1]) && notAfter.indexOf(text[at - 1]) < 0) {
          var next = at + word.length
          while (next < text.length && isRegexSpace(text[next])) {
            next++
          }
          if (next < text.length && text[next] == '(') {
            return true
          }
        }
        at = text.indexOf(word, at + 1)
      }
    }
    return false
  }

  private fun isAsciiWord(c: Char): Boolean =
      c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

  private fun isRegexSpace(c: Char): Boolean =
      c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'
}
