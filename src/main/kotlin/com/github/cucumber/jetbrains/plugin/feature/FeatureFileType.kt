package com.github.cucumber.jetbrains.plugin.feature

import com.github.cucumber.jetbrains.plugin.CucumberIcons
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object FeatureFileType : LanguageFileType(FeatureLanguage) {

  override fun getName(): String = "Cucumber Feature"

  override fun getDescription(): String = "Cucumber feature file"

  override fun getDefaultExtension(): String = "feature"

  override fun getIcon(): Icon = CucumberIcons.CUCUMBER
}
