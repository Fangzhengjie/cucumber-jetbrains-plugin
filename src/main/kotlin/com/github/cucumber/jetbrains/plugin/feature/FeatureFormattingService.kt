package com.github.cucumber.jetbrains.plugin.feature

import com.github.cucumber.jetbrains.plugin.core.GherkinFormatter
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.service.AbstractDocumentFormattingService
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/**
 * Code | Reformat Code for `.feature` files, backed by the IDE-free [GherkinFormatter]. The formatter
 * keeps the line count, so only changed lines that intersect the requested ranges are replaced.
 */
class FeatureFormattingService : AbstractDocumentFormattingService() {

  override fun getFeatures(): Set<FormattingService.Feature> =
      setOf(FormattingService.Feature.FORMAT_FRAGMENTS)

  override fun canFormat(file: PsiFile): Boolean = file is FeatureFile

  override fun formatDocument(
      document: Document,
      formattingRanges: List<TextRange>,
      formattingContext: FormattingContext,
      canChangeWhiteSpaceOnly: Boolean,
      quickFormat: Boolean,
  ) {
    val text = document.text
    val formatted = GherkinFormatter.format(text, INDENT_SIZE)
    if (formatted == text) {
      return
    }
    val lines = formatted.split('\n')
    if (lines.size != document.lineCount.coerceAtLeast(1)) {
      document.setText(formatted)
      return
    }
    for (line in lines.indices.reversed()) {
      val start = document.getLineStartOffset(line)
      val end = document.getLineEndOffset(line)
      if (formattingRanges.none { it.startOffset <= end && it.endOffset >= start }) {
        continue
      }
      if (document.charsSequence.subSequence(start, end).toString() != lines[line]) {
        document.replaceString(start, end, lines[line])
      }
    }
  }

  private companion object {
    const val INDENT_SIZE = 2
  }
}
