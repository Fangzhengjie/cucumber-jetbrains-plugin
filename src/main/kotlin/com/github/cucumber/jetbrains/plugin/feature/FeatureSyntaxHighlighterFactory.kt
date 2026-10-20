package com.github.cucumber.jetbrains.plugin.feature

import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class FeatureSyntaxHighlighterFactory : SyntaxHighlighterFactory() {

  override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
      FeatureSyntaxHighlighter()
}
