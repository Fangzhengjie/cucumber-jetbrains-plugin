package com.github.cucumber.jetbrains.plugin.feature

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class FeatureParserDefinition : ParserDefinition {

  override fun createLexer(project: Project?): Lexer = FeatureLexer()

  override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
    val file = builder.mark()
    while (!builder.eof()) {
      if (builder.tokenType == FeatureTokenTypes.STEP_KEYWORD) {
        val step = builder.mark()
        val text = builder.originalText
        var end = tokenEnd(builder)
        builder.advanceLexer()
        while (!builder.eof() && !containsLineBreak(text, end, builder.currentOffset)) {
          end = tokenEnd(builder)
          builder.advanceLexer()
        }
        step.done(FeatureTokenTypes.STEP)
      } else {
        builder.advanceLexer()
      }
    }
    file.done(root)
    builder.treeBuilt
  }

  override fun getFileNodeType(): IFileElementType = FeatureTokenTypes.FILE

  override fun getWhitespaceTokens(): TokenSet = TokenSet.create(TokenType.WHITE_SPACE)

  override fun getCommentTokens(): TokenSet = FeatureTokenTypes.COMMENTS

  override fun getStringLiteralElements(): TokenSet =
      TokenSet.create(FeatureTokenTypes.QUOTED, FeatureTokenTypes.DOC_STRING)

  override fun createElement(node: ASTNode): PsiElement {
    if (node.elementType == FeatureTokenTypes.STEP) {
      return FeatureStep(node)
    }
    throw IllegalArgumentException("Unknown Cucumber PSI element: " + node.elementType)
  }

  override fun createFile(viewProvider: FileViewProvider): PsiFile = FeatureFile(viewProvider)

  private companion object {
    fun tokenEnd(builder: PsiBuilder): Int {
      val token: String? = builder.tokenText
      return builder.currentOffset + (token?.length ?: 0)
    }

    fun containsLineBreak(text: CharSequence, from: Int, to: Int): Boolean {
      var i = from
      while (i < to && i < text.length) {
        if (text[i] == '\n') {
          return true
        }
        i++
      }
      return false
    }
  }
}
