package io.github.fangzhengjie.cucumber.feature

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext

class FeatureCompletionContributor : CompletionContributor() {

  init {
    extend(
        CompletionType.BASIC,
        PlatformPatterns.psiElement().withLanguage(FeatureLanguage),
        object : CompletionProvider<CompletionParameters>() {
          override fun addCompletions(
              parameters: CompletionParameters,
              context: ProcessingContext,
              result: CompletionResultSet
          ) {
            if (FeatureKeywordCompletion.addCompletions(parameters, result)) {
              return
            }
            FeatureStepCompletion.addCompletions(parameters, result)
          }
        })
  }
}
