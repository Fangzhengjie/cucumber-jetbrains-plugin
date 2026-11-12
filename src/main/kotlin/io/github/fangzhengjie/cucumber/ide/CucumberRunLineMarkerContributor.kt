package io.github.fangzhengjie.cucumber.ide

import com.intellij.execution.TestStateStorage
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.fangzhengjie.cucumber.core.CucumberMessages
import io.github.fangzhengjie.cucumber.core.FeatureOutline
import io.github.fangzhengjie.cucumber.core.GherkinKeywordType
import io.github.fangzhengjie.cucumber.core.RunResults
import io.github.fangzhengjie.cucumber.feature.FeatureFile
import io.github.fangzhengjie.cucumber.feature.FeatureTokenTypes
import java.text.DateFormat
import javax.swing.Icon

/**
 * Gutter Run / Debug / Modify actions on `Feature:`, `Scenario:` / `Scenario Outline:` and example
 * row lines, backed by the Cucumber run configuration producer. The icon shows the line's last
 * result ([CucumberRunResults], else the platform's test state storage) and the tooltip the
 * failure.
 */
class CucumberRunLineMarkerContributor : RunLineMarkerContributor() {

  override fun getInfo(element: PsiElement): Info? {
    val node = element.node ?: return null
    val file = element.containingFile as? FeatureFile ?: return null
    val feature: Boolean
    if (node.elementType == FeatureTokenTypes.KEYWORD) {
      val type = file.dialect().sectionType(element.text) ?: return null
      feature = type == GherkinKeywordType.FEATURE
      if (!feature &&
          type != GherkinKeywordType.SCENARIO &&
          type != GherkinKeywordType.SCENARIO_OUTLINE) {
        return null
      }
    } else if (node.elementType == FeatureTokenTypes.TABLE && isExampleRow(element, file)) {
      feature = false
    } else {
      return null
    }
    val default =
        if (feature) AllIcons.RunConfigurations.TestState.Run_run
        else AllIcons.RunConfigurations.TestState.Run
    val state = lastState(element)
    val actions = ExecutorAction.getActions(1)
    if (state == null) {
      return Info(default, actions, RUN_TEST_TOOLTIP_PROVIDER)
    }
    return Info(
        state.icon,
        actions,
        { e: PsiElement ->
          val run = RUN_TEST_TOOLTIP_PROVIDER.apply(e)
          if (run.isNullOrEmpty()) state.text else state.text + "\n" + run
        })
  }

  private class LastState(val icon: Icon, val text: String)

  private fun lastState(element: PsiElement): LastState? {
    val file = element.containingFile ?: return null
    val virtualFile = file.virtualFile ?: return null
    val document = PsiDocumentManager.getInstance(element.project).getDocument(file) ?: return null
    val line = document.getLineNumber(element.textRange.startOffset) + 1
    val results = CucumberRunResults.getInstance(element.project)
    val result = results.resultAt(virtualFile, line, lineText(document, line))
    if (result != null) {
      return LastState(icon(result.status), "Last run: " + result.describe())
    }
    if (results.hasResults(virtualFile)) {
      return null // the file has results but this line changed (or did not run) since
    }
    // E.g. runs from before this plugin kept its own results: the platform's per-test record.
    val record =
        TestStateStorage.getInstance(element.project)
            .getState(CucumberMessages.location(virtualFile.path, line)) ?: return null
    val status =
        when (record.magnitude) {
          MAGNITUDE_PASSED -> RunResults.Status.PASSED
          MAGNITUDE_FAILED,
          MAGNITUDE_ERROR -> RunResults.Status.FAILED
          MAGNITUDE_IGNORED -> RunResults.Status.SKIPPED
          else -> return null
        }
    val text = StringBuilder("Last run: ").append(status.label)
    record.date?.let {
      text.append(" (").append(DateFormat.getDateTimeInstance().format(it)).append(')')
    }
    RunResults.headline(record.errorMessage)?.let { text.append(": ").append(it) }
    return LastState(icon(status), text.toString())
  }

  private fun isExampleRow(element: PsiElement, file: PsiFile): Boolean {
    val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return false
    val offset = element.textRange.startOffset
    val line = document.getLineNumber(offset)
    val start = document.getLineStartOffset(line)
    if (document.charsSequence.subSequence(start, offset).isNotBlank()) {
      return false // not the row's first `|`
    }
    return line + 1 in exampleRows(file)
  }

  companion object {
    // TestStateStorage.Record.magnitude values. They are part of the stored test state format; the
    // enum that names them (TestStateInfo.Magnitude) is internal API.
    private const val MAGNITUDE_PASSED = 1
    private const val MAGNITUDE_IGNORED = 5
    private const val MAGNITUDE_FAILED = 6
    private const val MAGNITUDE_ERROR = 8

    /** The icon of a last result: green, red, or yellow for skipped / pending / undefined. */
    fun icon(status: RunResults.Status): Icon =
        when (status) {
          RunResults.Status.PASSED -> AllIcons.RunConfigurations.TestState.Green2
          RunResults.Status.FAILED,
          RunResults.Status.AMBIGUOUS -> AllIcons.RunConfigurations.TestState.Red2
          RunResults.Status.SKIPPED,
          RunResults.Status.PENDING,
          RunResults.Status.UNDEFINED -> AllIcons.RunConfigurations.TestState.Yellow2
        }

    fun lineText(document: Document, line: Int): CharSequence {
      if (line < 1 || line > document.lineCount) {
        return ""
      }
      return TextRange(document.getLineStartOffset(line - 1), document.getLineEndOffset(line - 1))
          .subSequence(document.charsSequence)
    }

    /** 1-based lines of the file's example rows. */
    private fun exampleRows(file: PsiFile): Set<Int> =
        CachedValuesManager.getCachedValue(file) {
          val rows = HashSet<Int>()
          for (scenario in FeatureOutline.parse(file.text).scenarios) {
            scenario.examples.mapTo(rows) { it.line }
          }
          CachedValueProvider.Result.create(rows, file)
        }
  }
}
