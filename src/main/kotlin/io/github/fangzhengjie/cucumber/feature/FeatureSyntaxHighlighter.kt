package io.github.fangzhengjie.cucumber.feature

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType

class FeatureSyntaxHighlighter : SyntaxHighlighterBase() {

  override fun getHighlightingLexer(): Lexer = FeatureLexer()

  override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> {
    val key = KEYS[tokenType]
    return if (key == null) pack(HighlighterColors.TEXT) else pack(key)
  }

  companion object {
    @JvmField
    internal val KEYWORD: TextAttributesKey =
        key("CUCUMBER_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    @JvmField
    internal val TITLE: TextAttributesKey =
        key("CUCUMBER_TITLE", DefaultLanguageHighlighterColors.CLASS_NAME)
    @JvmField
    internal val STEP_KEYWORD: TextAttributesKey =
        key("CUCUMBER_STEP_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    @JvmField
    internal val STEP_TEXT: TextAttributesKey =
        key("CUCUMBER_STEP_TEXT", DefaultLanguageHighlighterColors.IDENTIFIER)
    @JvmField
    internal val QUOTED: TextAttributesKey =
        key("CUCUMBER_QUOTED", DefaultLanguageHighlighterColors.STRING)
    @JvmField
    internal val NUMBER: TextAttributesKey =
        key("CUCUMBER_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
    @JvmField
    internal val OUTLINE_PARAM: TextAttributesKey =
        key("CUCUMBER_OUTLINE_PARAM", DefaultLanguageHighlighterColors.PARAMETER)
    @JvmField
    internal val TAG: TextAttributesKey =
        key("CUCUMBER_TAG", DefaultLanguageHighlighterColors.METADATA)
    @JvmField
    internal val COMMENT: TextAttributesKey =
        key("CUCUMBER_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
    @JvmField
    internal val TABLE_PIPE: TextAttributesKey =
        key("CUCUMBER_TABLE_PIPE", DefaultLanguageHighlighterColors.BRACES)
    @JvmField
    internal val TABLE_CELL: TextAttributesKey =
        key("CUCUMBER_TABLE_CELL", DefaultLanguageHighlighterColors.CONSTANT)
    @JvmField
    internal val DOC_STRING: TextAttributesKey =
        key("CUCUMBER_DOC_STRING", DefaultLanguageHighlighterColors.STRING)
    @JvmField
    internal val TEXT: TextAttributesKey =
        key("CUCUMBER_TEXT", DefaultLanguageHighlighterColors.DOC_COMMENT)

    private val KEYS: Map<IElementType, TextAttributesKey> =
        mapOf(
            FeatureTokenTypes.KEYWORD to KEYWORD,
            FeatureTokenTypes.TITLE to TITLE,
            FeatureTokenTypes.STEP_KEYWORD to STEP_KEYWORD,
            FeatureTokenTypes.STEP_TEXT to STEP_TEXT,
            FeatureTokenTypes.QUOTED to QUOTED,
            FeatureTokenTypes.NUMBER to NUMBER,
            FeatureTokenTypes.OUTLINE_PARAM to OUTLINE_PARAM,
            FeatureTokenTypes.TAG to TAG,
            FeatureTokenTypes.COMMENT to COMMENT,
            FeatureTokenTypes.TABLE to TABLE_PIPE,
            FeatureTokenTypes.TABLE_CELL to TABLE_CELL,
            FeatureTokenTypes.DOC_STRING to DOC_STRING,
            FeatureTokenTypes.TEXT to TEXT)

    private fun key(name: String, fallback: TextAttributesKey): TextAttributesKey =
        TextAttributesKey.createTextAttributesKey(name, fallback)
  }
}
