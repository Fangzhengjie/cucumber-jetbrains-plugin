package com.github.cucumber.jetbrains.plugin.ide

/**
 * A process to start: command line, working directory and extra environment variables.
 *
 * Value class (like a data class) that keeps immutable copies of the given argv and env.
 */
class RunCommand(argv: List<String>, val workDir: String, env: Map<String, String>) {

  val argv: List<String> = java.util.List.copyOf(argv)
  val env: Map<String, String> = java.util.Map.copyOf(env)

  operator fun component1(): List<String> = argv

  operator fun component2(): String = workDir

  operator fun component3(): Map<String, String> = env

  override fun equals(other: Any?): Boolean =
      other is RunCommand && other.argv == argv && other.workDir == workDir && other.env == env

  override fun hashCode(): Int = (argv.hashCode() * 31 + workDir.hashCode()) * 31 + env.hashCode()

  override fun toString(): String = "RunCommand[argv=$argv, workDir=$workDir, env=$env]"
}
