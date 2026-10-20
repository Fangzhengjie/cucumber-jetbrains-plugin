package com.github.cucumber.jetbrains.plugin.ide

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import javax.swing.JList

/** Shows the feature steps using a step definition and opens the chosen one. */
object StepUsagesPopup {

  fun navigate(project: Project, usage: StepUsageIndex.Usage) {
    if (usage.file.isValid) {
      OpenFileDescriptor(project, usage.file, usage.textOffset).navigate(true)
    }
  }

  /** Opens a single usage directly; several are offered in a chooser at `where` or the editor caret. */
  fun show(
      project: Project,
      usages: List<StepUsageIndex.Usage>,
      title: String,
      editor: Editor?,
      where: RelativePoint?,
  ) {
    if (usages.isEmpty()) {
      if (editor != null) {
        HintManager.getInstance()
            .showInformationHint(editor, "No feature step uses this step definition")
      }
      return
    }
    if (usages.size == 1 && where != null) {
      navigate(project, usages[0])
      return
    }
    val popup = chooser(project, usages, title)
    when {
      where != null -> popup.show(where)
      editor != null -> popup.showInBestPositionFor(editor)
      else -> popup.showCenteredInCurrentWindow(project)
    }
  }

  private fun chooser(project: Project, usages: List<StepUsageIndex.Usage>, title: String): JBPopup =
      JBPopupFactory.getInstance()
          .createPopupChooserBuilder(usages.sortedWith(compareBy({ it.file.path }, { it.line })))
          .setTitle(title)
          .setRenderer(
              object : SimpleListCellRenderer<StepUsageIndex.Usage>() {
                override fun customize(
                    list: JList<out StepUsageIndex.Usage>,
                    value: StepUsageIndex.Usage?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                  text = value?.describe() ?: ""
                }
              })
          .setNamerForFiltering { it.describe() }
          .setItemChosenCallback { usage -> navigate(project, usage) }
          .createPopup()
}
