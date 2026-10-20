package com.github.cucumber.jetbrains.plugin.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Converts Cucumber Messages (the ndjson written by the `message` formatter of Cucumber-JVM,
 * cucumber-js and cucumber-ruby) into test-tree events: Feature (suite) -> Scenario (suite) ->
 * Step (test). IDE-free; the IDE renders the events as TeamCity service messages.
 */
object CucumberMessages {

  /** Location hints use `cucumber://<absolute path>:<line>`. */
  const val PROTOCOL = "cucumber"

  /**
   * Reads the lines a Cucumber process appends to a messages file. Each Cucumber run (several
   * runner classes or modules in one build) truncates and rewrites the file: [read] notices that
   * (the file got shorter, or the last bytes read changed) and reports a restart before the new
   * stream's lines. Not thread-safe.
   */
  class FileFollower(private val file: Path) {

    private val pending = ByteArrayOutputStream()
    private var offset = 0L
    private var tail = ByteArray(0)

    /** Reads the complete lines written since the last call. */
    @Throws(IOException::class)
    fun read(onRestart: () -> Unit, onLine: (String) -> Unit) {
      if (!Files.isRegularFile(file)) {
        return
      }
      RandomAccessFile(file.toFile(), "r").use { input ->
        if (replaced(input)) {
          offset = 0
          pending.reset()
          tail = ByteArray(0)
          onRestart()
        }
        if (input.length() <= offset) {
          return
        }
        input.seek(offset)
        val buffer = ByteArray(64 * 1024)
        while (true) {
          val count = input.read(buffer)
          if (count <= 0) {
            break
          }
          offset += count
          remember(buffer, count)
          var start = 0
          for (i in 0 until count) {
            if (buffer[i] == '\n'.code.toByte()) {
              pending.write(buffer, start, i - start)
              val line = pending.toString(StandardCharsets.UTF_8)
              pending.reset()
              onLine(line)
              start = i + 1
            }
          }
          pending.write(buffer, start, count - start)
        }
      }
    }

    /** The unterminated last line, if any (call when the process has ended). */
    fun rest(): String? {
      if (pending.size() == 0) {
        return null
      }
      val line = pending.toString(StandardCharsets.UTF_8)
      pending.reset()
      return line
    }

    private fun replaced(input: RandomAccessFile): Boolean {
      if (offset == 0L) {
        return false
      }
      if (input.length() < offset) {
        return true
      }
      val current = ByteArray(tail.size)
      input.seek(offset - tail.size)
      input.readFully(current)
      return !current.contentEquals(tail)
    }

    /** Keeps the last [TAIL_SIZE] bytes read (fewer at the file's start). */
    private fun remember(buffer: ByteArray, count: Int) {
      val keep = minOf(TAIL_SIZE.toLong(), offset).toInt()
      val next = ByteArray(keep)
      val fromBuffer = minOf(count, keep)
      val fromTail = keep - fromBuffer
      System.arraycopy(tail, tail.size - fromTail, next, 0, fromTail)
      System.arraycopy(buffer, count - fromBuffer, next, fromTail, fromBuffer)
      tail = next
    }

    private companion object {
      const val TAIL_SIZE = 256
    }
  }

  sealed interface Event {
    val id: String
    val name: String

    /** The TeamCity service message for an id-based test tree. */
    fun serviceMessage(): String
  }

  data class SuiteStarted(
      override val id: String,
      val parentId: String,
      override val name: String,
      val location: String?,
  ) : Event {
    override fun serviceMessage(): String =
        message(
            "testSuiteStarted",
            "name" to name,
            "nodeId" to id,
            "parentNodeId" to parentId,
            "locationHint" to location)
  }

  data class SuiteFinished(override val id: String, override val name: String) : Event {
    override fun serviceMessage(): String =
        message("testSuiteFinished", "name" to name, "nodeId" to id)
  }

  data class TestStarted(
      override val id: String,
      val parentId: String,
      override val name: String,
      val location: String?,
  ) : Event {
    override fun serviceMessage(): String =
        message(
            "testStarted",
            "name" to name,
            "nodeId" to id,
            "parentNodeId" to parentId,
            "locationHint" to location)
  }

  data class TestFailed(
      override val id: String,
      override val name: String,
      val message: String,
      val details: String,
  ) : Event {
    override fun serviceMessage(): String =
        message("testFailed", "name" to name, "nodeId" to id, "message" to message, "details" to details)
  }

  data class TestIgnored(override val id: String, override val name: String, val message: String) :
      Event {
    override fun serviceMessage(): String =
        message("testIgnored", "name" to name, "nodeId" to id, "message" to message)
  }

  data class TestFinished(override val id: String, override val name: String, val durationMs: Long) :
      Event {
    override fun serviceMessage(): String =
        message("testFinished", "name" to name, "nodeId" to id, "duration" to durationMs.toString())
  }

  /** `cucumber://<path>:<line>`. */
  fun location(path: String, line: Int): String = "$PROTOCOL://$path:$line"

  /** Splits a location path (`C:/x/a.feature:12`) into path and line, or null. */
  fun parseLocation(path: String): Pair<String, Int>? {
    val colon = path.lastIndexOf(':')
    if (colon <= 0) {
      return null
    }
    val line = path.substring(colon + 1).toIntOrNull() ?: return null
    return path.substring(0, colon) to line
  }

  private fun message(name: String, vararg attributes: Pair<String, String?>): String {
    val result = StringBuilder("##teamcity[").append(name)
    for ((key, value) in attributes) {
      if (value != null) {
        result.append(' ').append(key).append("='").append(escape(value)).append('\'')
      }
    }
    return result.append(']').toString()
  }

  /** TeamCity service-message escaping. */
  fun escape(value: String): String {
    val result = StringBuilder(value.length + 8)
    for (c in value) {
      when (c) {
        '|' -> result.append("||")
        '\'' -> result.append("|'")
        '\n' -> result.append("|n")
        '\r' -> result.append("|r")
        '[' -> result.append("|[")
        ']' -> result.append("|]")
        '\u0085' -> result.append("|x")
        '\u2028' -> result.append("|l")
        '\u2029' -> result.append("|p")
        else -> result.append(c)
      }
    }
    return result.toString()
  }

  // ------------------------------------------------------------------ converter

  /**
   * Stateful converter: feed every ndjson line to [accept] (in order), then call [finish].
   *
   * @param baseDir directory that relative `uri`s are resolved against (the runner's working
   *   directory)
   */
  class Converter(private val baseDir: String?) {

    private class AstNode(val line: Int, val keyword: String, val text: String)

    private class RowInfo(val line: Int, val index: Int)

    private class Pickle(
        val uri: String,
        val name: String,
        val astNodeIds: List<String>,
        val steps: Map<String, PickleStep>,
    )

    private class PickleStep(val text: String, val astNodeIds: List<String>)

    private class TestCase(val pickleId: String, val steps: Map<String, TestStep>)

    private class TestStep(val pickleStepId: String?, val hookId: String?)

    private class Running(val id: String, val testCase: TestCase, val pickle: Pickle?) {
      var sawStep = false
    }

    private val nodes = HashMap<String, AstNode>()
    private val rows = HashMap<String, RowInfo>()
    private val features = HashMap<String, AstNode>()
    private val pickles = HashMap<String, Pickle>()
    private val testCases = HashMap<String, TestCase>()
    private val running = LinkedHashMap<String, Running>()
    private val openFeatures = LinkedHashMap<String, Int>()
    private var lastFeature: String? = null
    private var eventCount = 0

    /** Index of the current Cucumber run; later runs (several runners in one build) get own ids. */
    private var run = 0
    private var runStarted = false

    /** Whether any test event was produced so far. */
    fun hasEvents(): Boolean = eventCount > 0

    /**
     * Starts over for a new message stream (the file was truncated by the next Cucumber run):
     * closes the suites still open and gives the following tree nodes new ids.
     */
    fun restart(): List<Event> {
      val events = finish()
      run++
      runStarted = false
      nodes.clear()
      rows.clear()
      features.clear()
      pickles.clear()
      testCases.clear()
      return events
    }

    /** Converts one ndjson line; malformed lines are ignored. */
    fun accept(line: String): List<Event> {
      val trimmed = line.trim()
      if (!trimmed.startsWith("{")) {
        return emptyList()
      }
      val envelope =
          try {
            Json.parse(trimmed) as? Map<*, *>
          } catch (e: IllegalArgumentException) {
            null
          } ?: return emptyList()
      val events = ArrayList<Event>()
      for ((key, value) in envelope) {
        val body = value as? Map<*, *> ?: continue
        when (key) {
          "gherkinDocument" -> gherkinDocument(body)
          "pickle" -> pickle(body)
          "testCase" -> testCase(body)
          "testCaseStarted" -> testCaseStarted(body, events)
          "testStepStarted" -> testStepStarted(body, events)
          "testStepFinished" -> testStepFinished(body, events)
          "testCaseFinished" -> testCaseFinished(body, events)
          "testRunStarted" -> {
            // Another run appended to the same stream: close the previous one, keep ids unique.
            if (runStarted) {
              events.addAll(finish())
              run++
            }
            runStarted = true
          }
          "testRunFinished" -> events.addAll(finish())
        }
      }
      eventCount += events.size
      return events
    }

    /** Closes every suite still open (e.g. when the process was stopped). */
    fun finish(): List<Event> {
      val events = ArrayList<Event>()
      for (run in running.values.toList()) {
        events.add(SuiteFinished(scenarioId(run.id), scenarioName(run.pickle)))
      }
      running.clear()
      for (uri in openFeatures.keys.toList()) {
        events.add(SuiteFinished(featureId(uri), featureName(uri)))
      }
      openFeatures.clear()
      lastFeature = null
      eventCount += events.size
      return events
    }

    private fun gherkinDocument(body: Map<*, *>) {
      val uri = body["uri"] as? String ?: return
      val feature = body["feature"] as? Map<*, *> ?: return
      features[uri] =
          AstNode(line(feature), feature["keyword"] as? String ?: "Feature", feature["name"] as? String ?: "")
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
      nodes[id] =
          AstNode(line(scenario), scenario["keyword"] as? String ?: "Scenario", scenario["name"] as? String ?: "")
      steps(scenario["steps"])
      for (examples in scenario["examples"] as? List<*> ?: emptyList<Any>()) {
        val body = (examples as? Map<*, *>)?.get("tableBody") as? List<*> ?: continue
        for ((index, row) in body.withIndex()) {
          val rowMap = row as? Map<*, *> ?: continue
          val rowId = rowMap["id"] as? String ?: continue
          rows[rowId] = RowInfo(line(rowMap), index + 1)
        }
      }
    }

    private fun steps(list: Any?) {
      for (step in list as? List<*> ?: return) {
        val map = step as? Map<*, *> ?: continue
        val id = map["id"] as? String ?: continue
        nodes[id] = AstNode(line(map), map["keyword"] as? String ?: "", map["text"] as? String ?: "")
      }
    }

    private fun pickle(body: Map<*, *>) {
      val id = body["id"] as? String ?: return
      val steps = LinkedHashMap<String, PickleStep>()
      for (step in body["steps"] as? List<*> ?: emptyList<Any>()) {
        val map = step as? Map<*, *> ?: continue
        val stepId = map["id"] as? String ?: continue
        steps[stepId] = PickleStep(map["text"] as? String ?: "", strings(map["astNodeIds"]))
      }
      pickles[id] =
          Pickle(body["uri"] as? String ?: "", body["name"] as? String ?: "", strings(body["astNodeIds"]), steps)
    }

    private fun testCase(body: Map<*, *>) {
      val id = body["id"] as? String ?: return
      val steps = LinkedHashMap<String, TestStep>()
      for (step in body["testSteps"] as? List<*> ?: emptyList<Any>()) {
        val map = step as? Map<*, *> ?: continue
        val stepId = map["id"] as? String ?: continue
        steps[stepId] = TestStep(map["pickleStepId"] as? String, map["hookId"] as? String)
      }
      testCases[id] = TestCase(body["pickleId"] as? String ?: "", steps)
    }

    private fun testCaseStarted(body: Map<*, *>, events: MutableList<Event>) {
      val id = body["id"] as? String ?: return
      val testCase = testCases[body["testCaseId"] as? String] ?: return
      val pickle = pickles[testCase.pickleId]
      val uri = pickle?.uri ?: ""
      val previous = lastFeature
      if (previous != null && previous != uri && openFeatures[previous] == 0) {
        openFeatures.remove(previous)
        events.add(SuiteFinished(featureId(previous), featureName(previous)))
      }
      if (!openFeatures.containsKey(uri)) {
        openFeatures[uri] = 0
        val feature = features[uri]
        events.add(
            SuiteStarted(featureId(uri), "0", featureName(uri), feature?.let { location(path(uri), it.line) }))
      }
      openFeatures[uri] = (openFeatures[uri] ?: 0) + 1
      lastFeature = uri
      val run = Running(id, testCase, pickle)
      running[id] = run
      events.add(SuiteStarted(scenarioId(id), featureId(uri), scenarioName(pickle), scenarioLocation(pickle)))
    }

    private fun testStepStarted(body: Map<*, *>, events: MutableList<Event>) {
      val run = running[body["testCaseStartedId"] as? String] ?: return
      val stepId = body["testStepId"] as? String ?: return
      val step = run.testCase.steps[stepId] ?: return
      if (step.pickleStepId == null) {
        return
      }
      run.sawStep = true
      val (name, location) = stepNameAndLocation(run, step.pickleStepId)
      events.add(TestStarted(stepNodeId(run.id, stepId), scenarioId(run.id), name, location))
    }

    private fun testStepFinished(body: Map<*, *>, events: MutableList<Event>) {
      val run = running[body["testCaseStartedId"] as? String] ?: return
      val stepId = body["testStepId"] as? String ?: return
      val step = run.testCase.steps[stepId] ?: return
      val result = body["testStepResult"] as? Map<*, *> ?: emptyMap<Any, Any>()
      val status = (result["status"] as? String ?: "UNKNOWN").uppercase(Locale.ROOT)
      val duration = durationMs(result["duration"])
      val nodeId = stepNodeId(run.id, stepId)
      val name: String
      if (step.pickleStepId == null) {
        // Hooks are only shown when they fail.
        if (status != "FAILED" && status != "AMBIGUOUS" && status != "UNDEFINED") {
          return
        }
        name = if (run.sawStep) "After hook" else "Before hook"
        events.add(TestStarted(nodeId, scenarioId(run.id), name, scenarioLocation(run.pickle)))
      } else {
        name = stepNameAndLocation(run, step.pickleStepId).first
      }
      val text = result["message"] as? String
      when (status) {
        "PASSED" -> {}
        "SKIPPED" -> events.add(TestIgnored(nodeId, name, text ?: "Skipped"))
        "PENDING" -> events.add(TestIgnored(nodeId, name, text ?: "Pending"))
        "UNKNOWN" -> events.add(TestIgnored(nodeId, name, text ?: "Unknown"))
        "UNDEFINED" -> events.add(TestFailed(nodeId, name, "Undefined step: " + name, text ?: ""))
        "AMBIGUOUS" -> events.add(TestFailed(nodeId, name, "Ambiguous step: " + name, text ?: ""))
        else -> {
          val exception = result["exception"] as? Map<*, *>
          val full = text ?: (exception?.get("message") as? String) ?: status.lowercase(Locale.ROOT)
          val newline = full.indexOf('\n')
          val headline = (if (newline < 0) full else full.substring(0, newline)).trim()
          val stackTrace = exception?.get("stackTrace") as? String
          val details =
              stackTrace?.trimEnd() ?: if (newline < 0) "" else full.substring(newline + 1).trimEnd()
          events.add(TestFailed(nodeId, name, headline, details))
        }
      }
      events.add(TestFinished(nodeId, name, duration))
    }

    private fun testCaseFinished(body: Map<*, *>, events: MutableList<Event>) {
      val id = body["testCaseStartedId"] as? String ?: return
      val run = running.remove(id) ?: return
      events.add(SuiteFinished(scenarioId(id), scenarioName(run.pickle)))
      val uri = run.pickle?.uri ?: ""
      openFeatures[uri]?.let { openFeatures[uri] = it - 1 }
    }

    private fun stepNameAndLocation(run: Running, pickleStepId: String): Pair<String, String?> {
      val pickle = run.pickle ?: return pickleStepId to null
      val pickleStep = pickle.steps[pickleStepId]
      val ast = pickleStep?.astNodeIds?.firstOrNull()?.let { nodes[it] }
      val keyword = ast?.keyword?.trim().orEmpty()
      val text = pickleStep?.text ?: ast?.text ?: pickleStepId
      val name = if (keyword.isEmpty()) text else "$keyword $text"
      val location = if (ast == null) null else location(path(pickle.uri), ast.line)
      return name to location
    }

    private fun scenarioName(pickle: Pickle?): String {
      if (pickle == null) {
        return "Scenario"
      }
      val row = pickle.astNodeIds.drop(1).firstNotNullOfOrNull { rows[it] }
      return if (row == null) pickle.name else "${pickle.name} (example ${row.index})"
    }

    private fun scenarioLocation(pickle: Pickle?): String? {
      if (pickle == null) {
        return null
      }
      val line =
          pickle.astNodeIds.drop(1).firstNotNullOfOrNull { rows[it] }?.line
              ?: pickle.astNodeIds.firstOrNull()?.let { nodes[it] }?.line
              ?: return null
      return location(path(pickle.uri), line)
    }

    private fun featureName(uri: String): String {
      val name = features[uri]?.text
      if (!name.isNullOrBlank()) {
        return name
      }
      return uri.substringAfterLast('/').ifEmpty { "Feature" }
    }

    private fun prefix(): String = if (run == 0) "" else "run$run/"

    private fun featureId(uri: String): String = prefix() + "feature:$uri"

    private fun scenarioId(testCaseStartedId: String): String = prefix() + "scenario:$testCaseStartedId"

    private fun stepNodeId(testCaseStartedId: String, stepId: String): String =
        prefix() + "step:$testCaseStartedId:$stepId"

    /** The absolute path (with `/`) of a message `uri`. */
    fun path(uri: String): String {
      if (uri.startsWith("file:")) {
        return try {
          var path = URI(uri).path ?: uri.removePrefix("file:")
          if (path.length > 2 && path[0] == '/' && path[2] == ':') {
            path = path.substring(1)
          }
          path
        } catch (e: IllegalArgumentException) {
          uri.removePrefix("file:")
        }
      }
      val normalized = uri.replace('\\', '/')
      if (normalized.startsWith("classpath:")) {
        return classpath(normalized.removePrefix("classpath:").trimStart('/'))
      }
      val absolute =
          normalized.startsWith("/") || (normalized.length > 1 && normalized[1] == ':') ||
              normalized.contains(":") && !normalized.startsWith(".")
      if (absolute || baseDir == null) {
        return normalized
      }
      return baseDir.replace('\\', '/').trimEnd('/') + "/" + normalized.removePrefix("./")
    }

    /**
     * A `classpath:` resource: the first `src/<set>/resources` (or `java`) file under [baseDir]
     * and its direct subdirectories, else `classpath:<resource>` for the IDE to search.
     */
    private fun classpath(resource: String): String {
      val base = baseDir ?: return "classpath:$resource"
      return classpathCache.getOrPut(resource) {
        val roots = ArrayList<java.io.File>()
        val baseFile = java.io.File(base)
        roots.add(baseFile)
        baseFile.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.let { roots.addAll(it) }
        for (root in roots) {
          for (set in listOf("test", "main")) {
            for (kind in listOf("resources", "java", "kotlin")) {
              val candidate = java.io.File(root, "src/$set/$kind/$resource")
              if (candidate.isFile) {
                return@getOrPut candidate.absolutePath.replace('\\', '/')
              }
            }
          }
        }
        "classpath:$resource"
      }
    }

    private val classpathCache = HashMap<String, String>()

    private fun line(node: Map<*, *>): Int =
        ((node["location"] as? Map<*, *>)?.get("line") as? Number)?.toInt() ?: 0

    private fun strings(value: Any?): List<String> =
        (value as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    private fun durationMs(value: Any?): Long {
      val map = value as? Map<*, *> ?: return 0
      val seconds = (map["seconds"] as? Number)?.toLong() ?: 0
      val nanos = (map["nanos"] as? Number)?.toLong() ?: 0
      return seconds * 1000 + nanos / 1_000_000
    }
  }

  // ------------------------------------------------------------------ JSON

  /** A minimal JSON reader: objects become maps, arrays lists, numbers Long/Double. */
  object Json {

    fun parse(text: String): Any? {
      val reader = Reader(text)
      reader.skipWhitespace()
      val value = reader.value()
      reader.skipWhitespace()
      if (reader.position != text.length) {
        throw IllegalArgumentException("Trailing characters at ${reader.position}")
      }
      return value
    }

    private class Reader(val text: String) {
      var position = 0

      fun skipWhitespace() {
        while (position < text.length && text[position].isWhitespace()) {
          position++
        }
      }

      fun value(): Any? {
        if (position >= text.length) {
          throw IllegalArgumentException("Unexpected end of JSON")
        }
        return when (val c = text[position]) {
          '{' -> obj()
          '[' -> array()
          '"' -> string()
          't' -> literal("true", true)
          'f' -> literal("false", false)
          'n' -> literal("null", null)
          else ->
              if (c == '-' || c.isDigit()) number()
              else throw IllegalArgumentException("Unexpected '$c' at $position")
        }
      }

      private fun obj(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        position++
        skipWhitespace()
        if (peek() == '}') {
          position++
          return result
        }
        while (true) {
          skipWhitespace()
          if (peek() != '"') {
            throw IllegalArgumentException("Expected a key at $position")
          }
          val key = string()
          skipWhitespace()
          expect(':')
          skipWhitespace()
          result[key] = value()
          skipWhitespace()
          when (peek()) {
            ',' -> position++
            '}' -> {
              position++
              return result
            }
            else -> throw IllegalArgumentException("Expected ',' or '}' at $position")
          }
        }
      }

      private fun array(): List<Any?> {
        val result = ArrayList<Any?>()
        position++
        skipWhitespace()
        if (peek() == ']') {
          position++
          return result
        }
        while (true) {
          skipWhitespace()
          result.add(value())
          skipWhitespace()
          when (peek()) {
            ',' -> position++
            ']' -> {
              position++
              return result
            }
            else -> throw IllegalArgumentException("Expected ',' or ']' at $position")
          }
        }
      }

      private fun string(): String {
        position++
        val result = StringBuilder()
        while (true) {
          if (position >= text.length) {
            throw IllegalArgumentException("Unterminated string")
          }
          val c = text[position++]
          when (c) {
            '"' -> return result.toString()
            '\\' -> {
              if (position >= text.length) {
                throw IllegalArgumentException("Unterminated escape")
              }
              when (val e = text[position++]) {
                '"', '\\', '/' -> result.append(e)
                'b' -> result.append('\b')
                'f' -> result.append('\u000C')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                  if (position + 4 > text.length) {
                    throw IllegalArgumentException("Bad unicode escape")
                  }
                  result.append(text.substring(position, position + 4).toInt(16).toChar())
                  position += 4
                }
                else -> throw IllegalArgumentException("Bad escape '\\$e'")
              }
            }
            else -> result.append(c)
          }
        }
      }

      private fun number(): Any {
        val start = position
        if (peek() == '-') {
          position++
        }
        var fraction = false
        while (position < text.length) {
          val c = text[position]
          if (c.isDigit()) {
            position++
          } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
            fraction = true
            position++
          } else {
            break
          }
        }
        val token = text.substring(start, position)
        return try {
          if (fraction) token.toDouble() else token.toLong()
        } catch (e: NumberFormatException) {
          throw IllegalArgumentException("Bad number '$token'")
        }
      }

      private fun literal(word: String, value: Any?): Any? {
        if (!text.startsWith(word, position)) {
          throw IllegalArgumentException("Unexpected token at $position")
        }
        position += word.length
        return value
      }

      private fun expect(c: Char) {
        if (peek() != c) {
          throw IllegalArgumentException("Expected '$c' at $position")
        }
        position++
      }

      private fun peek(): Char? = if (position < text.length) text[position] else null
    }
  }
}
