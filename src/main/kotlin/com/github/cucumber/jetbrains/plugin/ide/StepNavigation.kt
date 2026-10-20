package com.github.cucumber.jetbrains.plugin.ide

import com.github.cucumber.jetbrains.plugin.feature.FeatureOutlines
import com.github.cucumber.jetbrains.plugin.feature.FeatureStep
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import javax.swing.JList

/** Opens step definitions in the editor; several matches (ambiguous steps) offer a chooser. */
object StepNavigation {

  /** The valid definitions a feature step resolves to (EDT: computed under a progress). */
  fun definitions(step: FeatureStep): List<StepIndex.Definition> =
      stepReadAction(step.project, STEP_INDEX_PROGRESS) {
        if (!step.isValid || FeatureOutlines.matchText(step).isBlank()) emptyList()
        else StepIndex.get(step.project).matching(step).filter { it.file.isValid }
      } ?: emptyList()

  fun definitions(
      project: Project,
      stepText: String?,
      stepKeyword: String? = null,
  ): List<StepIndex.Definition> {
    if (stepText == null || stepText.isBlank()) {
      return emptyList()
    }
    return stepReadAction(project, STEP_INDEX_PROGRESS) {
      StepIndex.get(project).matching(stepText, stepKeyword).filter { definition ->
        definition.file.isValid
      }
    } ?: emptyList()
  }

  /** Returns false when there is nothing to navigate to. */
  fun navigate(
      project: Project,
      definitions: List<StepIndex.Definition>,
      where: RelativePoint?,
  ): Boolean {
    if (definitions.isEmpty()) {
      return false
    }
    if (definitions.size == 1 || where == null) {
      return navigate(project, definitions[0])
    }
    JBPopupFactory.getInstance()
        .createPopupChooserBuilder(definitions)
        .setTitle("Choose Step Definition")
        .setRenderer(
            object : SimpleListCellRenderer<StepIndex.Definition>() {
              override fun customize(
                  list: JList<out StepIndex.Definition>,
                  value: StepIndex.Definition?,
                  index: Int,
                  selected: Boolean,
                  hasFocus: Boolean,
              ) {
                text = if (value == null) "" else describe(value)
              }
            })
        .setItemChosenCallback { definition -> navigate(project, definition) }
        .createPopup()
        .show(where)
    return true
  }

  fun navigate(project: Project, definition: StepIndex.Definition): Boolean {
    val virtualFile = definition.file.viewProvider.virtualFile
    if (!virtualFile.isValid) {
      return false
    }
    OpenFileDescriptor(project, virtualFile, definition.info.offset).navigate(true)
    return true
  }

  fun describe(definition: StepIndex.Definition): String =
      definition.info.pattern + "  (" + definition.file.name + ")"
}
