package com.github.cucumber.jetbrains.plugin.ide

import com.github.cucumber.jetbrains.plugin.core.StepDefinitionBlocks
import com.github.cucumber.jetbrains.plugin.core.StepRename
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageView
import com.intellij.usages.UsageViewManager
import com.intellij.usages.UsageViewPresentation

/**
 * Step definitions no feature step uses: listing them in the Find tool window and deleting them
 * (the whole method / function / registration, see [StepDefinitionBlocks]).
 */
object UnusedStepDefinitions {

  /** A step definition to delete: its file and the offset of its pattern literal. */
  data class Target(val file: VirtualFile, val offset: Int)

  /** A definition that can be deleted, with its block in `document` as of `stamp`. */
  class Deletion(
      val definition: StepIndex.Definition,
      val document: Document,
      val stamp: Long,
      val block: StepDefinitionBlocks.Block,
      val label: String,
  )

  /** What [apply] would delete, and the requested definitions it leaves alone (and why). */
  class Plan(val deletions: List<Deletion>, val skipped: List<String>)

  /** The unused definitions, in index order. Read action. */
  fun find(usages: StepUsageIndex.Usages): List<StepIndex.Definition> =
      usages.index.definitions.filter { definition ->
        ProgressManager.checkCanceled()
        usages.of(definition).isEmpty()
      }

  /** Usages (in the Find tool window sense) at the pattern literal of each definition. Read action. */
  fun usageInfos(definitions: List<StepIndex.Definition>): List<UsageInfo> {
    val infos = ArrayList<UsageInfo>()
    for ((file, inFile) in definitions.groupBy { it.file }) {
      ProgressManager.checkCanceled()
      if (!file.isValid) {
        continue
      }
      val text = file.viewProvider.contents.toString()
      for (definition in inFile) {
        val start = definition.info.offset
        if (start >= text.length) {
          continue
        }
        val end = StepRename.literalAt(definition.info.language, text, start)?.end ?: (start + 1)
        infos.add(UsageInfo(file, start, minOf(end, text.length)))
      }
    }
    return infos
  }

  /** Finds the unused definitions under a cancellable progress and shows them in the Find tool window. */
  fun show(project: Project) {
    val found =
        stepReadAction(project, "Finding unused Cucumber step definitions...") {
          val usages = StepUsageIndex.get(project)
          usageInfos(find(usages)) to usages.index.definitions.size
        } ?: return
    val (infos, total) = found
    if (infos.isEmpty()) {
      notify(project, "No unused step definitions ($total checked)", NotificationType.INFORMATION)
      return
    }
    val presentation = UsageViewPresentation()
    presentation.setTabText("Unused Step Definitions")
    presentation.setToolwindowTitle("Unused Step Definitions")
    presentation.setTargetsNodeText("Unused step definitions")
    presentation.setCodeUsagesString("${infos.size} unused of $total step definitions")
    presentation.setOpenInNewTab(false)
    presentation.setUsageTypeFilteringAvailable(false)
    val view =
        UsageViewManager.getInstance(project)
            .showUsages(UsageTarget.EMPTY_ARRAY, infos.map { UsageInfo2UsageAdapter(it) }.toTypedArray(), presentation)
    view.addButtonToLowerPane(Runnable { delete(project, targets(view, false)) }, "Delete All Not Excluded...")
    view.addButtonToLowerPane(Runnable { delete(project, targets(view, true)) }, "Delete Selected...")
  }

  /** The definitions selected in `view` (`selected`), or all of them that are not excluded. */
  fun targets(view: UsageView, selected: Boolean): List<Target> {
    val usages: Collection<Usage> = if (selected) view.selectedUsages else view.usages - view.excludedUsages
    return usages.mapNotNull { usage ->
      val info = (usage as? UsageInfo2UsageAdapter)?.usageInfo ?: return@mapNotNull null
      val file = info.virtualFile ?: return@mapNotNull null
      val segment = info.segment ?: return@mapNotNull null
      Target(file, segment.startOffset)
    }
  }

  /** Re-checks `targets` (still definitions, still unused) and finds their blocks. Read action. */
  fun plan(project: Project, targets: Collection<Target>): Plan {
    val usages = StepUsageIndex.get(project)
    val deletions = ArrayList<Deletion>()
    val skipped = ArrayList<String>()
    for (target in targets.distinct()) {
      ProgressManager.checkCanceled()
      val definition = usages.definitionsIn(target.file).firstOrNull { it.info.offset == target.offset }
      if (definition == null) {
        skipped.add(target.file.name + ": no step definition at offset " + target.offset + " any more")
        continue
      }
      val document = FileDocumentManager.getInstance().getDocument(target.file)
      val label = label(definition, document)
      val used = usages.of(definition).size
      if (used > 0) {
        skipped.add("$label: used by $used " + StringUtil.pluralize("step", used))
        continue
      }
      if (document == null) {
        skipped.add("$label: file cannot be edited")
        continue
      }
      val block = StepDefinitionBlocks.find(definition.info.language, document.text, definition.info.offset)
      if (block == null) {
        skipped.add("$label: its extent cannot be determined safely")
        continue
      }
      deletions.add(Deletion(definition, document, document.modificationStamp, block, label))
    }
    return Plan(deletions, skipped)
  }

  /**
   * Deletes the planned blocks in one undoable command; returns how many were deleted (files
   * changed since [plan] are left alone). Call on the EDT.
   */
  fun apply(project: Project, plan: Plan): Int {
    val byDocument = plan.deletions.groupBy { it.document }
    val files: Array<PsiFile> = plan.deletions.map { it.definition.file }.distinct().toTypedArray()
    var deleted = 0
    WriteCommandAction.writeCommandAction(project, *files)
        .withName("Delete Unused Step Definitions")
        .run<RuntimeException> {
          for ((document, deletions) in byDocument) {
            if (deletions.any { it.stamp != document.modificationStamp }) {
              continue
            }
            for ((start, end) in StepDefinitionBlocks.deletions(deletions.map { it.block })) {
              document.deleteString(start, end)
            }
            PsiDocumentManager.getInstance(project).commitDocument(document)
            deleted += deletions.size
          }
        }
    return deleted
  }

  /** Deletes the `targets` that are still unused definitions, after confirmation. Call on the EDT. */
  fun delete(project: Project, targets: Collection<Target>) {
    if (targets.isEmpty()) {
      return
    }
    val plan = stepReadAction(project, "Checking Cucumber step definitions...") { plan(project, targets) } ?: return
    if (plan.deletions.isEmpty()) {
      Messages.showInfoMessage(
          project, "Nothing to delete:\n" + list(plan.skipped), "Delete Unused Step Definitions")
      return
    }
    val message = StringBuilder()
    message
        .append("Delete ")
        .append(plan.deletions.size)
        .append(if (plan.deletions.size == 1) " unused step definition" else " unused step definitions")
        .append("?\n\n")
        .append(list(plan.deletions.map { it.label }))
    if (plan.skipped.isNotEmpty()) {
      message.append("\n\nLeft alone:\n").append(list(plan.skipped))
    }
    val answer =
        Messages.showOkCancelDialog(
            project,
            message.toString(),
            "Delete Unused Step Definitions",
            "Delete",
            Messages.getCancelButton(),
            Messages.getQuestionIcon())
    if (answer != Messages.OK) {
      return
    }
    val deleted = apply(project, plan)
    val skipped = plan.skipped.size + plan.deletions.size - deleted
    notify(
        project,
        "Deleted $deleted step " + (if (deleted == 1) "definition" else "definitions") +
            (if (skipped > 0) ", left $skipped alone" else ""),
        if (skipped > 0) NotificationType.WARNING else NotificationType.INFORMATION)
  }

  private fun label(definition: StepIndex.Definition, document: Document?): String {
    val offset = definition.info.offset
    val line = if (document != null && offset <= document.textLength) document.getLineNumber(offset) + 1 else 0
    return definition.file.name + ":" + line + "  " + definition.info.pattern
  }

  private fun list(lines: List<String>): String {
    val shown = lines.take(MAX_LISTED).joinToString("\n") { "  $it" }
    return if (lines.size > MAX_LISTED) shown + "\n  ... and ${lines.size - MAX_LISTED} more" else shown
  }

  private fun notify(project: Project, content: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("Cucumber Step Definitions")
        .createNotification(content, type)
        .notify(project)
  }

  private const val MAX_LISTED = 25
}
