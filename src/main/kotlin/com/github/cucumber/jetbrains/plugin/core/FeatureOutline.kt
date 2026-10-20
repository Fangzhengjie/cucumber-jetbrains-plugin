package com.github.cucumber.jetbrains.plugin.core

/**
 * A light, IDE-free outline of a `.feature` file (keywords of its `# language:` dialect): the
 * feature, its scenarios with their effective tags (feature + rule + scenario, plus each examples
 * table's tags) and example rows. Lines are 1-based.
 */
object FeatureOutline {

  data class ExampleRow(val line: Int, val index: Int, val tags: Set<String>)

  data class Scenario(
      val line: Int,
      val keyword: String,
      val name: String,
      /** Feature, rule and scenario tags. */
      val tags: Set<String>,
      val examples: List<ExampleRow>,
      /** The first line after this scenario (exclusive end). */
      val endLine: Int,
  ) {
    /** Whether the scenario (or any of its example rows) matches a tag expression. */
    fun matches(expression: TagExpressions.Expression): Boolean =
        if (examples.isEmpty()) expression.evaluate(tags)
        else examples.any { expression.evaluate(it.tags) }

    /** Every tag that applies to the scenario or one of its examples. */
    fun allTags(): Set<String> {
      val all = LinkedHashSet(tags)
      for (row in examples) {
        all.addAll(row.tags)
      }
      return all
    }
  }

  data class Outline(
      val featureLine: Int,
      val featureName: String,
      val featureTags: Set<String>,
      val scenarios: List<Scenario>,
  )

  /** What to run for a caret line: the whole feature (`line == 0`), a scenario or an example row. */
  data class Target(val line: Int, val name: String, val isFeature: Boolean)

  fun parse(text: String): Outline {
    val lines = text.split(Regex("\r?\n"))
    val dialect = GherkinSyntax.dialect(lines)
    var featureLine = 0
    var featureName = ""
    var featureTags: Set<String> = emptySet()
    var ruleTags: Set<String> = emptySet()
    val pending = LinkedHashSet<String>()
    val scenarios = ArrayList<Scenario>()

    var current: Scenario? = null
    var rows = ArrayList<ExampleRow>()
    var examplesTags: Set<String>? = null
    var headerSeen = false
    var docString: String? = null

    fun close(end: Int) {
      val scenario = current ?: return
      scenarios.add(scenario.copy(examples = rows.toList(), endLine = end))
      current = null
      rows = ArrayList()
      examplesTags = null
    }

    for (i in lines.indices) {
      val number = i + 1
      val line = lines[i]
      val trimmed = line.trim()
      val fence = docString
      if (fence != null) {
        if (trimmed.startsWith(fence)) {
          docString = null
        }
        continue
      }
      if (trimmed.startsWith("\"\"\"") || trimmed.startsWith("```")) {
        docString = trimmed.substring(0, 3)
        continue
      }
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue
      }
      if (trimmed.startsWith("@")) {
        pending.addAll(tagsOf(trimmed))
        continue
      }
      val header = GherkinSyntax.header(trimmed, dialect)
      when (header?.type) {
        GherkinKeywordType.FEATURE -> {
          close(number)
          featureLine = number
          featureName = header.title
          featureTags = LinkedHashSet(pending)
          pending.clear()
          continue
        }
        GherkinKeywordType.RULE -> {
          close(number)
          ruleTags = LinkedHashSet(featureTags + pending)
          pending.clear()
          continue
        }
        GherkinKeywordType.BACKGROUND -> {
          close(number)
          pending.clear()
          continue
        }
        GherkinKeywordType.SCENARIO,
        GherkinKeywordType.SCENARIO_OUTLINE -> {
          close(number)
          val name = header.title.ifEmpty { header.keyword }
          val inherited = if (ruleTags.isEmpty()) featureTags else ruleTags
          current = Scenario(number, header.keyword, name, LinkedHashSet(inherited + pending), listOf(), 0)
          pending.clear()
          continue
        }
        GherkinKeywordType.EXAMPLES -> {
          val scenario = current
          examplesTags = if (scenario == null) null else LinkedHashSet(scenario.tags + pending)
          headerSeen = false
          pending.clear()
          continue
        }
        else -> {}
      }
      if (trimmed.startsWith("|")) {
        val tags = examplesTags
        if (tags != null) {
          if (headerSeen) {
            rows.add(ExampleRow(number, rows.size + 1, tags))
          } else {
            headerSeen = true
          }
        }
        continue
      }
      // A step or description line ends any examples table.
      examplesTags = null
    }
    close(lines.size + 1)
    return Outline(featureLine, featureName, featureTags, scenarios.toList())
  }

  /** What runs at a 1-based [line]: an example row, the enclosing scenario, else the feature. */
  fun targetAt(outline: Outline, line: Int): Target {
    for (scenario in outline.scenarios) {
      if (line >= scenario.line && line < scenario.endLine) {
        val row = scenario.examples.firstOrNull { it.line == line }
        if (row != null) {
          return Target(row.line, "${scenario.name} (example ${row.index})", false)
        }
        return Target(scenario.line, scenario.name, false)
      }
    }
    return Target(0, outline.featureName, true)
  }

  private fun tagsOf(line: String): List<String> {
    val comment = line.indexOf(" #")
    val tagsPart = if (comment >= 0) line.substring(0, comment) else line
    return tagsPart.split(Regex("\\s+")).filter { it.startsWith("@") }
  }
}
