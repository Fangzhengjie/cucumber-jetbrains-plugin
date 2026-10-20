package com.github.cucumber.jetbrains.plugin.feature

import com.github.cucumber.jetbrains.plugin.core.Dialect
import com.github.cucumber.jetbrains.plugin.core.GherkinDialects
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.psi.FileViewProvider
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

class FeatureFile internal constructor(viewProvider: FileViewProvider) :
    PsiFileBase(viewProvider, FeatureLanguage) {

  override fun getFileType(): FeatureFileType = FeatureFileType

  /** The `# language:` dialect, read once per file change (steps and markers ask per element). */
  fun dialect(): Dialect =
      CachedValuesManager.getCachedValue(this) {
        CachedValueProvider.Result.create(GherkinDialects.dialectFor(viewProvider.contents), this)
      }

  override fun toString(): String = "Cucumber Feature: $name"
}
