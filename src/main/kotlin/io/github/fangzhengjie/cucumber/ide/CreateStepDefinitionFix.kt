package io.github.fangzhengjie.cucumber.ide

import io.github.fangzhengjie.cucumber.core.Language
import io.github.fangzhengjie.cucumber.core.Snippets
import io.github.fangzhengjie.cucumber.feature.FeatureOutlines
import io.github.fangzhengjie.cucumber.feature.FeatureStep
import io.github.fangzhengjie.cucumber.feature.FeatureTokenTypes
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * Adds a snippet for an undefined step to a step definition file. The step index is computed in a
 * cancellable read action off the EDT, then the snippet is inserted in a write command.
 */
class CreateStepDefinitionFix : LocalQuickFix {

  private class Plan(
      val file: PsiFile,
      val virtualFile: VirtualFile,
      val document: Document,
      val snippet: Snippets.Snippet,
  )

  override fun getFamilyName(): String = "Create Cucumber step definition"

  override fun startInWriteAction(): Boolean = false

  override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo =
      IntentionPreviewInfo.EMPTY

  override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val plan = stepReadAction(project, STEP_INDEX_PROGRESS) { plan(project, descriptor) } ?: return
    WriteCommandAction.runWriteCommandAction(
        project,
        "Create Cucumber Step Definition",
        null,
        {
          plan.snippet.edits
              .sortedByDescending { it.offset }
              .forEach { edit -> plan.document.insertString(edit.offset, edit.code) }
          PsiDocumentManager.getInstance(project).commitDocument(plan.document)
          FileDocumentManager.getInstance().saveDocument(plan.document)
        },
        plan.file)
    OpenFileDescriptor(project, plan.virtualFile, plan.snippet.navigationOffset()).navigate(true)
  }

  private fun plan(project: Project, descriptor: ProblemDescriptor): Plan? {
    val step = descriptor.psiElement as? FeatureStep ?: return null
    val index = StepIndex.get(project)
    val destination = index.files.filter { it.file.isWritable }.minByOrNull { it.file.name } ?: return null
    val file = destination.file
    val virtualFile = file.virtualFile ?: return null
    val language = Language.forExtension(virtualFile.extension) ?: return null
    val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
    val request =
        Snippets.request(
            step.effectiveKeyword(),
            FeatureOutlines.matchText(step),
            step.hasAttachment(FeatureTokenTypes.DOC_STRING),
            step.hasAttachment(FeatureTokenTypes.TABLE))
    val snippet = language.adapter().snippet(request, document.text, virtualFile.name)
    return Plan(file, virtualFile, document, snippet)
  }
}