package io.github.fangzhengjie.cucumber.ide

import io.github.fangzhengjie.cucumber.core.IgnoredPaths
import io.github.fangzhengjie.cucumber.core.Language
import io.github.fangzhengjie.cucumber.core.StepDefinitionInfo
import io.github.fangzhengjie.cucumber.feature.FeatureOutlines
import io.github.fangzhengjie.cucumber.feature.FeatureStep
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderEx
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.annotations.TestOnly

/**
 * Project-wide step definitions. Files are read as text, so languages the IDE has no plugin for
 * work too. This index is independent of any language-specific IDE plugin.
 *
 * Three cache levels keep this cheap: the candidate files (recomputed only when files are
 * added, removed or renamed, project roots change or settings change), each file's parsed steps
 * (re-read only when that file's modification stamp changes), and the indexed definitions
 * (rebuilt only when a file in a step definition language changes, see
 * [CucumberModificationTrackers]). Building the index never builds PSI trees: a definition's
 * [Definition.target] is looked up when it is used. In smart mode, files the IDE's word index
 * proves free of step keywords are not read at all (see [StepWordFilter]); in dumb mode every
 * candidate is read, and the index is rebuilt (mostly from the parse cache) once indexing ends.
 */
object StepIndex {

  data class FileSteps(val file: PsiFile, val steps: List<StepDefinitionInfo>)

  /**
   * A step definition found in `file`. Equality is by file and parsed step, so a definition stays
   * the same map key while unrelated PSI is reparsed.
   */
  data class Definition(val file: PsiFile, val info: StepDefinitionInfo) {
    /** The PSI leaf at the pattern literal, or a target navigating to its offset. Read action. */
    val target: PsiElement
      get() = target(file, info)
  }

  data class Index(val files: List<FileSteps>, val definitions: List<Definition>) {

    internal fun contains(file: PsiFile): Boolean = files.any { entry -> entry.file == file }

    /**
     * The definitions matching `stepText`. Pass the step's effective keyword (see
     * [FeatureStep.effectiveKeyword]) whenever it is known: keyword-sensitive frameworks need it.
     */
    fun matching(stepText: String, stepKeyword: String? = null): List<Definition> =
        definitions.filter { definition -> definition.info.matches(stepText, stepKeyword) }

    /** The definitions matching a feature step (Outline parameters substituted, keyword checked). */
    fun matching(step: FeatureStep): List<Definition> =
        matching(FeatureOutlines.matchText(step), step.effectiveKeyword())
  }

  private data class Candidate(val file: VirtualFile, val language: Language)

  private data class ParsedFile(val stamp: Long, val steps: List<StepDefinitionInfo>)

  private val INDEX: Key<CachedValue<Index>> = Key.create("cucumber.jetbrains.plugin.index")
  private val CANDIDATES: Key<CachedValue<List<Candidate>>> =
      Key.create("cucumber.jetbrains.plugin.candidates")
  private val PARSED: Key<MutableMap<VirtualFile, ParsedFile>> =
      Key.create("cucumber.jetbrains.plugin.parsed")

  private const val MAX_FILE_SIZE = 2_000_000L

  /**
   * Every file of the project: content roots first, then files under the project directory that
   * no module covers (e.g. a stale or missing module setup). Excluded directories and the ones
   * [IgnoredPaths] skips (judged relative to their root, never by the project's own location) are
   * left out. Must be called in a read action.
   */
  fun forEachProjectFile(project: Project, processor: (VirtualFile) -> Unit) {
    val fileIndex = ProjectFileIndex.getInstance(project)
    val base = project.guessProjectDir()
    fileIndex.iterateContent { file ->
      if (!file.isDirectory) {
        val root = fileIndex.getContentRootForFile(file) ?: base
        val relative = root?.let { VfsUtilCore.getRelativePath(file, it, '/') }
        if (relative == null || !IgnoredPaths.isIgnored(relative)) {
          processor(file)
        }
      }
      true
    }
    if (base == null) {
      return
    }
    VfsUtilCore.visitChildrenRecursively(
        base,
        object : VirtualFileVisitor<Unit>(NO_FOLLOW_SYMLINKS) {
          override fun visitFile(file: VirtualFile): Boolean {
            if (!file.isDirectory) {
              if (!fileIndex.isInContent(file) && !fileIndex.isExcluded(file)) {
                processor(file)
              }
              return true
            }
            if (file == base) {
              return true
            }
            val relative = VfsUtilCore.getRelativePath(file, base, '/') ?: return false
            return !(fileIndex.isInContent(file) ||
                fileIndex.isExcluded(file) ||
                IgnoredPaths.isIgnoredDirectory(relative))
          }
        })
  }

  /** What [get] depends on; [StepUsageIndex] adds the feature files to it. */
  internal fun dependencies(project: Project): Array<Any> =
      arrayOf(
          CucumberModificationTrackers.getInstance(project).definitions,
          CucumberModificationTrackers.getInstance(project).files,
          ProjectRootModificationTracker.getInstance(project),
          CucumberSettings.getInstance(),
          // Indexing finished: files the word index did not cover yet are ruled in or out now.
          DumbService.getInstance(project).modificationTracker)

  /** How the last index build found its definitions (diagnostics and timing). */
  data class ScanStats(
      val candidates: Int,
      val cached: Int,
      val read: Int,
      val skipped: Int,
      val wordIndexUsed: Boolean,
      val millis: Long,
  )

  @Volatile
  var lastScan: ScanStats? = null
    private set

  /** Forgets every cached candidate, parsed file and index, so the next [get] starts cold. */
  @TestOnly
  fun clearCaches(project: Project) {
    project.putUserData(INDEX, null)
    project.putUserData(CANDIDATES, null)
    project.putUserData(PARSED, null)
  }

  fun get(project: Project): Index =
      CachedValuesManager.getManager(project)
          .getCachedValue(
              project,
              INDEX,
              CachedValueProvider {
                CachedValueProvider.Result.create(compute(project), *dependencies(project))
              },
              false)

  private fun candidates(project: Project): List<Candidate> =
      CachedValuesManager.getManager(project)
          .getCachedValue(
              project,
              CANDIDATES,
              CachedValueProvider {
                val candidates = ArrayList<Candidate>()
                forEachProjectFile(project) { file ->
                  val language = Language.forExtension(file.extension)
                  if (language != null && LanguageAvailability.isEnabled(language)) {
                    candidates.add(Candidate(file, language))
                  }
                }
                CachedValueProvider.Result.create<List<Candidate>>(
                    candidates.toList(),
                    CucumberModificationTrackers.getInstance(project).files,
                    ProjectRootModificationTracker.getInstance(project),
                    CucumberSettings.getInstance())
              },
              false)

  private fun compute(project: Project): Index {
    val start = System.nanoTime()
    var parsed = project.getUserData(PARSED)
    if (parsed == null) {
      val fresh: MutableMap<VirtualFile, ParsedFile> = ConcurrentHashMap()
      parsed =
          if (project is UserDataHolderEx) {
            project.putUserDataIfAbsent(PARSED, fresh)
          } else {
            project.putUserData(PARSED, fresh)
            fresh
          }
    }
    val psiManager = PsiManager.getInstance(project)
    val files = ArrayList<FileSteps>()
    val definitions = ArrayList<Definition>()
    val candidates = candidates(project)
    parsed.keys.retainAll(candidates.mapTo(HashSet()) { it.file })
    val filter = StepWordFilter.create(project, candidates.mapTo(HashSet()) { it.language })
    val counts = IntArray(3)
    for (candidate in candidates) {
      val steps = steps(candidate, parsed, filter, counts)
      val psiFile = (if (steps.isEmpty()) null else psiManager.findFile(candidate.file)) ?: continue
      files.add(FileSteps(psiFile, steps))
      for (step in steps) {
        definitions.add(Definition(psiFile, step))
      }
    }
    lastScan =
        ScanStats(
            candidates.size,
            counts[0],
            counts[1],
            counts[2],
            filter != null,
            (System.nanoTime() - start) / 1_000_000)
    return Index(files.toList(), definitions.toList())
  }

  /**
   * The file's steps, re-parsed only when its content changed. Files `filter` rules out are not
   * read at all. `counts` tallies cache hits, reads and skipped files.
   */
  private fun steps(
      candidate: Candidate,
      parsed: MutableMap<VirtualFile, ParsedFile>,
      filter: StepWordFilter?,
      counts: IntArray,
  ): List<StepDefinitionInfo> {
    val file = candidate.file
    if (!file.isValid || file.length > MAX_FILE_SIZE) {
      return emptyList()
    }
    val document = FileDocumentManager.getInstance().getCachedDocument(file)
    val stamp = document?.modificationStamp ?: file.modificationStamp
    val cached = parsed[file]
    if (cached != null && cached.stamp == stamp) {
      counts[0]++
      return cached.steps
    }
    ProgressManager.checkCanceled()
    if (filter != null && filter.excludes(file)) {
      counts[2]++
      return emptyList()
    }
    counts[1]++
    val text = text(file, document) ?: return emptyList() // unreadable now: try again next time
    var steps: List<StepDefinitionInfo> = emptyList()
    val adapter = candidate.language.adapter()
    if (adapter.mayContainSteps(text)) {
      steps = adapter.parse(text).toList()
    }
    parsed[file] = ParsedFile(stamp, steps)
    return steps
  }

  /** The file's current text (unsaved edits included), with `\n` line separators. */
  private fun text(file: VirtualFile, document: Document?): String? {
    if (document != null) {
      return document.text
    }
    return try {
      LoadTextUtil.loadText(file).toString()
    } catch (e: RuntimeException) {
      // Cancellation must propagate: swallowing it would cache the file as having no steps.
      if (e is ControlFlowException || e is CancellationException) {
        throw e
      }
      null
    }
  }

  /**
   * The PSI leaf starting at the pattern literal when the IDE understands the language;
   * otherwise a target that navigates to the exact offset.
   */
  private fun target(file: PsiFile, step: StepDefinitionInfo): PsiElement {
    val leaf = if (file.isValid) file.findElementAt(step.offset) else null
    if (leaf != null && leaf.textRange.startOffset == step.offset) {
      return leaf
    }
    return StepTarget(file, step.offset, step.pattern)
  }
}
