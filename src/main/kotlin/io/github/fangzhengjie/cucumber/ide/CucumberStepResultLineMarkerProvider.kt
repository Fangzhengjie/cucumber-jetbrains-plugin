package io.github.fangzhengjie.cucumber.ide

import io.github.fangzhengjie.cucumber.core.RunResults
import io.github.fangzhengjie.cucumber.feature.FeatureTokenTypes
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement

/**
 * Marks the steps that failed (or were undefined, ambiguous or pending) in the last Cucumber run,
 * with the message as tooltip. The marker disappears once the step line is edited.
 */
class CucumberStepResultLineMarkerProvider : LineMarkerProvider {

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    if (element.node?.elementType != FeatureTokenTypes.STEP_KEYWORD) {
      return null
    }
    val file = element.containingFile ?: return null
    val virtualFile = file.virtualFile ?: return null
    val results = CucumberRunResults.getInstance(element.project)
    if (!results.hasResults(virtualFile)) {
      return null
    }
    val document = PsiDocumentManager.getInstance(element.project).getDocument(file) ?: return null
    val line = document.getLineNumber(element.textRange.startOffset) + 1
    val result =
        results.resultAt(virtualFile, line, CucumberRunLineMarkerContributor.lineText(document, line))
            ?: return null
    val icon =
        when (result.status) {
          RunResults.Status.FAILED,
          RunResults.Status.AMBIGUOUS -> AllIcons.RunConfigurations.TestFailed
          RunResults.Status.UNDEFINED,
          RunResults.Status.PENDING -> AllIcons.RunConfigurations.TestIgnored
          RunResults.Status.PASSED,
          RunResults.Status.SKIPPED -> return null
        }
    val tooltip = "Last run: step " + StringUtil.escapeXmlEntities(result.describe().replaceFirstChar { it.lowercase() })
    return LineMarkerInfo(
        element,
        element.textRange,
        icon,
        { tooltip },
        null,
        GutterIconRenderer.Alignment.RIGHT,
        { "Step ${result.status.label} in the last run" })
  }
}
