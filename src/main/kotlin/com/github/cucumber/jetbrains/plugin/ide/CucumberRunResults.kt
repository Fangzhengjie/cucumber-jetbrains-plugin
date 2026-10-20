package com.github.cucumber.jetbrains.plugin.ide

import com.github.cucumber.jetbrains.plugin.core.CucumberMessages
import com.github.cucumber.jetbrains.plugin.core.RunResults
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The last Cucumber run's result of each `.feature` line (features, scenarios, outlines, example
 * rows and steps), shown by the gutter icons. A newer run replaces the results of the lines it ran;
 * a result is ignored once its line's text changes. Kept across restarts in the IDE's cache.
 */
@Service(Service.Level.PROJECT)
@State(name = "CucumberRunResults", storages = [Storage(StoragePathMacros.CACHE_FILE)])
class CucumberRunResults(private val project: Project) :
    PersistentStateComponent<CucumberRunResults.Saved> {

  @Tag("entry")
  class Entry {
    @Attribute var path: String = ""
    @Attribute var line: Int = 0
    @Attribute var kind: String = ""
    @Attribute var status: String = ""
    @Attribute var message: String? = null
    @Attribute var step: String? = null
    @Attribute var counts: String = ""
    @Attribute var signature: String = ""
  }

  class Saved {
    @XCollection var entries: MutableList<Entry> = ArrayList()
  }

  private val files = ConcurrentHashMap<String, Map<Int, RunResults.LineResult>>()

  /** Stores the results of the given files (by absolute path) and refreshes their gutters. */
  fun update(results: Map<String, Map<Int, RunResults.LineResult>>, paths: Collection<String>) {
    val keys = HashSet<String>()
    for (path in paths) {
      val lines = results[path] ?: continue
      val key = key(path)
      keys.add(key)
      files.merge(key, lines) { old, new -> old + new }
    }
    if (keys.isNotEmpty()) {
      refresh(keys)
    }
  }

  /** The result of a 1-based [line] whose current text is [lineText], if it is still valid. */
  fun resultAt(file: VirtualFile, line: Int, lineText: CharSequence): RunResults.LineResult? {
    val result = files[key(file.path)]?.get(line) ?: return null
    return if (result.matches(lineText)) result else null
  }

  /** Whether any result is known for [file] (even for lines that changed since). */
  fun hasResults(file: VirtualFile): Boolean = files.containsKey(key(file.path))

  fun clear() {
    val keys = HashSet(files.keys)
    files.clear()
    refresh(keys)
  }

  private fun refresh(keys: Set<String>) {
    ApplicationManager.getApplication().invokeLater(
        {
          val psiManager = PsiManager.getInstance(project)
          for (file in FileEditorManager.getInstance(project).openFiles) {
            if (key(file.path) in keys) {
              psiManager.findFile(file)?.let {
                DaemonCodeAnalyzer.getInstance(project).restart(it, "Cucumber run results")
              }
            }
          }
        },
        project.disposed)
  }

  override fun getState(): Saved {
    val saved = Saved()
    for ((path, lines) in files) {
      for (result in lines.values) {
        saved.entries.add(
            Entry().apply {
              this.path = path
              line = result.line
              kind = result.kind.name
              status = result.status.name
              message = result.message
              step = result.step
              counts = result.counts.entries.joinToString(",") { "${it.key.name}=${it.value}" }
              signature = result.signature
            })
      }
    }
    return saved
  }

  override fun loadState(state: Saved) {
    files.clear()
    val loaded = HashMap<String, MutableMap<Int, RunResults.LineResult>>()
    for (entry in state.entries) {
      val kind = RunResults.Kind.entries.firstOrNull { it.name == entry.kind } ?: continue
      val counts = LinkedHashMap<RunResults.Status, Int>()
      for (part in entry.counts.split(',')) {
        val (status, count) = part.split('=').takeIf { it.size == 2 } ?: continue
        counts[RunResults.Status.of(status)] = count.toIntOrNull() ?: continue
      }
      loaded.getOrPut(entry.path) { HashMap() }[entry.line] =
          RunResults.LineResult(
              entry.line, kind, RunResults.Status.of(entry.status), entry.message, entry.step, counts, entry.signature)
    }
    files.putAll(loaded)
  }

  /** Collects the results of one run from its Cucumber Messages and publishes them while it runs. */
  class Feed(private val project: Project, converter: CucumberMessages.Converter) {

    private val collector = RunResults.Collector { uri -> canonicalPath(converter.path(uri)) }
    private var published = 0L

    fun accept(line: String) = collector.accept(line)

    fun restart() = collector.restart()

    /** Hands new results to the project's [CucumberRunResults], at most once a second unless [force]. */
    fun publish(force: Boolean) {
      val now = System.currentTimeMillis()
      if (!force && now - published < 1000 || project.isDisposed) {
        return
      }
      val changed = collector.takeChanged()
      if (changed.isEmpty()) {
        return
      }
      published = now
      getInstance(project).update(collector.results(), changed)
    }
  }

  companion object {
    fun getInstance(project: Project): CucumberRunResults = project.getService(CucumberRunResults::class.java)

    private val canonical = ConcurrentHashMap<String, String>()

    private fun key(path: String): String {
      val normalized = RunResults.normalizePath(path)
      return if (SystemInfoRt.isFileSystemCaseSensitive) normalized else normalized.lowercase(Locale.ROOT)
    }

    /** The path as the file system spells it (the IDE's file paths), `/`-separated. */
    fun canonicalPath(path: String): String {
      if (path.startsWith("classpath:")) {
        return path
      }
      if (canonical.size > 10_000) {
        canonical.clear()
      }
      return canonical.computeIfAbsent(path) {
        val normalized = RunResults.normalizePath(it)
        try {
          RunResults.normalizePath(Path.of(normalized).toRealPath().toString())
        } catch (e: Exception) {
          normalized
        }
      }
    }

    /**
     * A `cucumber://<path>:<line>` location with the canonical path, so the test tree's locations
     * (stored by the platform's test state storage) equal those the gutter looks up.
     */
    fun canonicalLocation(location: String): String {
      val prefix = CucumberMessages.PROTOCOL + "://"
      if (!location.startsWith(prefix)) {
        return location
      }
      val (path, line) = CucumberMessages.parseLocation(location.substring(prefix.length)) ?: return location
      return CucumberMessages.location(canonicalPath(path), line)
    }
  }
}
