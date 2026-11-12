package io.github.fangzhengjie.cucumber.ide

import io.github.fangzhengjie.cucumber.core.Language
import io.github.fangzhengjie.cucumber.core.LiteralRanges
import io.github.fangzhengjie.cucumber.core.StepRename
import io.github.fangzhengjie.cucumber.feature.FeatureFile
import io.github.fangzhengjie.cucumber.feature.FeatureStep
import com.intellij.model.Pointer
import com.intellij.model.Symbol
import com.intellij.model.psi.ImplicitReferenceProvider
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.navigation.NavigatableSymbol
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
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

    override fun resolveReference(): Collection<Symbol> =
        StepDefinitionLiteral.featureSteps(element.project, definition).map(::FeatureStepSymbol)
  }

  /** A feature step as a symbol, so Ctrl+click navigates to it (PsiSymbolService is internal API). */
  private class FeatureStepSymbol(private val step: PsiElement) : NavigatableSymbol {

    override fun createPointer(): Pointer<FeatureStepSymbol> =
        Pointer.delegatingPointer(SmartPointerManager.createPointer(step), ::FeatureStepSymbol)

    override fun getNavigationTargets(project: Project): Collection<NavigationTarget> =
        listOf(FeatureStepTarget(step))

    override fun equals(other: Any?): Boolean = other is FeatureStepSymbol && other.step == step

    override fun hashCode(): Int = step.hashCode()
  }

  private class FeatureStepTarget(private val step: PsiElement) : NavigationTarget {

    override fun createPointer(): Pointer<FeatureStepTarget> =
        Pointer.delegatingPointer(SmartPointerManager.createPointer(step), ::FeatureStepTarget)

    override fun computePresentation(): TargetPresentation {
      val file = step.containingFile
      val document = PsiDocumentManager.getInstance(step.project).getDocument(file)
      val line = document?.getLineNumber(step.textOffset)?.plus(1)
      return TargetPresentation.builder(step.text.trim().lineSequence().first())
          .icon(file.getIcon(0))
          .locationText(if (line == null) file.name else "${file.name}:$line")
          .presentation()
    }

    override fun navigationRequest(): NavigationRequest? =
        NavigationRequest.sourceNavigationRequest(step.containingFile, step.textRange)

    override fun equals(other: Any?): Boolean = other is FeatureStepTarget && other.step == step

    override fun hashCode(): Int = step.hashCode()
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
