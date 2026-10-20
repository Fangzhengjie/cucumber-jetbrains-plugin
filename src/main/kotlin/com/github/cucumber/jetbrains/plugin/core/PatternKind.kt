package com.github.cucumber.jetbrains.plugin.core

/** How a step definition states its pattern, which decides how step text is matched. */
enum class PatternKind {
  /** Literal text that must equal the whole step (cucumber-rs plain strings, pytest-bdd). */
  EXACT,
  /** A Cucumber Expression: `{int}`, `{string}`, `(optional)`, `a/b`. */
  CUCUMBER_EXPRESSION,
  /** A regular expression. */
  REGEX,
  /** A Python `parse` format (behave, pytest-bdd `parsers.parse`). */
  PARSE_FORMAT,
}
