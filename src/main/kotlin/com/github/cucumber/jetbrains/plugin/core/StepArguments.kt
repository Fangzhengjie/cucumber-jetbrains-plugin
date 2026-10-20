package com.github.cucumber.jetbrains.plugin.core

import java.util.regex.Matcher
import java.util.regex.Pattern

/** Arguments a step passes to a step definition, and why a typed argument fails to match. */
object StepArguments {

  /** @property type the typed parameter as written (`{int}`, `{n:d}`), empty for regex groups */
  data class Argument(val name: String, val type: String, val value: String?)

  data class Mismatch(val value: String, val type: String, val definition: StepDefinitionInfo) {
    val message: String
      get() = "argument '$value' is not a valid $type for step definition '${definition.pattern}'"
  }

  private class Parameter(val type: String, val start: Int, val end: Int)

  /** The arguments of `stepText` for `info`, empty when it does not match. */
  @JvmStatic
  fun extract(info: StepDefinitionInfo, stepText: String): List<Argument> {
    val m = info.compiled?.matcher(stepText)?.takeIf { it.find() } ?: return emptyList()
    val types = types(info)
    val arguments = ArrayList<Argument>()
    for (group in 1..m.groupCount()) {
      val type = types.getOrNull(group - 1) ?: ""
      val name = info.parameterNames.getOrNull(group - 1) ?: "arg${group - 1}"
      arguments.add(Argument(name, type, m.group(group)))
    }
    return arguments
  }

  /** The typed parameters of a Cucumber Expression or parse format, in group order. */
  @JvmStatic
  fun types(info: StepDefinitionInfo): List<String> = parameters(info).map { it.type }

  /**
   * The definition's regex with every typed parameter widened to "anything", or null when the
   * definition has no typed parameters.
   */
  @JvmStatic
  fun relaxedRegex(info: StepDefinitionInfo): String? {
    val parameters = parameters(info)
    if (parameters.isEmpty()) {
      return null
    }
    val relaxed = StringBuilder()
    var last = 0
    for (parameter in parameters) {
      relaxed.append(info.pattern, last, parameter.start)
      relaxed.append("{}")
      last = parameter.end
    }
    relaxed.append(info.pattern, last, info.pattern.length)
    return when (info.kind) {
      PatternKind.CUCUMBER_EXPRESSION -> Patterns.cucumberExpression(relaxed.toString())
      PatternKind.PARSE_FORMAT -> Patterns.parseFormat(relaxed.toString())
      else -> null
    }
  }

  /**
   * Why `stepText` does not match `info` although it would with untyped parameters: the first
   * argument that is invalid for its type. Null when the step matches or differs in its text.
   */
  @JvmStatic
  fun mismatch(info: StepDefinitionInfo, stepText: String): Mismatch? {
    if (info.matches(stepText)) {
      return null
    }
    val relaxed = relaxedRegex(info) ?: return null
    val m = matcher(relaxed, stepText) ?: return null
    val parameters = parameters(info)
    for (group in 1..minOf(m.groupCount(), parameters.size)) {
      val value = m.group(group) ?: continue
      val type = parameters[group - 1].type
      val single =
          if (info.kind == PatternKind.PARSE_FORMAT) Patterns.parseFormat(type)
          else Patterns.cucumberExpression(type)
      if (!Pattern.compile(single).matcher(value).matches()) {
        return Mismatch(value, type, info)
      }
    }
    return null
  }

  /**
   * The most specific [mismatch] among `definitions` (the one with the most literal text);
   * definitions made only of parameters are ignored, they would explain any step.
   */
  @JvmStatic
  fun bestMismatch(definitions: Iterable<StepDefinitionInfo>, stepText: String): Mismatch? =
      definitions
          .filter { literalLength(it) > 0 }
          .mapNotNull { mismatch(it, stepText) }
          .maxByOrNull { literalLength(it.definition) }

  private fun literalLength(info: StepDefinitionInfo): Int {
    var length = info.pattern.count { !Character.isWhitespace(it) }
    for (parameter in parameters(info)) {
      length -= info.pattern.substring(parameter.start, parameter.end).count { !Character.isWhitespace(it) }
    }
    return length
  }

  private fun matcher(regex: String, text: String): Matcher? =
      try {
        Pattern.compile(regex).matcher(text).takeIf { it.find() }
      } catch (e: RuntimeException) {
        null
      }

  /** Typed parameters at the same positions `Patterns` converts them. */
  private fun parameters(info: StepDefinitionInfo): List<Parameter> {
    val pattern = info.pattern
    val parameters = ArrayList<Parameter>()
    var i = 0
    when (info.kind) {
      PatternKind.CUCUMBER_EXPRESSION ->
          while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\\' && i + 1 < pattern.length) {
              i += 2
            } else if (c == '{' && pattern.indexOf('}', i) > 0) {
              val close = pattern.indexOf('}', i)
              parameters.add(Parameter(pattern.substring(i, close + 1), i, close + 1))
              i = close + 1
            } else if (c == '(' && pattern.indexOf(')', i) > 0) {
              i = pattern.indexOf(')', i) + 1
            } else {
              i++
            }
          }
      PatternKind.PARSE_FORMAT ->
          while (i < pattern.length) {
            val c = pattern[i]
            if ((c == '{' || c == '}') && i + 1 < pattern.length && pattern[i + 1] == c) {
              i += 2
            } else if (c == '{' && pattern.indexOf('}', i) > 0) {
              val close = pattern.indexOf('}', i)
              parameters.add(Parameter(pattern.substring(i, close + 1), i, close + 1))
              i = close + 1
            } else {
              i++
            }
          }
      else -> {}
    }
    return parameters
  }
}
