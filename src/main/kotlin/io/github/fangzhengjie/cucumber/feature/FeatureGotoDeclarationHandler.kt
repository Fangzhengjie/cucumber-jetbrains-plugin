package io.github.fangzhengjie.cucumber.feature

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.fangzhengjie.cucumber.ide.StepIndex

/** Ctrl+click / Ctrl+B anywhere on a step line (keyword included) opens its definition(s). */
class FeatureGotoDeclarationHandler : GotoDeclarationHandler {

  override fun getGotoDeclarationTargets(
      sourceElement: PsiElement?,
      offset: Int,
      editor: Editor?
  ): Array<PsiElement>? {
    if (sourceElement == null || sourceElement.containingFile !is FeatureFile) {
      return null
    }
    val step = PsiTreeUtil.getParentOfType(sourceElement, FeatureStep::class.java, false)
    if (step == null || step.stepText().isBlank()) {
      return null
    }
    val targets: Array<PsiElement> =
        StepIndex.get(step.project)
            .matching(step)
            .map { it.target }
            .filter { it.isValid }
            .distinct()
            .toTypedArray()
    return if (targets.isEmpty()) null else targets
  }
}
