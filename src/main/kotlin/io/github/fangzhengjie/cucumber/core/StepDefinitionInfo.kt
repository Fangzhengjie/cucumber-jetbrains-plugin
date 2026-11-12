package io.github.fangzhengjie.cucumber.core

import java.util.regex.Pattern

/**
 * A step definition found in source text.
 *
 * @property keyword lower-case keyword as written (`given`, `when`, `then`, `step`, ...)
 * @property pattern the pattern as written in the source
 * @property regex the Java regex step text is matched against (with `find()`)
 * @property offset start of the pattern literal in the source text (the navigation target)
 */
data class StepDefinitionInfo(
    val language: Language,
    val keyword: String,
    val kind: PatternKind,
    val pattern: String,
    val regex: String,
    val parameterNames: List<String>,
    val offset: Int,
) {

  /** [regex] compiled once (null when invalid); not part of equality. */
  val compiled: Pattern? by lazy(LazyThreadSafetyMode.PUBLICATION) {
    try {
      Pattern.compile(regex)
    } catch (e: RuntimeException) {
      null
    }
  }

  /** Whether `stepText` is matched by this step definition. */
  fun matches(stepText: String): Boolean = compiled?.matcher(stepText)?.find() ?: false

  /**
   * Whether this definition may bind a step whose effective keyword (lower-case English `given` /
   * `when` / `then`, `And` / `But` / `*` already resolved) is `stepKeyword`; null means unknown.
   * Only [Language.keywordSensitive] frameworks look at it, and their generic `step` definitions
   * (`@step`, `[StepDefinition]`) bind any keyword.
   */
  fun acceptsKeyword(stepKeyword: String?): Boolean =
      stepKeyword == null || !language.keywordSensitive || keyword == "step" || keyword == stepKeyword

  /** [matches] and [acceptsKeyword] together. */
  fun matches(stepText: String, stepKeyword: String?): Boolean =
      acceptsKeyword(stepKeyword) && matches(stepText)

  companion object {
    @JvmStatic
    fun of(
        language: Language,
        keyword: String,
        kind: PatternKind,
        pattern: String,
        regex: String,
        offset: Int,
    ): StepDefinitionInfo =
        StepDefinitionInfo(
            language, keyword, kind, pattern, regex, Patterns.parameterNames(kind, pattern), offset)
  }
}
