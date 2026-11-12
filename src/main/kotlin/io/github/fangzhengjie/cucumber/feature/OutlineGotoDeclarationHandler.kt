package io.github.fangzhengjie.cucumber.feature

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import io.github.fangzhengjie.cucumber.core.Tags
import io.github.fangzhengjie.cucumber.ide.StepTarget
import io.github.fangzhengjie.cucumber.ide.StepUsageIndex

/**
 * Ctrl+click / Ctrl+B on a `<param>` opens its Examples column; on an Examples column header it
 * lists the column's `<param>` usages; on a `@tag` it lists the tag's other occurrences.
 */
class OutlineGotoDeclarationHandler : GotoDeclarationHandler {

  override fun getGotoDeclarationTargets(
      sourceElement: PsiElement?,
      offset: Int,
      editor: Editor?
  ): Array<PsiElement>? {
    val file = sourceElement?.containingFile as? FeatureFile ?: return null
    val type = sourceElement.node?.elementType
    if (type == FeatureTokenTypes.TAG) {
      return tagTargets(file, sourceElement.text, sourceElement.textRange.startOffset)
    }
    val scenario = FeatureOutlines.scenarioAt(file, offset) ?: return null
    val param = scenario.paramAt(offset)
    if (param != null) {
      return targets(
          scenario.headerCells(param.name).map { cell ->
            StepTarget(
                file, cell.start, "Examples column '" + cell.text + "'" + line(file, cell.start))
          })
    }
    val cell = scenario.headerCellAt(offset)
    if (cell != null && type == FeatureTokenTypes.TABLE_CELL) {
      return targets(
          scenario.usages(cell.text).map { usage ->
            StepTarget(file, usage.start, "<" + usage.name + ">" + line(file, usage.start))
          })
    }
    return null
  }

  private fun tagTargets(file: PsiFile, tag: String, start: Int): Array<PsiElement>? {
    val manager = PsiManager.getInstance(file.project)
    val targets = ArrayList<PsiElement>()
    for ((virtualFile, offsets) in projectTags(file.project)[tag].orEmpty()) {
      val target = manager.findFile(virtualFile) ?: continue
      for (offset in offsets) {
        if (target != file || offset != start) {
          targets.add(StepTarget(target, offset, tag + line(target, offset)))
        }
      }
    }
    return targets(targets)
  }

  private fun targets(targets: List<PsiElement>): Array<PsiElement>? =
      if (targets.isEmpty()) null else targets.toTypedArray()

  private fun line(file: PsiFile, offset: Int): String =
      "  (" +
          file.name +
          ":" +
          (StringUtil.offsetToLineNumber(file.viewProvider.contents, offset) + 1) +
          ")"

  companion object {

    /**
     * Tag -> feature file -> tag offsets, for all `.feature` files of the project. Each file's tags
     * are cached until that file changes and the file list until files are added, removed or
     * renamed, so typing re-scans only the edited file.
     */
    fun projectTags(project: Project): Map<String, Map<VirtualFile, List<Int>>> {
      val manager = PsiManager.getInstance(project)
      val result = LinkedHashMap<String, LinkedHashMap<VirtualFile, MutableList<Int>>>()
      for (virtualFile in StepUsageIndex.featureFiles(project)) {
        if (!virtualFile.isValid) {
          continue
        }
        val psiFile = manager.findFile(virtualFile) ?: continue
        for (tag in fileTags(psiFile)) {
          result
              .getOrPut(tag.name) { LinkedHashMap() }
              .getOrPut(virtualFile) { ArrayList() }
              .add(tag.start)
        }
      }
      return result
    }

    private fun fileTags(file: PsiFile): List<Tags.Tag> =
        CachedValuesManager.getCachedValue(file) {
          CachedValueProvider.Result.create(Tags.find(file.viewProvider.contents), file)
        }
  }
}
