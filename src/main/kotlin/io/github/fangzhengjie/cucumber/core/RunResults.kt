package io.github.fangzhengjie.cucumber.core

import java.util.Locale

/**
 * The last run's result per `.feature` line (feature, scenario, outline, example row and step),
 * collected from the Cucumber Messages ndjson of a run. IDE-free; the IDE shows the results in the
 * editor gutter.
 */
object RunResults {

  /** Result states, from best to worst. */
  enum class Status {
    PASSED,
    SKIPPED,
    PENDING,
    UNDEFINED,
    AMBIGUOUS,
    FAILED;

    val label: String
      get() = name.lowercase(Locale.ROOT)

    fun worst(other: Status): Status = if (other.ordinal > ordinal) other else this

    companion object {
      fun of(status: String): Status =
          when (status.uppercase(Locale.ROOT)) {
            "PASSED" -> PASSED
            "PENDING" -> PENDING
            "UNDEFINED" -> UNDEFINED
            "AMBIGUOUS" -> AMBIGUOUS
            "FAILED" -> FAILED
            else -> SKIPPED
          }
    }
  }

  enum class Kind {
    FEATURE,
    SCENARIO,
    OUTLINE,
    EXAMPLE,
    STEP,
  }

  /**
   * The result of one line.
   *
   * @param message first line of the (worst) failure, undefined, ambiguous or pending message
   * @param step the step (`When I buy 2`) or hook that produced [message], for non-step lines
   * @param counts executions by status: scenarios for feature / outline / scenario / example lines,
   *   step runs for step lines (a background or outline step runs several times)
   * @param signature [signature] of the source line, so results are dropped once the line changes
   */
  data class LineResult(
      val line: Int,
      val kind: Kind,
      val status: Status,
      val message: String?,
      val step: String?,
      val counts: Map<Status, Int>,
      val signature: String,
  ) {
    /** Whether the result still belongs to [lineText] (the current text of the line). */
    fun matches(lineText: CharSequence): Boolean = signature == signature(lineText)

    fun merge(other: LineResult): LineResult {
      val worse = other.status.ordinal > status.ordinal
      val counts = LinkedHashMap(counts)
      for ((status, count) in other.counts) {
        counts[status] = (counts[status] ?: 0) + count
      }
      return copy(
          status = status.worst(other.status),
          message =
              if (worse || message == null && other.status == status) other.message else message,
          step = if (worse || step == null && other.status == status) other.step else step,
          counts = counts)
    }

    /** Plain-text description for tooltips, e.g. `Failed: When I buy 2: boom`. */
    fun describe(): String {
      val result = StringBuilder(status.label.replaceFirstChar { it.uppercase(Locale.ROOT) })
      val total = counts.values.sum()
      if (total > 1) {
        val unit = if (kind == Kind.STEP) "runs" else "scenarios"
        result.append(" (")
        result.append(
            Status.entries
                .reversed()
                .filter { (counts[it] ?: 0) > 0 }
                .joinToString(", ") { "${counts[it]} ${it.label}" })
        result.append(" of $total $unit)")
      }
      val detail = listOfNotNull(step, message).joinToString(": ")
      if (detail.isNotEmpty() && status != Status.PASSED) {
        result.append(": ").append(detail)
      }
      return result.toString()
    }
  }

  /** A line without whitespace and table pipes / escapes: what the line's result is tied to. */
  fun signature(text: CharSequence): String {
    val result = StringBuilder(text.length)
    for (c in text) {
      if (!c.isWhitespace() && c != '|' && c != '\\') {
        result.append(c)
      }
    }
    return result.toString()
  }

  /** `/` separators and an upper-case drive letter (`c:\x` -> `C:/x`). */
  fun normalizePath(path: String): String {
    val normalized = path.replace('\\', '/')
    if (normalized.length >= 2 && normalized[1] == ':' && normalized[0].isLetter()) {
      return normalized[0].uppercaseChar() + normalized.substring(1)
    }
    return normalized
  }

  /** First line of a message, trimmed and shortened. */
  fun headline(text: String?): String? {
    if (text.isNullOrBlank()) {
      return null
    }
    val first = text.trim().lineSequence().first().trim()
    return if (first.length > MAX_MESSAGE) first.substring(0, MAX_MESSAGE - 1) + "\u2026" else first
  }

  private const val MAX_MESSAGE = 300

  /**
   * Collects line results: feed every ndjson line to [accept] in order ([restart] when a new
   * message stream starts, e.g. the next runner class). A test case's results are taken when it
   * finishes (retried attempts are dropped).
   *
   * @param pathOf the absolute file path of a message `uri`
   */
  class Collector(private val pathOf: (String) -> String) {

    private class Node(val line: Int, val signature: String, val text: String)

    private class Scenario(val node: Node, val outline: Boolean)

    private class Pickle(
        val uri: String,
        val astNodeIds: List<String>,
        val steps: Map<String, List<String>>
    )

    private class TestCase(val pickleId: String, val steps: Map<String, String?>)

    private class Running(val testCase: TestCase) {
      val steps = ArrayList<Pair<Node, LineResult>>()
      var result: LineResult? = null
      var sawStep = false
    }

    private val features = HashMap<String, Node>()
    private val scenarios = HashMap<String, Scenario>()
    private val nodes = HashMap<String, Node>()
    private val pickles = HashMap<String, Pickle>()
    private val testCases = HashMap<String, TestCase>()
    private val running = HashMap<String, Running>()
    private val results = LinkedHashMap<String, LinkedHashMap<Int, LineResult>>()
    private val changed = LinkedHashSet<String>()

    /** Starts a new message stream; results collected so far are kept. */
    fun restart() {
      features.clear()
      scenarios.clear()
      nodes.clear()
      pickles.clear()
      testCases.clear()
      running.clear()
    }

    /** Line results by absolute file path. */
    fun results(): Map<String, Map<Int, LineResult>> = results.mapValues { LinkedHashMap(it.value) }

    /** Paths whose results changed since the last call. */
    fun takeChanged(): Set<String> {
      val result = LinkedHashSet(changed)
      changed.clear()
      return result
    }

    fun accept(line: String) {
      val trimmed = line.trim()
      if (!trimmed.startsWith("{")) {
        return
      }
      val envelope =
          try {
            CucumberMessages.Json.parse(trimmed) as? Map<*, *>
          } catch (e: IllegalArgumentException) {
            null
          } ?: return
      for ((key, value) in envelope) {
        val body = value as? Map<*, *> ?: continue
        when (key) {
          "gherkinDocument" -> gherkinDocument(body)
          "pickle" -> pickle(body)
          "testCase" -> testCase(body)
          "testCaseStarted" -> testCaseStarted(body)
          "testStepStarted" -> testStepStarted(body)
          "testStepFinished" -> testStepFinished(body)
          "testCaseFinished" -> testCaseFinished(body)
        }
      }
    }

    private fun gherkinDocument(body: Map<*, *>) {
      val uri = body["uri"] as? String ?: return
      val feature = body["feature"] as? Map<*, *> ?: return
      features[uri] = header(feature, "Feature")
      children(feature["children"])
    }

    private fun children(list: Any?) {
      for (child in list as? List<*> ?: return) {
        val map = child as? Map<*, *> ?: continue
        (map["background"] as? Map<*, *>)?.let { steps(it["steps"]) }
        (map["scenario"] as? Map<*, *>)?.let { scenario(it) }
        (map["rule"] as? Map<*, *>)?.let { children(it["children"]) }
      }
    }

    private fun scenario(scenario: Map<*, *>) {
      val id = scenario["id"] as? String ?: return
      val examples = scenario["examples"] as? List<*> ?: emptyList<Any>()
      scenarios[id] = Scenario(header(scenario, "Scenario"), examples.isNotEmpty())
      steps(scenario["steps"])
      for (table in examples) {
        for (row in (table as? Map<*, *>)?.get("tableBody") as? List<*> ?: continue) {
          val rowMap = row as? Map<*, *> ?: continue
          val rowId = rowMap["id"] as? String ?: continue
          val cells =
              (rowMap["cells"] as? List<*>).orEmpty().map {
                (it as? Map<*, *>)?.get("value") as? String ?: ""
              }
          nodes[rowId] =
              Node(line(rowMap), signature(cells.joinToString("")), cells.joinToString(" | "))
        }
      }
    }

    private fun steps(list: Any?) {
      for (step in list as? List<*> ?: return) {
        val map = step as? Map<*, *> ?: continue
        val id = map["id"] as? String ?: continue
        val text = (map["keyword"] as? String ?: "").trim() + " " + (map["text"] as? String ?: "")
        nodes[id] = Node(line(map), signature(text), text.trim())
      }
    }

    private fun header(map: Map<*, *>, defaultKeyword: String): Node {
      val keyword = (map["keyword"] as? String ?: defaultKeyword).trim()
      val name = map["name"] as? String ?: ""
      return Node(line(map), signature("$keyword:$name"), name)
    }

    private fun pickle(body: Map<*, *>) {
      val id = body["id"] as? String ?: return
      val steps = LinkedHashMap<String, List<String>>()
      for (step in body["steps"] as? List<*> ?: emptyList<Any>()) {
        val map = step as? Map<*, *> ?: continue
        steps[map["id"] as? String ?: continue] = strings(map["astNodeIds"])
      }
      pickles[id] = Pickle(body["uri"] as? String ?: "", strings(body["astNodeIds"]), steps)
    }

    private fun testCase(body: Map<*, *>) {
      val id = body["id"] as? String ?: return
      val steps = LinkedHashMap<String, String?>()
      for (step in body["testSteps"] as? List<*> ?: emptyList<Any>()) {
        val map = step as? Map<*, *> ?: continue
        steps[map["id"] as? String ?: continue] = map["pickleStepId"] as? String
      }
      testCases[id] = TestCase(body["pickleId"] as? String ?: "", steps)
    }

    private fun testCaseStarted(body: Map<*, *>) {
      val id = body["id"] as? String ?: return
      val testCase = testCases[body["testCaseId"] as? String] ?: return
      running[id] = Running(testCase)
    }

    private fun testStepStarted(body: Map<*, *>) {
      val run = running[body["testCaseStartedId"] as? String] ?: return
      val stepId = body["testStepId"] as? String ?: return
      if (run.testCase.steps[stepId] != null) {
        run.sawStep = true
      }
    }

    private fun testStepFinished(body: Map<*, *>) {
      val run = running[body["testCaseStartedId"] as? String] ?: return
      val stepId = body["testStepId"] as? String ?: return
      if (!run.testCase.steps.containsKey(stepId)) {
        return
      }
      val pickleStepId = run.testCase.steps[stepId]
      val result = body["testStepResult"] as? Map<*, *> ?: emptyMap<Any, Any>()
      val status = Status.of(result["status"] as? String ?: "UNKNOWN")
      val node =
          pickleStepId
              ?.let { pickles[run.testCase.pickleId]?.steps?.get(it)?.firstOrNull() }
              ?.let { nodes[it] }
      val name =
          node?.text
              ?: if (pickleStepId != null) "Step"
              else if (run.sawStep) "After hook" else "Before hook"
      val exception = result["exception"] as? Map<*, *>
      val message =
          when (status) {
            Status.PASSED,
            Status.SKIPPED,
            Status.UNDEFINED -> null
            Status.AMBIGUOUS -> headline(result["message"] as? String) ?: "Ambiguous step"
            Status.PENDING -> headline(result["message"] as? String) ?: "Pending"
            Status.FAILED ->
                headline(result["message"] as? String)
                    ?: headline(exception?.get("message") as? String)
                    ?: (exception?.get("type") as? String)
                    ?: "Failed"
          }
      if (pickleStepId == null &&
          status != Status.FAILED &&
          status != Status.AMBIGUOUS &&
          status != Status.UNDEFINED) {
        return // passed / skipped hooks don't change the scenario's result
      }
      val stepResult =
          LineResult(
              node?.line ?: 0,
              Kind.STEP,
              status,
              message,
              null,
              mapOf(status to 1),
              node?.signature ?: "")
      if (node != null) {
        run.steps.add(node to stepResult)
      }
      val scenarioPart = stepResult.copy(step = name, counts = emptyMap())
      run.result = run.result?.merge(scenarioPart) ?: scenarioPart
    }

    private fun testCaseFinished(body: Map<*, *>) {
      val id = body["testCaseStartedId"] as? String ?: return
      val run = running.remove(id) ?: return
      if (body["willBeRetried"] == true) {
        return
      }
      val pickle = pickles[run.testCase.pickleId] ?: return
      val path = pathOf(pickle.uri)
      val status = run.result?.status ?: Status.PASSED
      val outcome =
          (run.result ?: LineResult(0, Kind.SCENARIO, Status.PASSED, null, null, emptyMap(), ""))
              .copy(counts = mapOf(status to 1))
      features[pickle.uri]?.let { add(path, it, Kind.FEATURE, outcome) }
      val scenario = pickle.astNodeIds.firstOrNull()?.let { scenarios[it] }
      val row = pickle.astNodeIds.drop(1).firstNotNullOfOrNull { nodes[it] }
      if (scenario != null) {
        add(path, scenario.node, if (scenario.outline) Kind.OUTLINE else Kind.SCENARIO, outcome)
      }
      if (row != null) {
        add(path, row, Kind.EXAMPLE, outcome)
      }
      for ((node, result) in run.steps) {
        add(path, node, Kind.STEP, result)
      }
    }

    /**
     * Merges with the line's result from earlier test cases of this run (unless the line changed).
     */
    private fun add(path: String, node: Node, kind: Kind, result: LineResult) {
      val fresh = result.copy(line = node.line, kind = kind, signature = node.signature)
      val lines = results.getOrPut(path) { LinkedHashMap() }
      val previous = lines[node.line]
      lines[node.line] =
          if (previous == null || previous.signature != fresh.signature || previous.kind != kind)
              fresh
          else previous.merge(fresh)
      changed.add(path)
    }

    private fun line(node: Map<*, *>): Int =
        ((node["location"] as? Map<*, *>)?.get("line") as? Number)?.toInt() ?: 0

    private fun strings(value: Any?): List<String> =
        (value as? List<*>)?.filterIsInstance<String>() ?: emptyList()
  }
}
