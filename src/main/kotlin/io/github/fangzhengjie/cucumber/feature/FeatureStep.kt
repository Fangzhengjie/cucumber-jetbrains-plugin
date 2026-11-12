package io.github.fangzhengjie.cucumber.feature

import io.github.fangzhengjie.cucumber.core.Dialect
import io.github.fangzhengjie.cucumber.core.GherkinDialects
import io.github.fangzhengjie.cucumber.core.GherkinKeywordType
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiReference
import com.intellij.psi.tree.IElementType

class FeatureStep(node: ASTNode) : ASTWrapperPsiElement(node) {

  /** The step keyword as written (possibly localized, e.g. `假如`). */
  fun keyword(): String {
    return keywordNode()?.text ?: "Given"
  }

  fun stepText(): String {
    val keyword = keywordNode() ?: return text.trim { it <= ' ' }
    return text.substring(keyword.startOffsetInParent + keyword.textLength).trim()
  }

  fun stepTextOffset(): Int {
    val keyword = keywordNode() ?: return textOffset
    val content = text
    var offset = keyword.startOffsetInParent + keyword.textLength
    while (offset < content.length && Character.isWhitespace(content[offset])) {
      offset++
    }
    return textRange.startOffset + offset
  }

  /** The `# language:` dialect of the containing feature file (cached until the file changes). */
  fun dialect(): Dialect =
      (containingFile as? FeatureFile)?.dialect()
          ?: GherkinDialects.dialectFor(containingFile.viewProvider.contents)

  /** The canonical kind of [keyword] (`*` counts as [GherkinKeywordType.AND]). */
  fun keywordType(): GherkinKeywordType = keywordType(dialect())

  private fun keywordType(dialect: Dialect): GherkinKeywordType {
    val keyword = keyword()
    return dialect.stepType(keyword)
        ?: GherkinDialects.stepType(keyword)
        ?: GherkinKeywordType.GIVEN
  }

  /**
   * Lower-case English `given` / `when` / `then`; `And` / `But` / `*` resolve to the step before
   * in the same scenario or background (`given` at its start).
   */
  fun effectiveKeyword(): String {
    val dialect = dialect()
    val type = keywordType(dialect)
    if (type != GherkinKeywordType.AND && type != GherkinKeywordType.BUT) {
      return type.stepKeyword()
    }
    var element = prevSibling
    while (element != null) {
      if (element.node.elementType == FeatureTokenTypes.KEYWORD) {
        break
      }
      if (element is FeatureStep) {
        val previous = element.keywordType(dialect)
        if (previous != GherkinKeywordType.AND && previous != GherkinKeywordType.BUT) {
          return previous.stepKeyword()
        }
      }
      element = element.prevSibling
    }
    return "given"
  }

  fun hasAttachment(attachmentType: IElementType): Boolean {
    var element = nextSibling
    while (element != null && element !is FeatureStep) {
      if (element.node.elementType == attachmentType) {
        return true
      }
      element = element.nextSibling
    }
    return false
  }

  private fun keywordNode(): ASTNode? = node.findChildByType(FeatureTokenTypes.STEP_KEYWORD)

  override fun getReferences(): Array<PsiReference> = arrayOf(FeatureStepReference(this))
}
