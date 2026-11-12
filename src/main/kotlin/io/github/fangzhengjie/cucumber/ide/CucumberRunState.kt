package io.github.fangzhengjie.cucumber.ide

import io.github.fangzhengjie.cucumber.core.CucumberMessages
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.actions.AbstractRerunFailedTestsAction
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComponentContainer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.execution.ParametersListUtil
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Starts the Cucumber process and shows its results as a test tree (or a plain console). */
class CucumberRunState(
    val environment: ExecutionEnvironment,
    private val configuration: CucumberRunConfiguration,
    /** Port the IDE debugger listens on; the test JVM connects to it. */
    val debugPort: Int?,
) : RunProfileState {

  override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
    val language = configuration.resolvedLanguage()
    val messages: Path? =
        if (CucumberCommands.supportsMessages(language)) {
          try {
            Files.createTempFile("cucumber-", ".ndjson")
          } catch (e: IOException) {
            null
          }
        } else null
    val request = configuration.request(messages?.toString(), debugPort)
    val command =
        try {
          CucumberCommands.build(request)
        } catch (e: CucumberCommands.CommandException) {
          messages?.let { Files.deleteIfExists(it) }
          throw ExecutionException(e.message)
        }
    val workDir = configuration.workingDirectory.ifBlank { command.workDir }
    val commandLine =
        CucumberProcesses.commandLine(
            RunCommand(command.argv, workDir, command.env + configuration.envs))
    val handler: ProcessHandler = KillableColoredProcessHandler(commandLine)
    ProcessTerminatedListener.attach(handler)
    val shown = "> " + ParametersListUtil.join(command.argv) + "\n  (in " + workDir + ")\n"

    if (messages == null) {
      val console = TextConsoleBuilderFactory.getInstance().createBuilder(configuration.project).console
      console.print(shown, ConsoleViewContentType.SYSTEM_OUTPUT)
      console.attachToProcess(handler)
      return DefaultExecutionResult(console, handler)
    }
    val properties = CucumberConsoleProperties(configuration, executor, messages, workDir)
    val console =
        SMTestRunnerConnectionUtil.createAndAttachConsole("Cucumber", handler, properties)
            as SMTRunnerConsoleView
    console.print(shown, ConsoleViewContentType.SYSTEM_OUTPUT)
    val result = DefaultExecutionResult(console, handler)
    val rerun = properties.createRerunFailedTestsAction(console)
    rerun.setModelProvider { console.resultsViewer }
    result.setRestartActions(rerun)
    return result
  }
}

/** Test-tree settings: Cucumber events from the messages file, `cucumber://` navigation. */
class CucumberConsoleProperties(
    configuration: CucumberRunConfiguration,
    executor: Executor,
    private val messagesFile: Path?,
    private val baseDir: String,
) : SMTRunnerConsoleProperties(configuration, "Cucumber", executor), SMCustomMessagesParsing {

  init {
    isIdBasedTestTree = true
  }

  override fun getTestLocator(): SMTestLocator = CucumberTestLocator

  override fun createTestEventsConverter(
      testFrameworkName: String,
      consoleProperties: TestConsoleProperties,
  ): OutputToGeneralTestEventsConverter =
      CucumberEventsConverter(testFrameworkName, consoleProperties, messagesFile, baseDir)

  override fun createRerunFailedTestsAction(consoleView: ConsoleView): AbstractRerunFailedTestsAction =
      CucumberRerunFailedTestsAction(consoleView as ComponentContainer, this)
}

/**
 * Reads the Cucumber Messages file while the process runs and feeds the converted events to the
 * test tree as service messages; the process output itself is shown unchanged.
 */
class CucumberEventsConverter(
    testFrameworkName: String,
    consoleProperties: TestConsoleProperties,
    private val messagesFile: Path?,
    baseDir: String,
) : OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties) {

  private val lock = Any()
  private val converter = CucumberMessages.Converter(baseDir.replace('\\', '/'))
  private val follower = messagesFile?.let { CucumberMessages.FileFollower(it) }
  private val results = CucumberRunResults.Feed(consoleProperties.project, converter)
  private var done = false
  @Volatile private var poller: ScheduledFuture<*>? = null

  override fun onStartTesting() {
    super.onStartTesting()
    if (messagesFile != null) {
      poller =
          AppExecutorUtil.getAppScheduledExecutorService()
              .scheduleWithFixedDelay({ poll(false) }, 200, 250, TimeUnit.MILLISECONDS)
    }
  }

  override fun processConsistentText(text: String, outputType: Key<*>) {
    synchronized(lock) { super.processConsistentText(text, outputType) }
  }

  override fun flushBufferOnProcessTermination(exitCode: Int) {
    poller?.cancel(false)
    poll(true)
    super.flushBufferOnProcessTermination(exitCode)
  }

  override fun dispose() {
    poller?.cancel(false)
    synchronized(lock) {
      done = true
      deleteFile()
    }
    super.dispose()
  }

  private fun poll(last: Boolean) {
    synchronized(lock) {
      if (done || messagesFile == null) {
        return
      }
      try {
        read()
      } catch (e: IOException) {
        LOG.debug("Cannot read " + messagesFile, e)
      } catch (e: RuntimeException) {
        LOG.warn("Cannot convert Cucumber messages", e)
      }
      if (last) {
        follower?.rest()?.let {
          emit(converter.accept(it))
          results.accept(it)
        }
        emit(converter.finish())
        done = true
        deleteFile()
      }
      publishResults(last)
    }
  }

  private fun read() {
    // A restart: the next Cucumber run (another runner class or module) rewrote the file.
    follower?.read({
      emit(converter.restart())
      results.restart()
    }) { line ->
      emit(converter.accept(line))
      results.accept(line)
    }
  }

  private fun publishResults(last: Boolean) {
    try {
      results.publish(last)
    } catch (e: RuntimeException) {
      LOG.warn("Cannot store Cucumber run results", e)
    }
  }

  private fun emit(events: List<CucumberMessages.Event>) {
    for (event in events) {
      // Locations as the IDE spells paths: the gutter looks up the stored test states by them.
      val located =
          when (event) {
            is CucumberMessages.SuiteStarted ->
                event.copy(location = event.location?.let { CucumberRunResults.canonicalLocation(it) })
            is CucumberMessages.TestStarted ->
                event.copy(location = event.location?.let { CucumberRunResults.canonicalLocation(it) })
            else -> event
          }
      processConsistentText(located.serviceMessage() + "\n", ProcessOutputTypes.STDOUT)
    }
  }

  private fun deleteFile() {
    try {
      messagesFile?.let { Files.deleteIfExists(it) }
    } catch (ignored: IOException) {
      // a temp file; the OS cleans it up eventually
    }
  }

  private companion object {
    val LOG = Logger.getInstance(CucumberEventsConverter::class.java)
  }
}

/** Resolves `cucumber://<path>:<line>` test locations to the feature line. */
object CucumberTestLocator : SMTestLocator {

  override fun getLocation(
      protocol: String,
      path: String,
      project: Project,
      scope: GlobalSearchScope,
  ): List<Location<*>> {
    if (protocol != CucumberMessages.PROTOCOL) {
      return emptyList()
    }
    val (filePath, line) = CucumberMessages.parseLocation(path) ?: (path to 0)
    val file = findFile(project, filePath) ?: return emptyList()
    val psiFile = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
    val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
    if (document == null || line < 1 || line > document.lineCount) {
      return listOf(PsiLocation(project, psiFile))
    }
    var offset = document.getLineStartOffset(line - 1)
    val end = document.getLineEndOffset(line - 1)
    val text = document.charsSequence
    while (offset < end && text[offset].isWhitespace()) {
      offset++
    }
    val element = psiFile.findElementAt(offset) ?: psiFile
    return listOf(PsiLocation(project, element))
  }

  /** A file path, or a `classpath:` resource found among the project's files. */
  fun findFile(project: Project, path: String): VirtualFile? {
    if (!path.startsWith("classpath:")) {
      return LocalFileSystem.getInstance().findFileByPath(path)
    }
    val resource = "/" + path.removePrefix("classpath:").trimStart('/')
    val name = resource.substringAfterLast('/')
    return FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))
        .filter { it.path.endsWith(resource) }
        .minByOrNull { if (it.path.contains("/src/test/")) 0 else 1 }
  }
}

/** Reruns the scenarios (feature `path:line`s) that contain failed steps. */
class CucumberRerunFailedTestsAction(
    container: ComponentContainer,
    properties: TestConsoleProperties,
) : AbstractRerunFailedTestsAction(container) {

  init {
    init(properties)
  }

  override fun getRunProfile(environment: ExecutionEnvironment): MyRunProfile? {
    val configuration = myConsoleProperties.configuration as? CucumberRunConfiguration ?: return null
    val targets = LinkedHashSet<String>()
    for (test in getFailedTests(configuration.project)) {
      val scenario = scenarioOf(test) ?: continue
      val url = scenario.locationUrl ?: continue
      val prefix = CucumberMessages.PROTOCOL + "://"
      if (url.startsWith(prefix)) {
        val location = url.substring(prefix.length)
        val parsed = CucumberMessages.parseLocation(location)
        if (parsed != null && parsed.first.startsWith("classpath:")) {
          val file =
              ReadAction.computeBlocking<VirtualFile?, RuntimeException> {
                CucumberTestLocator.findFile(configuration.project, parsed.first)
              }
          targets.add(if (file != null) file.path + ":" + parsed.second else location)
        } else {
          targets.add(location)
        }
      }
    }
    if (targets.isEmpty()) {
      return null
    }
    val copy = configuration.clone() as CucumberRunConfiguration
    copy.rerunFeatures = targets.toList()
    return object : MyRunProfile(copy) {
      override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
          copy.getState(executor, environment)
    }
  }

  /** Tree levels: root -> feature -> scenario -> step. */
  private fun scenarioOf(test: AbstractTestProxy): AbstractTestProxy? {
    var node: AbstractTestProxy? = test
    while (node != null) {
      val parent = node.parent
      if (parent != null && parent.parent != null && parent.parent.parent == null) {
        return node
      }
      node = parent
    }
    return null
  }
}

/** Turns a [RunCommand] into a [GeneralCommandLine] (wrapping `.cmd` / `.bat` on Windows). */
object CucumberProcesses {

  /**
   * Set to `%` for commands run through cmd.exe. [GeneralCommandLine] escapes cmd's special
   * characters, but nothing on a cmd command line stops `%NAME%` from expanding; writing each `%`
   * as `%CUCUMBER_PLUGIN_PERCENT%` does, since cmd expands variables in a single pass.
   */
  private const val PERCENT_VARIABLE = "CUCUMBER_PLUGIN_PERCENT"

  @Throws(ExecutionException::class)
  fun commandLine(command: RunCommand): GeneralCommandLine {
    val argv = command.argv
    val base = command.workDir
    if (argv.isEmpty()) {
      throw ExecutionException("Nothing to run")
    }
    val executable = resolveExecutable(argv[0], base)
    val rest = argv.subList(1, argv.size)
    val lower = executable.lowercase()
    val viaCmd = SystemInfo.isWindows && (lower.endsWith(".cmd") || lower.endsWith(".bat"))
    // GeneralCommandLine runs a .bat / .cmd through cmd.exe and escapes everything but `%`.
    val parameters = if (viaCmd) rest.map { it.replace("%", "%$PERCENT_VARIABLE%") } else rest
    val environment = if (viaCmd) command.env + (PERCENT_VARIABLE to "%") else command.env
    return GeneralCommandLine(executable)
        .withParameters(parameters)
        .withWorkingDirectory(Path.of(base))
        .withEnvironment(environment)
        .withCharset(StandardCharsets.UTF_8)
        .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
  }

  private fun resolveExecutable(executable: String, base: String): String {
    if (executable.contains("/") || executable.contains("\\")) {
      val path = Path.of(executable)
      return (if (path.isAbsolute) path else Path.of(base).resolve(path)).toString()
    }
    if (!SystemInfo.isWindows) {
      return executable
    }
    val pathVariable = System.getenv("PATH") ?: return executable
    val pathExt = System.getenv("PATHEXT")
    val extensions = ArrayList<String>()
    if (executable.contains(".")) {
      extensions.add("")
    }
    extensions.addAll(
        (pathExt ?: ".COM;.EXE;.BAT;.CMD").lowercase().split(";").dropLastWhile { it.isEmpty() })
    for (directory in pathVariable.split(File.pathSeparator)) {
      if (directory.isBlank()) {
        continue
      }
      for (extension in extensions) {
        try {
          val candidate = Path.of(directory.trim(), executable + extension)
          if (Files.isRegularFile(candidate)) {
            return candidate.toString()
          }
        } catch (ignored: RuntimeException) {
          // invalid PATH entry
        }
      }
    }
    return executable
  }
}
