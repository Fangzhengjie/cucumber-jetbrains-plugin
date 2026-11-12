package io.github.fangzhengjie.cucumber.core

import java.util.Locale

/**
 * New step definition files ("Create new step file...") and adding several snippets to a step file
 * at once ("Create all undefined step definitions").
 */
object StepFileTemplates {

  /** A new step file: its name and initial content (snippets not yet added). */
  data class Template(val fileName: String, val text: String)

  /** One undefined step to create a definition for. */
  data class Item(val stepText: String, val request: Snippets.Request)

  /**
   * The snippets to apply in order (each relative to the text left by the previous ones), the
   * resulting text, and the items left out because an earlier snippet already matches them.
   */
  data class Batch(val snippets: List<Snippets.Snippet>, val text: String, val skipped: List<Item>)

  private val JAVA_PACKAGE = Regex("""(?m)^\s*package\s+([\w.]+)\s*;?""")
  private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
  private val FILE_BASE = Regex("""[A-Za-z0-9_][A-Za-z0-9_.\-]*""")

  @JvmStatic
  fun supports(language: Language): Boolean =
      language == Language.JAVA ||
          language == Language.KOTLIN ||
          language == Language.JAVASCRIPT ||
          language == Language.PYTHON ||
          language == Language.RUBY

  /** Suggested name (class name or file name without extension) for a new step file. */
  @JvmStatic
  fun defaultName(language: Language): String =
      if (language == Language.JAVA || language == Language.KOTLIN) "NewSteps" else "new_steps"

  @JvmStatic
  fun isValidName(language: Language, name: String): Boolean =
      if (language == Language.JAVA || language == Language.KOTLIN) IDENTIFIER.matches(name)
      else FILE_BASE.matches(name) && !name.endsWith(".")

  /**
   * A new step file named after `name` in the directory of an existing step file
   * (`referenceFileName`, content `referenceText`), declaring the imports the snippets of
   * `keywords` need. Null for languages without a template or an invalid name.
   */
  @JvmStatic
  fun create(
      language: Language,
      referenceFileName: String,
      referenceText: String,
      name: String,
      keywords: Collection<String>,
  ): Template? {
    if (!supports(language) || !isValidName(language, name)) {
      return null
    }
    val used =
        keywords
            .map { Snippets.normalizeKeyword(it) }
            .toSortedSet()
            .map { Snippets.capitalized(it) }
    val extension = referenceFileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (language) {
      Language.JAVA -> {
        val text = StringBuilder()
        val packageName = JAVA_PACKAGE.find(referenceText)?.groupValues?.get(1)
        if (packageName != null) {
          text.append("package ").append(packageName).append(";\n\n")
        }
        for (keyword in used) {
          text.append("import io.cucumber.java.en.").append(keyword).append(";\n")
        }
        if (used.isNotEmpty()) {
          text.append('\n')
        }
        text.append("public class ").append(name).append(" {\n}\n")
        Template("$name.java", text.toString())
      }
      Language.KOTLIN -> {
        val text = StringBuilder()
        val packageName = JAVA_PACKAGE.find(referenceText)?.groupValues?.get(1)
        if (packageName != null) {
          text.append("package ").append(packageName).append("\n\n")
        }
        for (keyword in used) {
          text.append("import io.cucumber.java.en.").append(keyword).append('\n')
        }
        if (used.isNotEmpty()) {
          text.append('\n')
        }
        text.append("class ").append(name).append(" {\n}\n")
        Template("$name.kt", text.toString())
      }
      Language.JAVASCRIPT -> {
        val ext = if (extension.isEmpty()) "js" else extension
        val typeScript = ext in setOf("ts", "tsx", "mts", "cts")
        val names = used.ifEmpty { listOf("Given") }.joinToString(", ")
        val esm =
            typeScript ||
                ext == "mjs" ||
                Regex("""(?m)^\s*import\s.*['"]@cucumber/cucumber['"]""")
                    .containsMatchIn(referenceText)
        val text =
            if (esm) "import { $names } from '@cucumber/cucumber';\n"
            else "const { $names } = require('@cucumber/cucumber');\n"
        Template("$name.$ext", text)
      }
      Language.PYTHON -> {
        val names = used.ifEmpty { listOf("Given") }.map { it.lowercase(Locale.ROOT) }
        val text =
            if (referenceText.contains("pytest_bdd"))
                "from pytest_bdd import " + (names + "parsers").sorted().joinToString(", ") + "\n"
            else "from behave import " + names.joinToString(", ") + "\n"
        Template("$name.py", text)
      }
      Language.RUBY -> Template("$name.rb", "")
      else -> null
    }
  }

  /**
   * Adds a step definition for each item to `text` (a step file named `fileName`). An item whose
   * step is already matched by a definition added for an earlier item is skipped.
   */
  @JvmStatic
  fun addSnippets(language: Language, text: String, fileName: String, items: List<Item>): Batch {
    val adapter = language.adapter()
    val before = adapter.parse(text).map { it.offset to it.pattern }.toSet()
    var current = text
    val snippets = ArrayList<Snippets.Snippet>()
    val skipped = ArrayList<Item>()
    val seen = HashSet<String>()
    for (item in items) {
      if (!seen.add(item.stepText)) {
        continue
      }
      if (snippets.isNotEmpty()) {
        val added = adapter.parse(current).filter { (it.offset to it.pattern) !in before }
        if (added.any { it.matches(item.stepText) }) {
          skipped.add(item)
          continue
        }
      }
      val snippet = adapter.snippet(item.request, current, fileName)
      snippets.add(snippet)
      current = snippet.apply(current)
    }
    return Batch(snippets, current, skipped)
  }
}
