package io.github.fangzhengjie.cucumber.feature

import io.github.fangzhengjie.cucumber.ide.StepIndex
import io.github.fangzhengjie.cucumber.ide.StepNavigation
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.ui.awt.RelativePoint
import java.awt.event.MouseEvent

/** Gutter icon on each defined step; click it to jump to the step definition in code. */
class FeatureStepLineMarkerProvider : LineMarkerProvider {

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    val node = element.node ?: return null
    if (node.elementType != FeatureTokenTypes.STEP_KEYWORD) {
      return null
    }
    val step = element.parent as? FeatureStep ?: return null
    val definitions: List<StepIndex.Definition> = StepIndex.get(element.project).matching(step)
    if (definitions.isEmpty()) {
      return null
    }
    val tooltip =
        if (definitions.size == 1) "Go to step definition: " + StepNavigation.describe(definitions[0])
        else "Go to step definition (" + definitions.size + " matches)"
    return LineMarkerInfo(
        element,
        element.textRange,
        AllIcons.Gutter.ImplementedMethod,
        { tooltip },
        { mouseEvent: MouseEvent, keyword: PsiElement ->
          val current = keyword.parent
          if (current is FeatureStep) {
            StepNavigation.navigate(
                current.project,
                StepNavigation.definitions(current),
                RelativePoint(mouseEvent))
          }
        },
        GutterIconRenderer.Alignment.LEFT,
        { "Go to step definition" })
  }
}
