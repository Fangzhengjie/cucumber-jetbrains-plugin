package io.github.fangzhengjie.cucumber.feature

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import io.github.fangzhengjie.cucumber.core.GherkinOutline

/**
 * Folds Rule / Background / Scenario / Scenario Outline / Examples bodies, doc strings and tables.
 */
class FeatureFoldingBuilder : FoldingBuilderEx(), DumbAware {

  override fun buildFoldRegions(
      root: PsiElement,
      document: Document,
      quick: Boolean,
  ): Array<FoldingDescriptor> {
    val node = root.node ?: return FoldingDescriptor.EMPTY_ARRAY
    val outline = GherkinOutline.parse(document.charsSequence)
    val result = ArrayList<FoldingDescriptor>()
    fun add(start: Int, end: Int, placeholder: String) {
      if (start < end &&
          end <= document.textLength &&
          document.getLineNumber(start) != document.getLineNumber(end)) {
        result.add(FoldingDescriptor(node, TextRange(start, end), null, placeholder))
      }
    }
    for (section in outline.all()) {
      if (section.kind != GherkinOutline.Kind.FEATURE && section.kind != GherkinOutline.Kind.STEP) {
        add(section.headerEnd, section.endOffset, " ...")
      }
    }
    for (block in outline.blocks) {
      when (block.kind) {
        GherkinOutline.BlockKind.DOC_STRING -> {
          val delimiter = block.firstLine.substring(0, 3)
          add(block.startOffset, block.endOffset, "$delimiter...$delimiter")
        }
        GherkinOutline.BlockKind.TABLE -> add(block.firstLineEnd, block.endOffset, " ...")
      }
    }
    return result.toTypedArray()
  }

  override fun getPlaceholderText(node: ASTNode): String = "..."

  override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}
