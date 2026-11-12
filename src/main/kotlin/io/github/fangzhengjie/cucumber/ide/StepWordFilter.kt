package io.github.fangzhengjie.cucumber.ide

import io.github.fangzhengjie.cucumber.core.Language
import io.github.fangzhengjie.cucumber.core.StepWords
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.SingleRootFileViewProvider
import com.intellij.psi.impl.cache.CacheManager
import com.intellij.psi.impl.cache.impl.id.IdTableBuilding
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.UsageSearchContext
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.ID

/**
 * Rules out step definition candidates without reading them: a file the IDE's word index (IdIndex)
 * covers and that contains none of the [StepWords] outside comments cannot contain step
 * definitions (the adapters skip definitions in comments anyway).
 *
 * Only files whose index entry is known to reflect their current text are ruled out: files in
 * project content, of a word-indexed file type, not too large for indexing and without unsaved
 * edits. Every other file (outside content, unknown file types, ...) is still read.
 */
internal class StepWordFilter private constructor(
    private val project: Project,
    private val withWords: Set<VirtualFile>,
) {

  private val fileIndex = ProjectFileIndex.getInstance(project)
  private val documents = FileDocumentManager.getInstance()

  /** Whether the word index proves that `file` has no step definitions. */
  fun excludes(file: VirtualFile): Boolean =
      file !in withWords && isWordIndexed(file) && hasIndexedWords(file)

  private fun isWordIndexed(file: VirtualFile): Boolean {
    if (!fileIndex.isInContent(file) || documents.isFileModified(file)) {
      return false
    }
    if (SingleRootFileViewProvider.isTooLargeForIntelligence(file)) {
      return false
    }
    return IdTableBuilding.getFileTypeIndexer(file.fileType) != null
  }

  /**
   * Whether the word index holds `file`'s words. A file of an indexed type can still be missing
   * from it (seen with Kotlin files outside source roots; also plain text files when plain text
   * indexing is off): such files have no words at all. The index is looked up by its ID's name,
   * as its class is internal API; should the name ever change, no file is ruled out.
   */
  private fun hasIndexedWords(file: VirtualFile): Boolean {
    if (file.length == 0L) {
      return true
    }
    val wordIndex = ID.findByName<Any, Any>(WORD_INDEX_NAME) ?: return false
    return FileBasedIndex.getInstance().getFileData(wordIndex, file, project).isNotEmpty()
  }

  companion object {
    /** Set to `true` to always read every candidate file (diagnostics, timing comparisons). */
    const val FULL_SCAN_PROPERTY = "cucumber.stepIndex.fullScan"

    /** Set to `true` to also read files that contain the step words only in comments. */
    const val COMMENT_WORDS_PROPERTY = "cucumber.stepIndex.commentWords"

    /** The name of the IDE's word index (the one [CacheManager] queries). */
    private const val WORD_INDEX_NAME = "IdIndex"

    /** Every occurrence context except comments. */
    private val OUTSIDE_COMMENTS = (UsageSearchContext.ANY.toInt() and UsageSearchContext.IN_COMMENTS.toInt().inv()).toShort()

    /**
     * A filter for files of `languages`, or null when the word index can't be used (dumb mode,
     * or disabled with [FULL_SCAN_PROPERTY]): then every candidate must be read.
     */
    fun create(project: Project, languages: Collection<Language>): StepWordFilter? {
      if (java.lang.Boolean.getBoolean(FULL_SCAN_PROPERTY) || DumbService.isDumb(project)) {
        return null
      }
      val cache = CacheManager.getInstance(project)
      val scope = GlobalSearchScope.everythingScope(project)
      val context =
          if (java.lang.Boolean.getBoolean(COMMENT_WORDS_PROPERTY)) UsageSearchContext.ANY else OUTSIDE_COMMENTS
      val files = HashSet<VirtualFile>()
      try {
        for (word in StepWords.of(languages)) {
          ProgressManager.checkCanceled()
          files.addAll(cache.getVirtualFilesWithWord(word, context, scope, true))
        }
      } catch (e: IndexNotReadyException) {
        return null
      }
      return StepWordFilter(project, files)
    }
  }
}
