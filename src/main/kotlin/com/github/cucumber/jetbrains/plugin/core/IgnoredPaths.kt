package com.github.cucumber.jetbrains.plugin.core

/**
 * Directories never scanned for feature files or step definitions: dependencies, virtual
 * environments, hidden directories and build output.
 *
 * Paths are always relative to a project or content root, so the location of the project itself
 * (e.g. `C:/work/build/my-service`) never matters. Build output names (`build`, `target`, ...)
 * only count above the first `src` directory: `module/build/` is output, but
 * `src/test/java/com/acme/build/` is source.
 */
object IgnoredPaths {

  private val ALWAYS = setOf("node_modules", "vendor", "venv", "site-packages", "__pycache__")
  private val OUTPUT = setOf("target", "build", "out", "bin", "obj", "dist")

  /** Whether the file at `relativePath` (`/`- or `\`-separated, file name last) is skipped. */
  @JvmStatic
  fun isIgnored(relativePath: String): Boolean = ignoredDirectories(directories(relativePath).dropLast(1))

  /** Whether the directory at `relativePath` (and everything below it) is skipped. */
  @JvmStatic
  fun isIgnoredDirectory(relativePath: String): Boolean = ignoredDirectories(directories(relativePath))

  private fun directories(path: String): List<String> =
      path.split('/', '\\').filter { it.isNotEmpty() && it != "." }

  private fun ignoredDirectories(directories: List<String>): Boolean {
    var underSource = false
    for (directory in directories) {
      if (directory.startsWith(".") || directory in ALWAYS) {
        return true
      }
      if (directory == "src") {
        underSource = true
      } else if (!underSource && directory in OUTPUT) {
        return true
      }
    }
    return false
  }
}
