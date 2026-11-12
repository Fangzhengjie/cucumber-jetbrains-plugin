package io.github.fangzhengjie.cucumber.feature

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiFile
import com.intellij.util.ProcessingContext
import io.github.fangzhengjie.cucumber.CucumberIcons
import io.github.fangzhengjie.cucumber.core.Tags

/** Completes `@tags` with the tags used in the project's feature files. */
class FeatureTagCompletionContributor : CompletionContributor() {

  init {
    extend(
        CompletionType.BASIC,
        PlatformPatterns.psiElement(FeatureTokenTypes.TAG).withLanguage(FeatureLanguage),
        object : CompletionProvider<CompletionParameters>() {
          override fun addCompletions(
              parameters: CompletionParameters,
              context: ProcessingContext,
              result: CompletionResultSet
          ) {
            val position = parameters.position
            val length = parameters.offset - position.textRange.startOffset
            if (length < 1 || length > position.textLength) {
              return
            }
            val prefix = position.text.substring(0, length)
            if (!prefix.startsWith("@")) {
              return
            }
            val counts = LinkedHashMap<String, Int>()
            for ((tag, files) in OutlineGotoDeclarationHandler.projectTags(position.project)) {
              counts[tag] = files.values.sumOf { it.size }
            }
            val original = parameters.originalFile
            for (tag in Tags.find(original.viewProvider.contents)) {
              counts.putIfAbsent(tag.name, 1)
            }
            val tags = result.withPrefixMatcher(prefix)
            for ((tag, count) in counts) {
              tags.addElement(
                  LookupElementBuilder.create(tag)
                      .withIcon(CucumberIcons.CUCUMBER)
                      .withTypeText(if (count == 1) "1 usage" else "$count usages", true))
            }
            tags.stopHere()
          }
        })
  }
}

/** Opens tag completion when `@` is typed in a feature file. */
class FeatureTagTypedHandler : TypedHandlerDelegate() {

  override fun checkAutoPopup(
      charTyped: Char,
      project: Project,
      editor: Editor,
      file: PsiFile
  ): Result {
    if (charTyped == '@' && file is FeatureFile) {
      AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
      return Result.STOP
    }
    return Result.CONTINUE
  }
}
