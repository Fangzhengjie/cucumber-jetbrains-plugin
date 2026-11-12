package io.github.fangzhengjie.cucumber.ide

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.fangzhengjie.cucumber.core.Language
import io.github.fangzhengjie.cucumber.feature.FeatureFile
import io.github.fangzhengjie.cucumber.feature.FeatureStep

/** What the caret is on: a step in a feature file, or a step definition in code. */
internal object StepAtCaret {

  fun featureStep(editor: Editor, file: PsiFile): FeatureStep? {
    val offset = editor.caretModel.offset
    return PsiTreeUtil.getParentOfType(file.findElementAt(offset), FeatureStep::class.java, false)
        ?: if (offset > 0)
            PsiTreeUtil.getParentOfType(
                file.findElementAt(offset - 1), FeatureStep::class.java, false)
        else null
  }

  /**
   * Cheap check for action updates (no index is built): a non-blank feature step at the caret, or a
   * file in a step definition language.
   */
  fun mayHaveStep(editor: Editor, file: PsiFile): Boolean {
    if (file is FeatureFile) {
      return featureStep(editor, file)?.stepText()?.isNotBlank() == true
    }
    return Language.forExtension(file.viewProvider.virtualFile.extension) != null
  }

  fun definition(
      editor: Editor,
      file: PsiFile,
      usages: StepUsageIndex.Usages,
  ): StepIndex.Definition? {
    val virtualFile = file.viewProvider.virtualFile
    if (usages.definitionsIn(virtualFile).isEmpty()) {
      return null
    }
    return StepUsageIndex.definitionAt(
        usages, virtualFile, editor.document.immutableCharSequence, editor.caretModel.offset)
  }
}

/**
 * Lists the feature steps using the step definition at the caret (or, in a feature file, the
 * definitions of the step at the caret) in a popup.
 */
class FindStepUsagesAction : AnAction(), DumbAware {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(event: AnActionEvent) {
    val editor = event.getData(CommonDataKeys.EDITOR)
    val file = event.getData(CommonDataKeys.PSI_FILE)
    event.presentation.isEnabledAndVisible =
        event.project != null &&
            editor != null &&
            file != null &&
            StepAtCaret.mayHaveStep(editor, file)
  }

  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    val editor = event.getData(CommonDataKeys.EDITOR) ?: return
    val file = event.getData(CommonDataKeys.PSI_FILE) ?: return
    val usages = stepReadAction(project, STEP_INDEX_PROGRESS) { usages(editor, file) }
    if (usages == null) {
      HintManager.getInstance().showInformationHint(editor, "No Cucumber step definition here")
      return
    }
    StepUsagesPopup.show(project, usages, "Step Usages", editor, null)
  }

  private fun usages(editor: Editor, file: PsiFile): List<StepUsageIndex.Usage>? {
    val all = StepUsageIndex.get(file.project)
    val step = StepAtCaret.featureStep(editor, file)
    if (step != null) {
      val definitions = all.index.matching(step)
      return if (definitions.isEmpty()) null else definitions.flatMap { all.of(it) }.distinct()
    }
    val definition = StepAtCaret.definition(editor, file, all) ?: return null
    return all.of(definition)
  }
}
