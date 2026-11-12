package io.github.fangzhengjie.cucumber.ide

import io.github.fangzhengjie.cucumber.core.Language
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Builds the command that runs features or scenarios with the project's Cucumber runner. Shared by
 * the run configuration and the tool window; IDE-free (tested by SelfTest).
 */
object CucumberCommands {

  class CommandException(message: String) : Exception(message)

  /**
   * @param base the project directory
   * @param language the step-definition language; null detects it from build files
   * @param features absolute feature files or directories, each optionally with `:line` suffixes;
   *   empty runs every feature
   * @param tags a tag expression, or null
   * @param messagesFile where Cucumber should write its ndjson messages (when supported)
   * @param debugPort port of a listening JVM debugger the test JVM connects to (JVM runners)
   * @param gradleInitScript path of the init script that forwards settings to Gradle's test JVMs
   */
  class Request(
      val base: String,
      val language: Language?,
      val features: List<String>,
      val tags: String? = null,
      val extraArgs: List<String> = listOf(),
      val messagesFile: String? = null,
      val debugPort: Int? = null,
      val gradleInitScript: String? = null,
      val windows: Boolean = isWindows(),
  )

  private val MARKERS =
      listOf(
          "package.json" to Language.JAVASCRIPT,
          "pom.xml" to Language.JAVA,
          "build.gradle" to Language.JAVA,
          "build.gradle.kts" to Language.JAVA,
          "settings.gradle" to Language.JAVA,
          "settings.gradle.kts" to Language.JAVA,
          "behave.ini" to Language.PYTHON,
          "requirements.txt" to Language.PYTHON,
          "pyproject.toml" to Language.PYTHON,
          "setup.py" to Language.PYTHON,
          "Gemfile" to Language.RUBY,
          "go.mod" to Language.GO,
          "Cargo.toml" to Language.RUST,
      )

  fun isJvm(language: Language?): Boolean =
      language == Language.JAVA ||
          language == Language.KOTLIN ||
          language == Language.GROOVY ||
          language == Language.SCALA

  /** Whether the runner can write Cucumber Messages (needed for the test tree). */
  fun supportsMessages(language: Language?): Boolean =
      isJvm(language) || language == Language.JAVASCRIPT || language == Language.RUBY

  /** Whether the runner understands a tag expression. */
  fun supportsTags(language: Language?): Boolean =
      supportsMessages(language) || language == Language.PYTHON

  /** The runner of the closest build file above [featurePath] (inside [base]), else of [base]. */
  fun detectLanguage(base: String, featurePath: String?): Language? {
    val root = Path.of(base).toAbsolutePath().normalize()
    var dir: Path? =
        featurePath?.let { JvmCommand.localPath(JvmCommand.splitLines(it).first) } ?: root
    if (dir != null && !Files.isDirectory(dir)) {
      dir = dir.parent
    }
    while (dir != null && dir.startsWith(root)) {
      markerLanguage(dir)?.let {
        return it
      }
      dir = dir.parent
    }
    return markerLanguage(root)
  }

  private fun markerLanguage(dir: Path): Language? {
    for ((marker, language) in MARKERS) {
      if (Files.isRegularFile(dir.resolve(marker))) {
        return language
      }
    }
    return try {
      Files.list(dir).use { files ->
        if (files.anyMatch {
          it.fileName.toString().let { n -> n.endsWith(".csproj") || n.endsWith(".sln") }
        })
            Language.CSHARP
        else null
      }
    } catch (e: IOException) {
      null
    }
  }

  @Throws(CommandException::class)
  fun build(request: Request): RunCommand {
    val base = request.base
    val first = request.features.firstOrNull()
    val language =
        request.language
            ?: detectLanguage(base, first)
            ?: throw CommandException("Cannot detect the Cucumber runner for this project.")
    val tags = request.tags?.trim()?.takeIf { it.isNotEmpty() }
    val messages = request.messagesFile?.replace('\\', '/')
    val features = request.features.map { relative(base, it) }
    val args = ArrayList<String>()
    when (language) {
      Language.JAVASCRIPT -> {
        if (Files.exists(
            Path.of(base, "node_modules", "@cucumber", "cucumber", "bin", "cucumber.js"))) {
          args.addAll(listOf("node", "node_modules/@cucumber/cucumber/bin/cucumber.js"))
        } else {
          args.addAll(listOf("npx", "cucumber-js"))
        }
        args.addAll(features)
        tags?.let { args.addAll(listOf("--tags", it)) }
        messages?.let { args.addAll(listOf("--format", "message:$it")) }
      }
      Language.PYTHON -> {
        args.addAll(listOf(pythonExecutable(base, request.windows), "-m", "behave"))
        args.addAll(features)
        tags?.let { args.add("--tags=$it") }
      }
      Language.RUBY -> {
        if (Files.exists(Path.of(base, "Gemfile"))) {
          args.addAll(listOf("bundle", "exec", "cucumber"))
        } else {
          args.addAll(listOf("ruby", "-S", "cucumber"))
        }
        args.addAll(features)
        tags?.let { args.addAll(listOf("--tags", it)) }
        messages?.let {
          args.addAll(listOf("--format", "pretty", "--format", "message", "--out", it))
        }
      }
      Language.JAVA,
      Language.KOTLIN,
      Language.GROOVY,
      Language.SCALA -> {
        val options =
            JvmCommand.Options(
                tags = tags,
                plugin = messages?.let { "message:$it" },
                debugAddress = request.debugPort?.let { "127.0.0.1:$it" },
                gradleInitScript = request.gradleInitScript,
            )
        val command = JvmCommand.build(base, request.features, options, request.windows)
        return RunCommand(command.argv + request.extraArgs, command.workDir, command.env)
      }
      Language.GO ->
          return nativeCommand(
              base,
              first,
              listOf("go.mod"),
              listOf("go", "test", "./...", "-v") + request.extraArgs)
      Language.RUST ->
          return nativeCommand(
              base, first, listOf("Cargo.toml"), listOf("cargo", "test") + request.extraArgs)
      Language.CSHARP ->
          return nativeCommand(
              base,
              first,
              listOf("*.sln", "*.csproj"),
              listOf("dotnet", "test") + request.extraArgs)
      else -> throw CommandException("No runner for " + language.displayName() + " projects.")
    }
    args.addAll(request.extraArgs)
    return RunCommand(args, base, mapOf())
  }

  /** Writes [JvmCommand.GRADLE_INIT_SCRIPT] to a temporary file (once) and returns its path. */
  fun gradleInitScript(): String? =
      try {
        val dir = Path.of(System.getProperty("java.io.tmpdir"), "cucumber-jetbrains-plugin")
        Files.createDirectories(dir)
        val file = dir.resolve("cucumber-init.gradle")
        val content = JvmCommand.GRADLE_INIT_SCRIPT
        if (!Files.isRegularFile(file) ||
            Files.readString(file, StandardCharsets.UTF_8) != content) {
          Files.writeString(file, content, StandardCharsets.UTF_8)
        }
        file.toString()
      } catch (e: IOException) {
        null
      }

  /**
   * Runs [argv] in the closest directory (feature dir up to the project root) with a build file.
   */
  @Throws(CommandException::class)
  private fun nativeCommand(
      base: String,
      feature: String?,
      buildFiles: List<String>,
      argv: List<String>,
  ): RunCommand {
    val root = Path.of(base).toAbsolutePath().normalize()
    var dir: Path? = feature?.let { JvmCommand.localPath(JvmCommand.splitLines(it).first) } ?: root
    if (dir != null && !Files.isDirectory(dir)) {
      dir = dir.parent
    }
    while (dir != null && dir.startsWith(root)) {
      val current: Path = dir
      val found =
          buildFiles.any { pattern ->
            if (pattern.startsWith("*")) {
              try {
                Files.list(current).use { files ->
                  files.anyMatch { it.fileName.toString().endsWith(pattern.substring(1)) }
                }
              } catch (e: IOException) {
                false
              }
            } else {
              Files.isRegularFile(current.resolve(pattern))
            }
          }
      if (found) {
        return RunCommand(argv, current.toString(), mapOf())
      }
      dir = current.parent
    }
    throw CommandException(
        "No ${buildFiles.joinToString(" / ")} found above " +
            (feature ?: base) +
            ", so '${argv.joinToString(" ")}' cannot run here.")
  }

  /** [feature] (with its `:line` suffix) relative to [base], with `/` separators. */
  internal fun relative(base: String, feature: String): String {
    val (path, lines) = JvmCommand.splitLines(feature)
    val normalized = path.replace('\\', '/')
    val prefix = base.replace('\\', '/').trimEnd('/') + "/"
    val relative =
        if (normalized.startsWith(prefix, ignoreCase = isWindows()))
            normalized.substring(prefix.length)
        else normalized
    return relative + lines
  }

  private fun pythonExecutable(base: String, windows: Boolean): String {
    val candidates =
        if (windows) listOf(".venv/Scripts/python.exe", "venv/Scripts/python.exe")
        else listOf(".venv/bin/python", "venv/bin/python")
    for (candidate in candidates) {
      val path = Path.of(base, candidate)
      if (Files.isRegularFile(path)) {
        return path.toString()
      }
    }
    return "python"
  }

  private fun isWindows(): Boolean =
      System.getProperty("os.name", "").lowercase(Locale.ROOT).startsWith("windows")
}
