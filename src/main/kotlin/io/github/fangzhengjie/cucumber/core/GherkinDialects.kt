package io.github.fangzhengjie.cucumber.core

/** Canonical Gherkin keyword kinds; [stepKeyword] is the lower-case English step keyword. */
enum class GherkinKeywordType(val english: String, val isStep: Boolean) {
  FEATURE("Feature", false),
  BACKGROUND("Background", false),
  RULE("Rule", false),
  SCENARIO("Scenario", false),
  SCENARIO_OUTLINE("Scenario Outline", false),
  EXAMPLES("Examples", false),
  GIVEN("Given", true),
  WHEN("When", true),
  THEN("Then", true),
  AND("And", true),
  BUT("But", true);

  fun stepKeyword(): String = english.lowercase()
}

/**
 * A keyword found at a line start: [keyword] is the source text (without trailing space or colon),
 * [end] the offset right after the keyword (for section keywords: after the colon).
 */
data class GherkinKeywordMatch(
    val keyword: String,
    val type: GherkinKeywordType,
    val start: Int,
    val end: Int,
)

/** The keywords of one Gherkin language (`# language: <code>`). */
class Dialect
internal constructor(
    val index: Int,
    val code: String,
    val name: String,
    val nativeName: String,
    private val keywords: Map<GherkinKeywordType, List<String>>,
) {

  private val steps: List<Pair<String, GherkinKeywordType>>
  private val sections: List<Pair<String, GherkinKeywordType>>

  init {
    val stepList = ArrayList<Pair<String, GherkinKeywordType>>()
    val sectionList = ArrayList<Pair<String, GherkinKeywordType>>()
    val seen = HashSet<String>()
    for (type in GherkinKeywordType.entries) {
      for (keyword in keywords(type)) {
        if (seen.add((if (type.isStep) "s:" else "k:") + keyword.lowercase())) {
          (if (type.isStep) stepList else sectionList).add(keyword to type)
        }
      }
    }
    stepList.add("* " to GherkinKeywordType.AND)
    stepList.sortByDescending { it.first.trimEnd().length }
    sectionList.sortByDescending { it.first.length }
    steps = stepList
    sections = sectionList
  }

  /**
   * Keywords of a kind as in `gherkin-languages.json`: step keywords end with a space when the
   * language separates them from the text (`"Given "`, but `"假如"`). `*` is not included.
   */
  fun keywords(type: GherkinKeywordType): List<String> = keywords[type] ?: emptyList()

  /** All step keywords (trimmed, including `*`), longest first. */
  fun stepKeywords(): List<String> = steps.map { it.first.trimEnd() }

  /** All section keywords (without colon), longest first. */
  fun sectionKeywords(): List<String> = sections.map { it.first }

  /** Whether a step keyword must be followed by whitespace (`Given `) or not (`假如`). */
  fun needsSpace(stepKeyword: String): Boolean =
      steps.firstOrNull { it.first.trimEnd().equals(stepKeyword, true) }?.first?.endsWith(" ")
          ?: true

  /** Matches a step keyword at [from] (the first non-blank character of a line). */
  fun matchStep(text: CharSequence, from: Int = 0, to: Int = text.length): GherkinKeywordMatch? {
    for ((raw, type) in steps) {
      val keyword = raw.trimEnd()
      if (!startsWith(text, from, to, keyword)) {
        continue
      }
      val end = from + keyword.length
      if (keyword.length != raw.length && end < to && !Character.isWhitespace(text[end])) {
        continue
      }
      return GherkinKeywordMatch(text.subSequence(from, end).toString(), type, from, end)
    }
    return null
  }

  /** Matches a section keyword followed by `:` (or a full-width `：`) at [from]. */
  fun matchSection(text: CharSequence, from: Int = 0, to: Int = text.length): GherkinKeywordMatch? {
    for ((keyword, type) in sections) {
      if (!startsWith(text, from, to, keyword)) {
        continue
      }
      var end = from + keyword.length
      while (end < to && (text[end] == ' ' || text[end] == '\t')) {
        end++
      }
      if (end < to && (text[end] == ':' || text[end] == '：')) {
        val keywordText = text.subSequence(from, from + keyword.length).toString()
        return GherkinKeywordMatch(keywordText, type, from, end + 1)
      }
    }
    return null
  }

  /** The kind of a step keyword of this dialect (`*` is [GherkinKeywordType.AND]). */
  fun stepType(keyword: String): GherkinKeywordType? {
    val trimmed = keyword.trim()
    return steps.firstOrNull { it.first.trimEnd().equals(trimmed, true) }?.second
  }

  /** The kind of a section keyword of this dialect; a trailing colon is ignored. */
  fun sectionType(keyword: String): GherkinKeywordType? {
    val trimmed = keyword.trim().trimEnd(':', '：').trim()
    return sections.firstOrNull { it.first.equals(trimmed, true) }?.second
  }

  /** A regex alternation (longest first) of this dialect's keywords of the given kinds. */
  fun alternation(vararg types: GherkinKeywordType): String =
      (steps + sections)
          .filter { it.second in types }
          .sortedByDescending { it.first.trimEnd().length }
          .joinToString("|") { (raw, _) ->
            val keyword = raw.trimEnd()
            Regex.escape(keyword) + if (keyword.length != raw.length) "(?=\\s|$)" else ""
          }

  override fun toString(): String = code

  private companion object {
    fun startsWith(text: CharSequence, from: Int, to: Int, keyword: String): Boolean {
      if (from + keyword.length > to) {
        return false
      }
      for (i in keyword.indices) {
        val a = text[from + i]
        val b = keyword[i]
        if (a != b &&
            Character.toLowerCase(a) != Character.toLowerCase(b) &&
            Character.toUpperCase(a) != Character.toUpperCase(b)) {
          return false
        }
      }
      return true
    }
  }
}

/** Gherkin dialects from the official `gherkin-languages.json`, selected by `# language: xx`. */
object GherkinDialects {

  private val TYPES =
      listOf(
          GherkinKeywordType.FEATURE,
          GherkinKeywordType.BACKGROUND,
          GherkinKeywordType.RULE,
          GherkinKeywordType.SCENARIO,
          GherkinKeywordType.SCENARIO_OUTLINE,
          GherkinKeywordType.EXAMPLES,
          GherkinKeywordType.GIVEN,
          GherkinKeywordType.WHEN,
          GherkinKeywordType.THEN,
          GherkinKeywordType.AND,
          GherkinKeywordType.BUT,
      )

  /** All dialects; English is first (index 0). */
  @JvmStatic
  val all: List<Dialect> =
      GherkinDialectData.ROWS.mapIndexed { index, row ->
        val keywords = HashMap<GherkinKeywordType, List<String>>()
        for ((i, type) in TYPES.withIndex()) {
          keywords[type] = row[3 + i].split('|').filter { it.isNotEmpty() }
        }
        Dialect(index, row[0], row[1], row[2], keywords)
      }

  @JvmStatic val ENGLISH: Dialect = all[0]

  private val byCode: Map<String, Dialect> = all.associateBy { normalize(it.code) }

  private val LANGUAGE_HEADER =
      Regex("""#\s*language\s*:\s*([A-Za-z0-9_-]+)\s*""", RegexOption.IGNORE_CASE)

  /** The dialect for a language code (`zh-CN`, `zh_cn`, `de`), or null. */
  @JvmStatic fun forCode(code: String): Dialect? = byCode[normalize(code.trim())]

  @JvmStatic fun byIndex(index: Int): Dialect = all.getOrElse(index) { ENGLISH }

  /**
   * The dialect declared by a `# language: xx` comment before any other content of a feature file;
   * English when there is none (or the code is unknown).
   */
  @JvmStatic
  fun dialectFor(featureText: CharSequence): Dialect =
      forCode(languageCode(featureText) ?: "") ?: ENGLISH

  /** The code of the `# language:` header, or null. */
  @JvmStatic
  fun languageCode(featureText: CharSequence): String? {
    var start = 0
    val length = featureText.length
    while (start < length) {
      var end = start
      while (end < length && featureText[end] != '\n') {
        end++
      }
      val line = featureText.subSequence(start, end).toString().trim().removePrefix("\uFEFF")
      if (line.isNotEmpty()) {
        if (!line.startsWith("#")) {
          return null
        }
        val match = LANGUAGE_HEADER.matchEntire(line)
        if (match != null) {
          return match.groupValues[1]
        }
      }
      start = end + 1
    }
    return null
  }

  /**
   * The kind of a step keyword in any dialect (English first), for callers that only have the
   * keyword text.
   */
  @JvmStatic
  fun stepType(keyword: String): GherkinKeywordType? {
    ENGLISH.stepType(keyword)?.let {
      return it
    }
    for (dialect in all) {
      dialect.stepType(keyword)?.let {
        return it
      }
    }
    return null
  }

  private fun normalize(code: String): String = code.replace('_', '-').lowercase()
}
