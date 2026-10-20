package com.github.cucumber.jetbrains.plugin.ide

import com.github.cucumber.jetbrains.plugin.CucumberIcons
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project

/** The "Cucumber" run configuration type: runs features, scenarios or folders of features. */
class CucumberRunConfigurationType :
    ConfigurationTypeBase(
        ID, "Cucumber", "Run Cucumber features and scenarios", CucumberIcons.CUCUMBER) {

  init {
    addFactory(Factory(this))
  }

  fun factory(): ConfigurationFactory = configurationFactories[0]

  private class Factory(type: CucumberRunConfigurationType) : ConfigurationFactory(type) {

    override fun getId(): String = ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        CucumberRunConfiguration(project, this, "Cucumber")
  }

  companion object {
    const val ID = "CucumberStandaloneRunConfiguration"

    fun instance(): CucumberRunConfigurationType =
        ConfigurationTypeUtil.findConfigurationType(CucumberRunConfigurationType::class.java)
  }
}
