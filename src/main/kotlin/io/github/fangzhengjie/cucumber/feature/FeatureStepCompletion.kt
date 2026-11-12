package io.github.fangzhengjie.cucumber.feature

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.fangzhengjie.cucumber.CucumberIcons
import io.github.fangzhengjie.cucumber.core.StepTextTemplates
import io.github.fangzhengjie.cucumber.ide.StepIndex
import io.github.fangzhengjie.cucumber.ide.StepUsageIndex

/**
 * Completion of step text: the project's step definitions rendered as text with placeholders
 * (inserted as a live template, Tab moves between parameters) and the steps already written in the
 * project's feature files.
 */
internal object FeatureStepCompletion {

  private const val DEFINITION_PRIORITY = 200.0
  private const val KEYWORD_BONUS = 50.0
  private const val FEATURE_STEP_PRIORITY = 100.0

  /** Matches the typed step text by words (in order), ranking items that start with it first. */
  private class StepTextMatcher(prefix: String) : PrefixMatcher(prefix) {
    override fun prefixMatches(name: String): Boolean = StepTextTemplates.matchesWords(name, prefix)

    override fun isStartMatch(name: String): Boolean = StepTextTemplates.startsWith(name, prefix)

    override fun cloneWithPrefix(prefix: String): PrefixMatcher = StepTextMatcher(prefix)
  }

  /** Replaces the inserted text with a live template over the placeholders. */
  private class TemplateInsertHandler(private val template: StepTextTemplates.Template) :
      InsertHandler<LookupElement> {

    override fun handleInsert(context: InsertionContext, item: LookupElement) {
      context.setAddCompletionChar(false)
      val document = context.document
      val start = context.startOffset
      document.deleteString(start, context.tailOffset)
      context.editor.caretModel.moveToOffset(start)
      context.commitDocument()
      val manager = TemplateManager.getInstance(context.project)
      val live = manager.createTemplate("", "")
      live.isToReformat = false
      var last = 0
      for ((index, placeholder) in template.placeholders.withIndex()) {
        live.addTextSegment(template.text.substring(last, placeholder.start))
        var expression = ConstantNode(template.defaultValue(placeholder))
        if (placeholder.choices.isNotEmpty()) {
          expression = expression.withLookupStrings(placeholder.choices)
        }
        live.addVariable("${placeholder.name}_$index", expression, expression, true)
        last = placeholder.end
      }
      live.addTextSegment(template.text.substring(last))
      live.addEndVariable()
      manager.startTemplate(context.editor, live)
    }
  }

  fun addCompletions(parameters: CompletionParameters, result: CompletionResultSet) {
    val position = parameters.position
    val step = PsiTreeUtil.getParentOfType(position, FeatureStep::class.java) ?: return
    val stepStart = step.stepTextOffset()
    val offset = parameters.offset
    if (offset < stepStart) {
      return
    }
    val text = parameters.originalFile.viewProvider.contents
    if (offset > text.length) {
      return
    }
    val prefix = text.subSequence(stepStart, offset).toString()
    if (prefix.contains('\n')) {
      return
    }
    val keyword = step.effectiveKeyword()
    val matcher = result.withPrefixMatcher(StepTextMatcher(prefix))
    val project = position.project
    val shown = HashSet<String>()
    val offered = HashSet<String>()

    val definitions =
        StepIndex.get(project).definitions.sortedByDescending {
          acceptsKeyword(it.info.keyword, keyword)
        }
    for (definition in definitions) {
      ProgressManager.checkCanceled()
      val info = definition.info
      val template = StepTextTemplates.render(info) ?: continue
      if (!shown.add(template.display)) {
        continue
      }
      offered.add(template.text)
      val keywordMatches = acceptsKeyword(info.keyword, keyword)
      var element =
          LookupElementBuilder.create(definition, template.text)
              .withLookupString(template.display)
              .withPresentableText(template.display)
              .withTailText("  " + definition.file.name, true)
              .withTypeText(info.language.displayName(), true)
              .withIcon(CucumberIcons.CUCUMBER)
              .withBoldness(keywordMatches)
      if (template.placeholders.isNotEmpty()) {
        element = element.withInsertHandler(TemplateInsertHandler(template))
      }
      matcher.addElement(
          PrioritizedLookupElement.withPriority(
              element, DEFINITION_PRIORITY + if (keywordMatches) KEYWORD_BONUS else 0.0))
    }

    val original = parameters.originalFile.viewProvider.virtualFile
    val counts = LinkedHashMap<String, Int>()
    val keywords = HashMap<String, Boolean>()
    for (usage in featureSteps(project)) {
      ProgressManager.checkCanceled()
      if (usage.file == original && usage.textOffset == stepStart) {
        continue
      }
      val stepText = usage.text.trim()
      if (stepText.isEmpty() || stepText == prefix.trim() || stepText in offered) {
        continue
      }
      counts.merge(stepText, 1, Int::plus)
      if (usage.effectiveKeyword == keyword) {
        keywords[stepText] = true
      }
    }
    for ((stepText, count) in counts) {
      val keywordMatches = keywords[stepText] == true
      val element =
          LookupElementBuilder.create(stepText)
              .withIcon(CucumberIcons.CUCUMBER)
              .withTypeText(if (count == 1) "1 step" else "$count steps", true)
      matcher.addElement(
          PrioritizedLookupElement.withPriority(
              element, FEATURE_STEP_PRIORITY + if (keywordMatches) KEYWORD_BONUS else 0.0))
    }
  }

  /** The project's feature steps: the usage index when current, else each file's cached steps. */
  private fun featureSteps(project: Project): Sequence<StepUsageIndex.Usage> {
    StepUsageIndex.getIfUpToDate(project)?.let {
      return it.steps.asSequence()
    }
    val psiManager = PsiManager.getInstance(project)
    return StepUsageIndex.featureFiles(project).asSequence().flatMap { file ->
      psiManager.findFile(file)?.let { StepUsageIndex.fileSteps(it) } ?: emptyList()
    }
  }

  /** Whether a definition declared with `definitionKeyword` suits a step with `stepKeyword`. */
  private fun acceptsKeyword(definitionKeyword: String, stepKeyword: String): Boolean =
      definitionKeyword == stepKeyword ||
          (definitionKeyword != "given" &&
              definitionKeyword != "when" &&
              definitionKeyword != "then")
}
