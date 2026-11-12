package io.github.fangzhengjie.cucumber.ide

import io.github.fangzhengjie.cucumber.core.StepRename
import io.github.fangzhengjie.cucumber.feature.FeatureOutlines
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * The step text to rename at the caret, the concrete text it stands for (Scenario Outline values
 * substituted) and the step definition it belongs to, when known.
 */
private data class RenameTarget(
    val text: String,
    val definition: StepIndex.Definition?,
    val matchText: String = text,
)

private fun renameTarget(editor: Editor, file: PsiFile): RenameTarget? {
  val step = StepAtCaret.featureStep(editor, file)
  if (step != null) {
    val text = step.stepText()
    if (text.isBlank()) {
      return null
    }
    // Resolve with the step's keyword and Outline values; several matches stay unresolved.
    return RenameTarget(
        text, StepIndex.get(file.project).matching(step).singleOrNull(), FeatureOutlines.matchText(step))
  }
  val usages = StepUsageIndex.get(file.project)
  val definition = StepAtCaret.definition(editor, file, usages) ?: return null
  val usage = usages.of(definition).firstOrNull()
  val text =
      usage?.text
          ?: StepRename.literalText(definition.info.kind, definition.info.pattern)
          ?: return null
  return RenameTarget(text, definition, usage?.matchText ?: text)
}

/**
 * Renames the step at the caret in every feature file (and its step definition pattern when it can
 * be rewritten safely); in code, renames the step definition at the caret and the steps using it.
 */
class RenameStepAction : AnAction(), DumbAware {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(event: AnActionEvent) {
    val editor = event.getData(CommonDataKeys.EDITOR)
    val file = event.getData(CommonDataKeys.PSI_FILE)
    event.presentation.isEnabledAndVisible =
        event.project != null && editor != null && file != null && StepAtCaret.mayHaveStep(editor, file)
  }

  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    val editor = event.getData(CommonDataKeys.EDITOR) ?: return
    val file = event.getData(CommonDataKeys.PSI_FILE) ?: return
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val target = stepReadAction(project, STEP_INDEX_PROGRESS) { renameTarget(editor, file) }
    if (target == null) {
      HintManager.getInstance().showInformationHint(editor, "No Cucumber step here")
      return
    }
    StepRenamer.rename(project, editor, target.text, target.definition, target.matchText)
  }
}

/** Alt+Enter on a feature step: "Rename step everywhere...". */
class RenameStepIntention : IntentionAction {

  override fun getText(): String = "Rename step everywhere..."

  override fun getFamilyName(): String = "Rename Cucumber step everywhere"

  override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
      editor != null && file != null && StepAtCaret.featureStep(editor, file)?.stepText()?.isNotBlank() == true

  override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
    if (editor == null || file == null) {
      return
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val target =
        stepReadAction(project, STEP_INDEX_PROGRESS) { renameTarget(editor, file) } ?: return
    StepRenamer.rename(project, editor, target.text, target.definition, target.matchText)
  }

  override fun startInWriteAction(): Boolean = false

  override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
      IntentionPreviewInfo.EMPTY
}
