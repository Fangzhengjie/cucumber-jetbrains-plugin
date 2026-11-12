package io.github.fangzhengjie.cucumber.feature

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.fangzhengjie.cucumber.core.StepArguments
import io.github.fangzhengjie.cucumber.ide.StepIndex

/**
 * Quick documentation (Ctrl+Q / hover) on a step: the matching step definitions with their location
 * and the argument values the step passes.
 */
class FeatureStepDocumentationProvider : AbstractDocumentationProvider() {

  override fun getCustomDocumentationElement(
      editor: Editor,
      file: PsiFile,
      contextElement: PsiElement?,
      targetOffset: Int
  ): PsiElement? {
    if (file !is FeatureFile || contextElement == null) {
      return null
    }
    return PsiTreeUtil.getParentOfType(contextElement, FeatureStep::class.java, false)
  }

  override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
    val step = element as? FeatureStep ?: return null
    val text = FeatureOutlines.matchText(step)
    val index = StepIndex.get(step.project)
    val definitions = index.matching(step)
    val html = StringBuilder()
    html
        .append(DocumentationMarkup.DEFINITION_START)
        .append(escape(step.keyword() + " " + step.stepText()))
        .append(DocumentationMarkup.DEFINITION_END)
    html.append(DocumentationMarkup.CONTENT_START)
    if (text != step.stepText()) {
      html
          .append("<p>With the first Examples row: <code>")
          .append(escape(text))
          .append("</code></p>")
    }
    if (definitions.isEmpty()) {
      val keyword = step.effectiveKeyword()
      val mismatch =
          StepArguments.bestMismatch(
              index.definitions.map { it.info }.filter { it.acceptsKeyword(keyword) }, text)
      html.append("<p>Undefined step")
      if (mismatch != null) {
        html.append(": ").append(escape(mismatch.message))
      }
      html.append("</p>")
    } else if (definitions.size > 1) {
      html.append("<p>Ambiguous step: ").append(definitions.size).append(" definitions match</p>")
    }
    html.append(DocumentationMarkup.CONTENT_END)
    for (definition in definitions) {
      val info = definition.info
      html.append(DocumentationMarkup.SECTIONS_START)
      section(html, "Definition:", "<code>" + escape(info.pattern) + "</code>")
      val file: PsiFile? = definition.file
      if (file != null) {
        val line = StringUtil.offsetToLineNumber(file.viewProvider.contents, info.offset) + 1
        section(html, "Location:", escape(file.name + ":" + line))
      }
      section(html, "Language:", escape(info.language.displayName()))
      val arguments = StepArguments.extract(info, text)
      if (arguments.isNotEmpty()) {
        val list = StringBuilder()
        for (argument in arguments) {
          if (list.isNotEmpty()) list.append("<br>")
          val label =
              if (argument.type.isEmpty() || argument.type == "{" + argument.name + "}")
                  argument.name
              else argument.name + " " + argument.type
          list
              .append(escape(label))
              .append(" = <code>")
              .append(escape(argument.value ?: ""))
              .append("</code>")
        }
        section(html, "Arguments:", list.toString())
      }
      html.append(DocumentationMarkup.SECTIONS_END)
    }
    return html.toString()
  }

  private fun section(html: StringBuilder, header: String, content: String) {
    html
        .append(DocumentationMarkup.SECTION_HEADER_START)
        .append(header)
        .append(DocumentationMarkup.SECTION_SEPARATOR)
        .append(content)
        .append(DocumentationMarkup.SECTION_END)
  }

  private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)
}
