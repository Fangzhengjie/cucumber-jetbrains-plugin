package com.github.cucumber.jetbrains.plugin.ide

import com.github.cucumber.jetbrains.plugin.core.Language
import com.github.cucumber.jetbrains.plugin.core.TagExpressions
import com.github.cucumber.jetbrains.plugin.feature.FeatureFileType
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.execution.configuration.EnvironmentVariablesTextFieldWithBrowseButton
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationWarning
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.sm.runner.SMRunnerConsolePropertiesProvider
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.JDOMExternalizerUtil
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBTextField
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.net.NetUtils
import com.intellij.util.ui.FormBuilder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import org.jdom.Element

/**
 * Runs a feature file, one scenario (`line`), a directory of features or every feature, with an
 * optional tag expression, through the project's Cucumber runner.
 */
class CucumberRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<RunProfileState>(project, factory, name),
    SMRunnerConsolePropertiesProvider {

  /** A feature file or directory (absolute, or relative to the project); blank for all features. */
  var featurePath: String = ""
  /** 1-based scenario / example line, or 0 for the whole feature. */
  var line: Int = 0
  /** Display name of the feature or scenario, for the suggested configuration name. */
  var targetName: String = ""
  var tags: String = ""
  /** [Language] name, or blank to detect the runner from the build files. */
  var language: String = ""
  /** Blank: the directory chosen by the runner (build root, package root, ...). */
  var workingDirectory: String = ""
  var extraArgs: String = ""
  var envs: MutableMap<String, String> = LinkedHashMap()

  /** Set by "Rerun Failed Tests": `path:line` targets that replace [featurePath] / [line]. */
  @Transient var rerunFeatures: List<String>? = null

  override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = Editor(project)

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
    val debugPort =
        if (executor.id == DefaultDebugExecutor.EXECUTOR_ID) {
          try {
            NetUtils.findAvailableSocketPort()
          } catch (e: IOException) {
            throw ExecutionException("No free port for the debugger", e)
          }
        } else null
    return CucumberRunState(environment, this, debugPort)
  }

  override fun createTestConsoleProperties(executor: Executor): SMTRunnerConsoleProperties =
      CucumberConsoleProperties(this, executor, null, project.basePath ?: "")

  override fun checkConfiguration() {
    val path = absoluteFeaturePath()
    if (path != null && !Files.exists(Path.of(path))) {
      throw RuntimeConfigurationError("Feature file or directory not found: $path")
    }
    if (tags.isNotBlank() && TagExpressions.parseOrNull(tags) == null) {
      throw RuntimeConfigurationWarning("'$tags' is not a valid tag expression")
    }
    if (resolvedLanguage() == null) {
      throw RuntimeConfigurationWarning("Cannot detect the Cucumber runner; choose one")
    }
  }

  override fun suggestedName(): String {
    val path = absoluteFeaturePath()
    val base =
        when {
          path == null -> "All Features"
          line > 0 -> "Scenario: " + targetName.ifBlank { Path.of(path).fileName.toString() + ":" + line }
          Files.isDirectory(Path.of(path)) -> "Features in " + Path.of(path).fileName
          else -> "Feature: " + targetName.ifBlank { Path.of(path).fileName.toString() }
        }
    return if (tags.isBlank()) base else "$base [$tags]"
  }

  override fun clone(): RunConfiguration {
    val copy = super.clone() as CucumberRunConfiguration
    copy.envs = LinkedHashMap(envs)
    copy.rerunFeatures = rerunFeatures
    return copy
  }

  /** [featurePath] made absolute (with `/`), or null for "all features". */
  fun absoluteFeaturePath(): String? {
    if (featurePath.isBlank()) {
      return null
    }
    val path = Path.of(featurePath.trim())
    val absolute = if (path.isAbsolute) path else Path.of(project.basePath ?: "").resolve(path)
    return absolute.normalize().toString().replace('\\', '/')
  }

  fun resolvedLanguage(): Language? {
    if (language.isNotBlank()) {
      Language.entries.firstOrNull { it.name == language }?.let {
        return it
      }
    }
    val base = project.basePath ?: return null
    return CucumberCommands.detectLanguage(base, absoluteFeaturePath())
  }

  /** The features to run: `path[:line]` entries, empty for every feature. */
  fun featureTargets(): List<String> {
    rerunFeatures?.let {
      return it
    }
    val path = absoluteFeaturePath() ?: return listOf()
    return listOf(if (line > 0) "$path:$line" else path)
  }

  @Throws(ExecutionException::class)
  fun request(messagesFile: String?, debugPort: Int?): CucumberCommands.Request {
    val base = project.basePath ?: throw ExecutionException("The project has no base directory")
    return CucumberCommands.Request(
        base = base,
        language = resolvedLanguage(),
        features = featureTargets(),
        tags = tags.ifBlank { null },
        extraArgs = ParametersListUtil.parse(extraArgs),
        messagesFile = messagesFile,
        debugPort = debugPort,
        gradleInitScript = CucumberCommands.gradleInitScript(),
    )
  }

  override fun readExternal(element: Element) {
    super.readExternal(element)
    featurePath = JDOMExternalizerUtil.readField(element, "FEATURE_PATH", "")
    line = JDOMExternalizerUtil.readField(element, "LINE", "0").toIntOrNull() ?: 0
    targetName = JDOMExternalizerUtil.readField(element, "TARGET_NAME", "")
    tags = JDOMExternalizerUtil.readField(element, "TAGS", "")
    language = JDOMExternalizerUtil.readField(element, "LANGUAGE", "")
    workingDirectory = JDOMExternalizerUtil.readField(element, "WORKING_DIRECTORY", "")
    extraArgs = JDOMExternalizerUtil.readField(element, "EXTRA_ARGS", "")
    envs = LinkedHashMap()
    EnvironmentVariablesComponent.readExternal(element, envs)
  }

  override fun writeExternal(element: Element) {
    super.writeExternal(element)
    JDOMExternalizerUtil.writeField(element, "FEATURE_PATH", featurePath)
    JDOMExternalizerUtil.writeField(element, "LINE", line.toString())
    JDOMExternalizerUtil.writeField(element, "TARGET_NAME", targetName)
    JDOMExternalizerUtil.writeField(element, "TAGS", tags)
    JDOMExternalizerUtil.writeField(element, "LANGUAGE", language)
    JDOMExternalizerUtil.writeField(element, "WORKING_DIRECTORY", workingDirectory)
    JDOMExternalizerUtil.writeField(element, "EXTRA_ARGS", extraArgs)
    EnvironmentVariablesComponent.writeExternal(element, envs)
  }

  private class Editor(project: Project) : SettingsEditor<CucumberRunConfiguration>() {
    private val feature = TextFieldWithBrowseButton()
    private val line = JBTextField()
    private val tags = JBTextField()
    private val language = ComboBox(arrayOf<Language?>(null) + Language.entries.toTypedArray())
    private val workingDirectory = TextFieldWithBrowseButton()
    private val extraArgs = RawCommandLineEditor()
    private val envs = EnvironmentVariablesTextFieldWithBrowseButton(project)
    private val panel: JComponent

    init {
      feature.addBrowseFolderListener(
          project,
          FileChooserDescriptorFactory.createSingleFileOrFolderDescriptor(FeatureFileType)
              .withTitle("Feature File or Directory"))
      workingDirectory.addBrowseFolderListener(
          project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Working Directory"))
      line.emptyText.text = "Whole feature"
      tags.emptyText.text = "e.g. @smoke and not @wip"
      workingDirectory.textField.let { (it as? JBTextField)?.emptyText?.text = "Automatic" }
      language.renderer =
          object : SimpleListCellRenderer<Language?>() {
            override fun customize(
                list: javax.swing.JList<out Language?>,
                value: Language?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
              text = value?.displayName() ?: "Detect automatically"
            }
          }
      panel =
          FormBuilder.createFormBuilder()
              .addLabeledComponent("Feature file or directory:", feature)
              .addLabeledComponent("Scenario line:", line)
              .addLabeledComponent("Tag expression:", tags)
              .addLabeledComponent("Runner:", language)
              .addLabeledComponent("Working directory:", workingDirectory)
              .addLabeledComponent("Runner arguments:", extraArgs)
              .addLabeledComponent("Environment variables:", envs)
              .panel
    }

    override fun resetEditorFrom(configuration: CucumberRunConfiguration) {
      feature.text = configuration.featurePath
      line.text = if (configuration.line > 0) configuration.line.toString() else ""
      tags.text = configuration.tags
      language.selectedItem = Language.entries.firstOrNull { it.name == configuration.language }
      workingDirectory.text = configuration.workingDirectory
      extraArgs.text = configuration.extraArgs
      envs.envs = configuration.envs
    }

    override fun applyEditorTo(configuration: CucumberRunConfiguration) {
      if (configuration.featurePath != feature.text.trim()) {
        configuration.targetName = ""
      }
      configuration.featurePath = feature.text.trim()
      configuration.line = line.text.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
      configuration.tags = tags.text.trim()
      configuration.language = (language.selectedItem as? Language)?.name ?: ""
      configuration.workingDirectory = workingDirectory.text.trim()
      configuration.extraArgs = extraArgs.text
      configuration.envs = LinkedHashMap(envs.envs)
    }

    override fun createEditor(): JComponent = panel
  }
}
