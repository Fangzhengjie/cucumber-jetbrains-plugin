package io.github.fangzhengjie.cucumber.core

/** Reads one language's step definitions and writes new ones. Pure text, no IDE classes. */
interface LanguageAdapter {

  fun language(): Language

  /** Cheap pre-check so most files are never parsed. */
  fun mayContainSteps(text: CharSequence): Boolean

  /** Step definitions in `text`, in source order. */
  fun parse(text: String): List<StepDefinitionInfo>

  /**
   * A step definition for `request`, and where to insert it into a file with `fileText` named
   * `fileName`.
   */
  fun snippet(request: Snippets.Request, fileText: String, fileName: String): Snippets.Snippet
}
