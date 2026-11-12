package io.github.fangzhengjie.cucumber.feature

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

object FeatureTokenTypes {

  @JvmField val FILE: IFileElementType = IFileElementType(FeatureLanguage)
  @JvmField val STEP: IElementType = type("STEP")

  @JvmField val KEYWORD: IElementType = type("KEYWORD")
  @JvmField val TITLE: IElementType = type("TITLE")
  @JvmField val STEP_KEYWORD: IElementType = type("STEP_KEYWORD")
  @JvmField val STEP_TEXT: IElementType = type("STEP_TEXT")
  @JvmField val QUOTED: IElementType = type("QUOTED")
  @JvmField val NUMBER: IElementType = type("NUMBER")
  @JvmField val OUTLINE_PARAM: IElementType = type("OUTLINE_PARAM")
  @JvmField val TAG: IElementType = type("TAG")
  @JvmField val COMMENT: IElementType = type("COMMENT")
  /** The `|` separator of a data table row. */
  @JvmField val TABLE: IElementType = type("TABLE_PIPE")
  @JvmField val TABLE_CELL: IElementType = type("TABLE_CELL")
  @JvmField val DOC_STRING: IElementType = type("DOC_STRING")
  @JvmField val TEXT: IElementType = type("TEXT")

  @JvmField val COMMENTS: TokenSet = TokenSet.create(COMMENT)

  private fun type(name: String): IElementType = IElementType("CUCUMBER_$name", FeatureLanguage)
}
