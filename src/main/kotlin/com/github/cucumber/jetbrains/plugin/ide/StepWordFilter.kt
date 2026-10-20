package com.github.cucumber.jetbrains.plugin.ide

import com.github.cucumber.jetbrains.plugin.core.Language
import com.github.cucumber.jetbrains.plugin.core.StepWords
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.SingleRootFileViewProvider
import com.intellij.psi.impl.cache.impl.id.IdIndex
import com.intellij.psi.impl.cache.impl.id.IdIndexEntry
import com.intellij.psi.impl.cache.impl.id.IdTableBuilding
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.UsageSearchContext
import com.intellij.util.indexing.FileBasedIndex

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
    val type = file.fileType
    if (type == PlainTextFileType.INSTANCE && FileBasedIndex.IGNORE_PLAIN_TEXT_FILES) {
      return false
    }
    return IdTableBuilding.getFileTypeIndexer(type) != null
  }

  /**
   * Whether the word index holds `file`'s words. A file of an indexed type can still be missing
   * from it (seen with Kotlin files outside source roots): such files have no words at all.
   */
  private fun hasIndexedWords(file: VirtualFile): Boolean =
      file.length == 0L ||
          FileBasedIndex.getInstance().getFileData(IdIndex.NAME, file, project).isNotEmpty()

  companion object {
    /** Set to `true` to always read every candidate file (diagnostics, timing comparisons). */
    const val FULL_SCAN_PROPERTY = "cucumber.stepIndex.fullScan"

    /** Set to `true` to also read files that contain the step words only in comments. */
    const val COMMENT_WORDS_PROPERTY = "cucumber.stepIndex.commentWords"

    /**
     * A filter for files of `languages`, or null when the word index can't be used (dumb mode,
     * or disabled with [FULL_SCAN_PROPERTY]): then every candidate must be read.
     */
    fun create(project: Project, languages: Collection<Language>): StepWordFilter? {
      if (java.lang.Boolean.getBoolean(FULL_SCAN_PROPERTY) || DumbService.isDumb(project)) {
        return null
      }
      val index = FileBasedIndex.getInstance()
      val scope = GlobalSearchScope.everythingScope(project)
      val anyContext = java.lang.Boolean.getBoolean(COMMENT_WORDS_PROPERTY)
      val files = HashSet<VirtualFile>()
      try {
        for (word in StepWords.of(languages)) {
          ProgressManager.checkCanceled()
          index.processValues(
              IdIndex.NAME,
              IdIndexEntry(word, true),
              null,
              FileBasedIndex.ValueProcessor<Int> { file, mask ->
                if (anyContext || (mask and UsageSearchContext.IN_COMMENTS.toInt().inv()) != 0) {
                  files.add(file)
                }
                true
              },
              scope)
        }
      } catch (e: IndexNotReadyException) {
        return null
      }
      return StepWordFilter(project, files)
    }
  }
}
