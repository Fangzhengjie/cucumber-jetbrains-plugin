package com.github.cucumber.jetbrains.plugin.ide

import com.github.cucumber.jetbrains.plugin.core.Language
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiManager
import java.awt.BorderLayout
import java.util.EnumMap
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Settings | Tools | Cucumber. All languages are detected automatically; this page only lets the
 * user exclude languages they do not want steps resolved from.
 */
class CucumberConfigurable : Configurable {

  private val checkBoxes: MutableMap<Language, JCheckBox> = EnumMap(Language::class.java)

  override fun getDisplayName(): String = "Cucumber"

  override fun createComponent(): JComponent {
    val list = JPanel()
    list.layout = BoxLayout(list, BoxLayout.Y_AXIS)
    list.add(
        JLabel(
            "The step language and test runner are detected automatically. Uncheck a language to exclude it:"))
    list.add(JLabel(" "))
    for (language in Language.values()) {
      val checkBox = JCheckBox(language.displayName())
      checkBoxes[language] = checkBox
      list.add(checkBox)
    }
    val panel = JPanel(BorderLayout())
    panel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    panel.add(list, BorderLayout.NORTH)
    reset()
    return panel
  }

  override fun isModified(): Boolean =
      checkBoxes.entries.any { (language, checkBox) ->
        checkBox.isSelected != LanguageAvailability.isEnabled(language)
      }

  override fun apply() {
    val settings = CucumberSettings.getInstance()
    checkBoxes.forEach { (language, checkBox) ->
      settings.setOverride(language, if (checkBox.isSelected) null else false)
    }
    for (project in ProjectManager.getInstance().openProjects) {
      PsiManager.getInstance(project).dropPsiCaches()
      DaemonCodeAnalyzer.getInstance(project).restart("Cucumber settings changed")
    }
  }

  override fun reset() {
    checkBoxes.forEach { (language, checkBox) ->
      checkBox.isSelected = LanguageAvailability.isEnabled(language)
    }
  }
}
