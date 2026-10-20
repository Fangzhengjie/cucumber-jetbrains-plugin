package com.github.cucumber.jetbrains.plugin.ide

import com.github.cucumber.jetbrains.plugin.core.Language
import com.github.cucumber.jetbrains.plugin.feature.FeatureFile
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.text.StringUtil

/** Lists the project's unused step definitions in the Find tool window. */
class FindUnusedStepDefinitionsAction : AnAction(), DumbAware {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(event: AnActionEvent) {
    event.presentation.isEnabledAndVisible = event.project != null
  }

  override fun actionPerformed(event: AnActionEvent) {
    UnusedStepDefinitions.show(event.project ?: return)
  }
}

/** Deletes the step definition at the caret (annotations and method, or call) if no step uses it. */
class DeleteUnusedStepDefinitionAction : AnAction(), DumbAware {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(event: AnActionEvent) {
    val file = event.getData(CommonDataKeys.PSI_FILE)
    event.presentation.isEnabledAndVisible =
        event.project != null &&
            event.getData(CommonDataKeys.EDITOR) != null &&
            file != null &&
            file !is FeatureFile &&
            Language.forExtension(file.viewProvider.virtualFile.extension) != null
  }

  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project ?: return
    val editor = event.getData(CommonDataKeys.EDITOR) ?: return
    val file = event.getData(CommonDataKeys.PSI_FILE) ?: return
    val found =
        stepReadAction(project, STEP_INDEX_PROGRESS) {
          val usages = StepUsageIndex.get(project)
          val definition = StepAtCaret.definition(editor, file, usages)
          if (definition == null) null else definition to usages.of(definition).size
        }
    if (found == null) {
      HintManager.getInstance().showInformationHint(editor, "No Cucumber step definition here")
      return
    }
    val (definition, used) = found
    if (used > 0) {
      HintManager.getInstance()
          .showInformationHint(editor, "This step definition is used by $used " + StringUtil.pluralize("step", used))
      return
    }
    UnusedStepDefinitions.delete(
        project, listOf(UnusedStepDefinitions.Target(file.viewProvider.virtualFile, definition.info.offset)))
  }
}
