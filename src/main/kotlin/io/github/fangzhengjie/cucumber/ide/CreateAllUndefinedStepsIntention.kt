package io.github.fangzhengjie.cucumber.ide

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.SimpleListCellRenderer
import io.github.fangzhengjie.cucumber.core.Language
import io.github.fangzhengjie.cucumber.core.Snippets
import io.github.fangzhengjie.cucumber.core.StepFileTemplates
import io.github.fangzhengjie.cucumber.feature.FeatureOutlines
import io.github.fangzhengjie.cucumber.feature.FeatureStep
import io.github.fangzhengjie.cucumber.feature.FeatureTokenTypes
import java.io.IOException
import java.util.EnumMap
import javax.swing.JList

/**
 * Alt+Enter on an undefined step: "Create all undefined step definitions" adds a snippet for each
 * undefined step of the feature file (one per step text) to a chosen step file of the detected
 * language, or to a new step file created next to an existing one.
 */
class CreateAllUndefinedStepsIntention : IntentionAction {

  /** Where to add the snippets: an existing step file, or a new one next to `file`. */
  private data class Choice(val file: VirtualFile, val createNew: Boolean)

  private data class Request(
      val language: Language,
      val items: List<StepFileTemplates.Item>,
      val files: List<VirtualFile>,
  )

  override fun getText(): String = "Create all undefined step definitions"

  override fun getFamilyName(): String = "Create all undefined Cucumber step definitions"

  override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
    if (editor == null || file == null) {
      return false
    }
    val step = StepAtCaret.featureStep(editor, file) ?: return false
    val index = StepIndex.get(project)
    return step.stepText().isNotBlank() &&
        index.matching(step).isEmpty() &&
        index.files.any { it.file.isWritable }
  }

  override fun startInWriteAction(): Boolean = false

  override fun generatePreview(
      project: Project,
      editor: Editor,
      file: PsiFile
  ): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

  override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
    if (editor == null || file == null) {
      return
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val request = stepReadAction(project, STEP_INDEX_PROGRESS) { request(project, file) }
    if (request == null || request.items.isEmpty()) {
      HintManager.getInstance().showInformationHint(editor, "No undefined steps")
      return
    }
    if (request.files.isEmpty()) {
      HintManager.getInstance()
          .showInformationHint(
              editor,
              "No writable " + request.language.displayName() + " step definition file found")
      return
    }
    val choices = request.files.map { Choice(it, false) }.toMutableList()
    if (StepFileTemplates.supports(request.language)) {
      choices.add(Choice(request.files[0], true))
    }
    val base = project.basePath
    JBPopupFactory.getInstance()
        .createPopupChooserBuilder(choices)
        .setTitle("Create " + request.items.size + " Step Definitions In")
        .setRenderer(
            object : SimpleListCellRenderer<Choice>() {
              override fun customize(
                  list: JList<out Choice>,
                  value: Choice?,
                  index: Int,
                  selected: Boolean,
                  hasFocus: Boolean,
              ) {
                text =
                    when {
                      value == null -> ""
                      value.createNew -> "Create new step file..."
                      else -> relativePath(base, value.file)
                    }
              }
            })
        .setItemChosenCallback { choice ->
          if (choice.createNew) {
            createNewFile(project, choice.file, request)
          } else {
            addTo(project, choice.file, request)
          }
        }
        .createPopup()
        .showInBestPositionFor(editor)
  }

  private fun relativePath(base: String?, file: VirtualFile): String {
    val path = file.path
    return if (base != null && path.startsWith("$base/")) path.substring(base.length + 1) else path
  }

  private fun request(project: Project, file: PsiFile): Request? {
    val index = StepIndex.get(project)
    val votes: MutableMap<Language, Int> = EnumMap(Language::class.java)
    val items = ArrayList<StepFileTemplates.Item>()
    val seen = HashSet<String>()
    for (step in PsiTreeUtil.findChildrenOfType(file, FeatureStep::class.java)) {
      val text = FeatureOutlines.matchText(step)
      if (text.isBlank()) {
        continue
      }
      val definitions = index.matching(step)
      if (definitions.isEmpty()) {
        if (seen.add(step.effectiveKeyword() + " " + text)) {
          items.add(
              StepFileTemplates.Item(
                  text,
                  Snippets.request(
                      step.effectiveKeyword(),
                      text,
                      step.hasAttachment(FeatureTokenTypes.DOC_STRING),
                      step.hasAttachment(FeatureTokenTypes.TABLE))))
        }
      } else {
        for (definition in definitions) {
          votes.merge(definition.info.language, 1, Int::plus)
        }
      }
    }
    val language =
        votes.entries.maxByOrNull { it.value }?.key
            ?: index.definitions
                .groupingBy { it.info.language }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
            ?: return null
    val files =
        index.files
            .map { it.file }
            .filter { it.isWritable }
            .mapNotNull { it.viewProvider.virtualFile }
            .filter { Language.forExtension(it.extension) == language }
            .distinct()
            .sortedBy { it.path }
    return Request(language, items, files)
  }

  private fun addTo(project: Project, file: VirtualFile, request: Request) {
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return
    val batch =
        StepFileTemplates.addSnippets(request.language, document.text, file.name, request.items)
    if (batch.snippets.isEmpty()) {
      return
    }
    WriteCommandAction.writeCommandAction(project)
        .withName("Create Undefined Cucumber Step Definitions")
        .run<RuntimeException> {
          for (snippet in batch.snippets) {
            snippet.edits
                .sortedByDescending { it.offset }
                .forEach { edit -> document.insertString(edit.offset, edit.code) }
          }
          PsiDocumentManager.getInstance(project).commitDocument(document)
          FileDocumentManager.getInstance().saveDocument(document)
        }
    OpenFileDescriptor(project, file, batch.snippets.last().navigationOffset()).navigate(true)
  }

  private fun createNewFile(project: Project, reference: VirtualFile, request: Request) {
    val directory = reference.parent ?: return
    val referenceDocument = FileDocumentManager.getInstance().getDocument(reference) ?: return
    val referenceText = referenceDocument.text
    val keywords = request.items.map { it.request.keyword }
    val name =
        Messages.showInputDialog(
                project,
                "New " +
                    request.language.displayName() +
                    " step file in " +
                    directory.presentableUrl +
                    ":",
                "Create New Step File",
                null,
                StepFileTemplates.defaultName(request.language),
                object : InputValidator {
                  override fun checkInput(inputString: String?): Boolean {
                    val template =
                        inputString?.let {
                          StepFileTemplates.create(
                              request.language, reference.name, referenceText, it.trim(), keywords)
                        } ?: return false
                    return directory.findChild(template.fileName) == null
                  }

                  override fun canClose(inputString: String?): Boolean = checkInput(inputString)
                })
            ?.trim() ?: return
    val template =
        StepFileTemplates.create(request.language, reference.name, referenceText, name, keywords)
            ?: return
    val batch =
        StepFileTemplates.addSnippets(
            request.language, template.text, template.fileName, request.items)
    var failure: IOException? = null
    val created =
        WriteCommandAction.writeCommandAction(project)
            .withName("Create Cucumber Step File")
            .compute<VirtualFile?, RuntimeException> {
              try {
                val file = directory.createChildData(this, template.fileName)
                val document = FileDocumentManager.getInstance().getDocument(file)
                if (document != null) {
                  document.setText(batch.text)
                  PsiDocumentManager.getInstance(project).commitDocument(document)
                  FileDocumentManager.getInstance().saveDocument(document)
                }
                file
              } catch (cannotCreate: IOException) {
                failure = cannotCreate
                null
              }
            }
    if (created == null) {
      Messages.showErrorDialog(
          project,
          "Cannot create " +
              FileUtil.toSystemDependentName(directory.path + "/" + template.fileName) +
              ": " +
              failure?.message,
          "Create New Step File")
      return
    }
    val offset = if (batch.snippets.isEmpty()) 0 else batch.snippets.last().navigationOffset()
    OpenFileDescriptor(project, created, offset).navigate(true)
  }
}
