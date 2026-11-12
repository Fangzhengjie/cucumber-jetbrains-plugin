package io.github.fangzhengjie.cucumber.feature

import io.github.fangzhengjie.cucumber.core.Outlines
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/** Scenario Outline structure of a feature file, cached per file. */
object FeatureOutlines {

  fun scenarios(file: PsiFile): List<Outlines.Scenario> =
      CachedValuesManager.getCachedValue(file) {
        CachedValueProvider.Result.create(Outlines.scenarios(file.viewProvider.contents), file)
      }

  fun scenarioAt(file: PsiFile, offset: Int): Outlines.Scenario? =
      Outlines.scenarioAt(scenarios(file), offset)

  /**
   * The text a step is matched against: in a Scenario Outline its `<param>`s are replaced by the
   * values of the first Examples row.
   */
  fun matchText(step: FeatureStep): String {
    val text = step.stepText()
    if (!text.contains('<')) {
      return text
    }
    val file = step.containingFile ?: return text
    return scenarioAt(file, step.textRange.startOffset)?.substitute(text) ?: text
  }

  /** The smallest element covering `[start, end)` and the range relative to it. */
  fun anchor(file: PsiFile, start: Int, end: Int): Pair<PsiElement, TextRange> {
    var element: PsiElement = file.findElementAt(start) ?: file
    while (element !is PsiFile && !element.textRange.containsRange(start, end)) {
      element = element.parent ?: file
    }
    val base = element.textRange.startOffset
    return element to TextRange(start - base, end - base)
  }
}
