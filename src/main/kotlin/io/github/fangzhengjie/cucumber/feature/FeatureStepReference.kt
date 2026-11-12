package io.github.fangzhengjie.cucumber.feature

import io.github.fangzhengjie.cucumber.ide.StepIndex
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.ResolveResult
import kotlin.math.max

/** Ctrl+click from a step to its definition(s); ambiguous steps offer a chooser. */
internal class FeatureStepReference(step: FeatureStep) :
    PsiPolyVariantReferenceBase<FeatureStep>(step, range(step), true) {

  override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
    if (FeatureOutlines.matchText(element).isBlank()) {
      return ResolveResult.EMPTY_ARRAY
    }
    return StepIndex.get(element.project)
        .matching(element)
        .map { it.target }
        .filter { it.isValid }
        .distinct()
        .map { PsiElementResolveResult(it) }
        .toTypedArray<ResolveResult>()
  }

  /** Step text completion is FeatureStepCompletion's (readable text, live templates). */
  override fun getVariants(): Array<Any> = emptyArray()

  private companion object {
    fun range(step: FeatureStep): TextRange {
      val start = max(0, step.stepTextOffset() - step.textRange.startOffset)
      val length = step.stepText().length
      if (length == 0 || start + length > step.textLength) {
        return TextRange.from(0, step.textLength)
      }
      return TextRange.from(start, length)
    }
  }
}
