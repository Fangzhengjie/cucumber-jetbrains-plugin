package io.github.fangzhengjie.cucumber.ide

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.fangzhengjie.cucumber.core.Language
import io.github.fangzhengjie.cucumber.core.LiteralRanges
import io.github.fangzhengjie.cucumber.core.StepRename
import io.github.fangzhengjie.cucumber.feature.FeatureFile
import io.github.fangzhengjie.cucumber.feature.FeatureStep

/**
 * Ctrl+click / Ctrl+B on a step definition's pattern literal in code (any language, including files
 * the IDE only knows as plain text) opens the feature steps that use it; several give a chooser.
 */
class StepDefinitionGotoDeclarationHandler : GotoDeclarationHandler {

  override fun getGotoDeclarationTargets(
      sourceElement: PsiElement?,
      offset: Int,
      editor: Editor?,
  ): Array<PsiElement>? {
    val file = sourceElement?.containingFile ?: return null
    if (file is FeatureFile) {
      return null
    }
    val virtualFile = file.viewProvider.virtualFile
    val language = Language.forExtension(virtualFile.extension) ?: return null
    val project = file.project
    val usages = StepUsageIndex.get(project)
    val definitions = usages.definitionsIn(virtualFile)
    if (definitions.isEmpty()) {
      return null
    }
    val text = file.viewProvider.document?.immutableCharSequence?.toString() ?: file.text
    val definition =
        definitions.firstOrNull { definition ->
          val start = definition.info.offset
          val end = StepRename.literalAt(language, text, start)?.end ?: return@firstOrNull false
          offset in start until end
        } ?: return null
    // A coarse leaf (plain text / TextMate) would make Ctrl+hover underline all of it: leave the
    // literal to StepDefinitionImplicitReferenceProvider, whose reference has the literal's range.
    val leafRange = sourceElement.textRange
    val literalEnd = StepRename.literalAt(language, text, definition.info.offset)?.end
    if (literalEnd != null &&
        sourceElement.parent is PsiFile &&
        LiteralRanges.leafIsCoarser(
            leafRange.startOffset, leafRange.endOffset, definition.info.offset, literalEnd)) {
      return null
    }
    val psiManager = PsiManager.getInstance(project)
    val targets =
        usages.of(definition).mapNotNull { usage ->
          val featureFile = psiManager.findFile(usage.file) ?: return@mapNotNull null
          val leaf = featureFile.findElementAt(usage.textOffset) ?: return@mapNotNull null
          PsiTreeUtil.getParentOfType(leaf, FeatureStep::class.java, false) ?: leaf
        }
    return if (targets.isEmpty()) null else targets.distinct().toTypedArray()
  }
}
