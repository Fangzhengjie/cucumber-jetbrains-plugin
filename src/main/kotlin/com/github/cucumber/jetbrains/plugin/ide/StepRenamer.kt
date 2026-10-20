package com.github.cucumber.jetbrains.plugin.ide

import com.github.cucumber.jetbrains.plugin.core.StepRename
import com.intellij.codeInsight.hint.HintManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager

/**
 * Renames a step in every feature file and, when that can be done safely, the literal parts of the
 * pattern of the step definition it matches (keeping parameter values in the other steps using it).
 * Everything happens in one undoable command.
 */
object StepRenamer {

  const val NOTIFICATION_GROUP = "Cucumber Step Definitions"

  private data class Change(val file: VirtualFile, val start: Int, val end: Int, val old: String, val new: String)

  private data class Plan(val changes: List<Change>, val steps: Int, val definitionRenamed: Boolean, val note: String?)

  /**
   * Asks for the new text of `oldText` and renames it. `definition` restricts the rename to one step
   * definition (when started from code or resolved from the step); otherwise the definitions
   * matching `matchText` (`oldText` with Scenario Outline values substituted) are used.
   */
  fun rename(
      project: Project,
      editor: Editor?,
      oldText: String,
      definition: StepIndex.Definition?,
      matchText: String = oldText,
  ) {
    val newText =
        Messages.showInputDialog(
                project,
                "Rename the step in all feature files (and its step definition when possible):",
                "Rename Cucumber Step",
                null,
                oldText,
                object : InputValidator {
                  override fun checkInput(inputString: String?): Boolean =
                      inputString != null && inputString.isNotBlank() && inputString.trim() != oldText

                  override fun canClose(inputString: String?): Boolean = checkInput(inputString)
                })
            ?.trim() ?: return
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val plan =
        stepReadAction(project, "Finding steps to rename...") {
          plan(project, oldText, newText, definition, matchText)
        } ?: return
    if (plan.changes.isEmpty()) {
      report(project, editor, plan.note ?: "Nothing to rename")
      return
    }
    val files = plan.changes.map { it.file }.distinct()
    val status = ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(files)
    if (status.hasReadonlyFiles()) {
      report(
          project,
          editor,
          "Nothing was renamed: " +
              status.readonlyFiles.joinToString { it.name } +
              (if (status.readonlyFiles.size == 1) " is" else " are") +
              " read-only")
      return
    }
    apply(project, plan.changes)
    val summary =
        "Renamed " +
            plan.steps +
            (if (plan.steps == 1) " feature step" else " feature steps") +
            (if (plan.definitionRenamed) " and the step definition pattern" else "")
    report(project, if (plan.note == null) editor else null, if (plan.note == null) summary else "$summary. ${plan.note}")
  }

  private fun plan(
      project: Project,
      oldText: String,
      newText: String,
      hint: StepIndex.Definition?,
      matchText: String,
  ): Plan {
    val usages = StepUsageIndex.get(project)
    val definitions = if (hint != null) listOf(hint) else usages.index.matching(matchText)
    val renamed = LinkedHashMap<StepUsageIndex.Usage, String>()
    var definitionChange: Change? = null
    var note: String? = null
    if (definitions.size == 1) {
      val definition = definitions[0]
      val file = definition.file.viewProvider.virtualFile.takeIf { it.isValid }
      val document = file?.let { FileDocumentManager.getInstance().getDocument(it) }
      val edit =
          if (file == null || document == null) null
          else StepRename.renameDefinition(document.text, definition.info, oldText, newText, matchText)
      // Every step using the definition must follow it, or the change would leave some undefined.
      val rewritten =
          edit?.let {
            usages.of(definition).associateWith { usage ->
              StepRename.rewriteStep(it.plan, definition.info.regex, usage.text)
            }
          }
      val stranded = rewritten?.count { it.value == null } ?: 0
      if (file != null && document != null && edit != null && rewritten != null && stranded == 0) {
        definitionChange =
            Change(
                file,
                edit.start,
                edit.end,
                document.charsSequence.subSequence(edit.start, edit.end).toString(),
                edit.replacement)
        for ((usage, text) in rewritten) {
          if (text != null && text != usage.text) {
            renamed[usage] = text
          }
        }
      } else {
        note =
            "The step definition pattern (" +
                (file?.name ?: "unknown file") +
                ") was not changed: " +
                (if (stranded > 0) "$stranded step(s) using it could not be rewritten"
                else "it cannot be rewritten safely") +
                ", update it by hand"
      }
    } else if (definitions.isEmpty()) {
      note = "No step definition matches the step; only feature steps were renamed"
    } else {
      note = "The step matches ${definitions.size} step definitions; only feature steps were renamed"
    }
    // Steps with the same text, except those bound to another definition (keyword-sensitive
    // frameworks can bind `Given x` and `When x` to different definitions).
    val boundElsewhere =
        if (definitions.size != 1) emptySet()
        else usages.index.definitions.filter { it != definitions[0] }.flatMap { usages.of(it) }.toSet()
    for (usage in usages.steps) {
      if (usage.text == oldText && usage !in renamed && usage !in boundElsewhere) {
        renamed[usage] = newText
      }
    }
    val changes =
        renamed.map { (usage, text) ->
          Change(usage.file, usage.textOffset, usage.textOffset + usage.text.length, usage.text, text)
        }
    return Plan(listOfNotNull(definitionChange) + changes, changes.size, definitionChange != null, note)
  }

  private fun apply(project: Project, changes: List<Change>) {
    val byFile = changes.groupBy { it.file }
    val psiManager = PsiManager.getInstance(project)
    val psiFiles = byFile.keys.mapNotNull { if (it.isValid) psiManager.findFile(it) else null }
    WriteCommandAction.writeCommandAction(project, *psiFiles.toTypedArray())
        .withName("Rename Cucumber Step")
        .withGlobalUndo()
        .run<RuntimeException> {
          val documents = PsiDocumentManager.getInstance(project)
          for ((file, fileChanges) in byFile) {
            val document = FileDocumentManager.getInstance().getDocument(file) ?: continue
            for (change in fileChanges.sortedByDescending { it.start }) {
              if (change.end <= document.textLength &&
                  document.charsSequence.subSequence(change.start, change.end).toString() == change.old) {
                document.replaceString(change.start, change.end, change.new)
              }
            }
            documents.commitDocument(document)
          }
        }
  }

  private fun report(project: Project, editor: Editor?, message: String) {
    if (editor != null && !editor.isDisposed) {
      HintManager.getInstance().showInformationHint(editor, message)
    } else {
      Notification(NOTIFICATION_GROUP, "Rename Cucumber step", message, NotificationType.INFORMATION)
          .notify(project)
    }
  }
}
