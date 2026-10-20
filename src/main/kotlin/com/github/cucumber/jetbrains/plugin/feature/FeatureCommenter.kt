package com.github.cucumber.jetbrains.plugin.feature

import com.intellij.lang.Commenter

/** Ctrl+/ toggles `# ` line comments in `.feature` files. */
class FeatureCommenter : Commenter {

  override fun getLineCommentPrefix(): String = "# "

  override fun getBlockCommentPrefix(): String? = null

  override fun getBlockCommentSuffix(): String? = null

  override fun getCommentedBlockCommentPrefix(): String? = null

  override fun getCommentedBlockCommentSuffix(): String? = null
}
