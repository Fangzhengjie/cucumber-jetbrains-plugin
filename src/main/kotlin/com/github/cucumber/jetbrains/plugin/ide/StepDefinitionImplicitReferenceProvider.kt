package com.github.cucumber.jetbrains.plugin.ide

import com.github.cucumber.jetbrains.plugin.core.Language
import com.github.cucumber.jetbrains.plugin.core.LiteralRanges
import com.github.cucumber.jetbrains.plugin.core.StepRename
import com.github.cucumber.jetbrains.plugin.feature.FeatureFile
import com.github.cucumber.jetbrains.plugin.feature.FeatureStep
import com.intellij.model.Symbol
import com.intellij.model.psi.ImplicitReferenceProvider
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.model.psi.PsiSymbolService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

/**
 * Gives a step definition's pattern literal its own reference when the file's PSI is one coarse
 * leaf (plain text / TextMate: Go, Rust, ... without a language plugin), so Ctrl+hover underlines
 * only the literal and Ctrl+click opens the feature steps using it. Fine-grained PSI (Java, Kotlin,
 * JavaScript, ...) keeps using [StepDefinitionGotoDeclarationHandler].
 */
class StepDefinitionImplicitReferenceProvider : ImplicitReferenceProvider {

  override fun getImplicitReference(element: PsiElement, offsetInElement: Int): PsiSymbolReference? {
    if (element is PsiFile || element.firstChild != null || element.parent !is PsiFile) {
      return null
    }
    val leafRange = element.textRange ?: return null
    val (definition, literal) =
        StepDefinitionLiteral.at(element.containingFile ?: return null, leafRange.startOffset + offsetInElement)
            ?: return null
    if (!LiteralRanges.leafIsCoarser(
        leafRange.startOffset, leafRange.endOffset, literal.startOffset, literal.endOffset)) {
      return null
    }
    val project = element.project
    if (StepUsageIndex.get(project).of(definition).isEmpty()) {
      return null
    }
    return StepLiteralReference(element, literal.shiftLeft(leafRange.startOffset), definition)
  }

  private class StepLiteralReference(
      private val element: PsiElement,
      private val range: TextRange,
      private val definition: StepIndex.Definition,
  ) : PsiSymbolReference {

    override fun getElement(): PsiElement = element

    override fun getRangeInElement(): TextRange = range

    override fun resolveReference(): Collection<Symbol> {
      val symbols = PsiSymbolService.getInstance()
      return StepDefinitionLiteral.featureSteps(element.project, definition).map(symbols::asSymbol)
    }
  }
}

/** Locating step definition pattern literals in code files and the feature steps using them. */
internal object StepDefinitionLiteral {

  /** The step definition whose pattern literal contains `offset` in `file`, with that literal's range. */
  fun at(file: PsiFile, offset: Int): Pair<StepIndex.Definition, TextRange>? {
    if (file is FeatureFile) {
      return null
    }
    val virtualFile = file.viewProvider.virtualFile
    val language = Language.forExtension(virtualFile.extension) ?: return null
    val definitions = StepUsageIndex.get(file.project).definitionsIn(virtualFile)
    if (definitions.isEmpty()) {
      return null
    }
    val text = file.viewProvider.document?.immutableCharSequence?.toString() ?: file.text
    for (definition in definitions) {
      val start = definition.info.offset
      val end = StepRename.literalAt(language, text, start)?.end ?: continue
      if (offset in start until end) {
        return definition to TextRange(start, end)
      }
    }
    return null
  }

  /** The feature steps (or their leaves) matching `definition`. */
  fun featureSteps(project: Project, definition: StepIndex.Definition): List<PsiElement> {
    val psiManager = PsiManager.getInstance(project)
    return StepUsageIndex.get(project)
        .of(definition)
        .mapNotNull { usage ->
          val featureFile = psiManager.findFile(usage.file) ?: return@mapNotNull null
          val leaf = featureFile.findElementAt(usage.textOffset) ?: return@mapNotNull null
          PsiTreeUtil.getParentOfType(leaf, FeatureStep::class.java, false) ?: leaf
        }
        .distinct()
  }
}
