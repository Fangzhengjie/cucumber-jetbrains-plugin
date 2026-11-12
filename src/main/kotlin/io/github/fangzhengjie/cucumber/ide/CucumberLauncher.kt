package io.github.fangzhengjie.cucumber.ide

import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import io.github.fangzhengjie.cucumber.core.Language

/** Runs features through (temporary) Cucumber run configurations, e.g. from the tool window. */
object CucumberLauncher {

  /**
   * @param path absolute feature file or directory, or null for every feature
   * @param line 1-based scenario line, or 0 for the whole feature
   */
  fun run(
      project: Project,
      path: String?,
      line: Int,
      name: String,
      language: Language?,
      tags: String?,
      executor: Executor = DefaultRunExecutor.getRunExecutorInstance(),
  ) {
    val runManager = RunManager.getInstance(project)
    val type = CucumberRunConfigurationType.instance()
    val featurePath = path?.replace('\\', '/') ?: ""
    val tagText = tags?.trim() ?: ""
    val languageName = language?.name ?: ""
    val existing =
        runManager.getConfigurationSettingsList(type).firstOrNull {
          val configuration = it.configuration as? CucumberRunConfiguration
          configuration != null &&
              (configuration.absoluteFeaturePath() ?: "") == featurePath &&
              configuration.line == line &&
              configuration.tags == tagText &&
              configuration.language == languageName
        }
    val settings =
        if (existing != null) {
          existing
        } else {
          val created = runManager.createConfiguration("Cucumber", type.factory())
          val configuration = created.configuration as CucumberRunConfiguration
          configuration.featurePath = featurePath
          configuration.line = line
          configuration.targetName = name
          configuration.tags = tagText
          configuration.language = languageName
          configuration.setGeneratedName()
          created.isTemporary = true
          runManager.setTemporaryConfiguration(created)
          created
        }
    runManager.selectedConfiguration = settings
    // ProgramRunnerUtil.executeConfiguration(settings, executor) silently does nothing without a
    // runner.
    val builder = ExecutionEnvironmentBuilder.createOrNull(executor, settings)
    if (builder == null) {
      notifyFailure(
          project,
          "No runner can ${executor.actionName.lowercase()} '${settings.name}' in this IDE.")
      return
    }
    ProgramRunnerUtil.executeConfiguration(builder.build(), true, true)
  }

  /** Reports a run that could not start (a balloon in the project frame). */
  fun notifyFailure(project: Project, message: String) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup(NOTIFICATION_GROUP)
        .createNotification("Cucumber run failed to start", message, NotificationType.ERROR)
        .notify(project)
  }

  private const val NOTIFICATION_GROUP = "Cucumber Run"
}
