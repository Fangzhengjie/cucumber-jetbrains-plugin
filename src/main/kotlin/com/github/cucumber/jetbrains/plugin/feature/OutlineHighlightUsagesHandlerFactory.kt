package com.github.cucumber.jetbrains.plugin.feature

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactoryBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.Consumer

/** Highlights a Scenario Outline parameter: its `<param>` usages and its Examples columns. */
class OutlineHighlightUsagesHandlerFactory : HighlightUsagesHandlerFactoryBase() {

  override fun createHighlightUsagesHandler(
      editor: Editor,
      file: PsiFile,
      target: PsiElement
  ): HighlightUsagesHandlerBase<*>? {
    if (file !is FeatureFile) {
      return null
    }
    val offset = editor.caretModel.offset
    val scenario = FeatureOutlines.scenarioAt(file, offset) ?: return null
    val name =
        scenario.paramAt(offset)?.name
            ?: scenario.headerCellAt(offset)?.text?.takeIf { scenario.isOutline }
            ?: return null
    val ranges =
        scenario.usages(name).map { TextRange(it.start, it.end) } +
            scenario.headerCells(name).map { TextRange(it.start, it.end) }
    return object : HighlightUsagesHandlerBase<PsiElement>(editor, file) {
      override fun getTargets(): List<PsiElement> = listOf(target)

      override fun selectTargets(
          targets: List<PsiElement>,
          selectionConsumer: Consumer<in List<PsiElement>>
      ) {
        selectionConsumer.consume(targets)
      }

      override fun computeUsages(targets: List<PsiElement>) {
        myReadUsages.addAll(ranges)
      }
    }
  }
}
