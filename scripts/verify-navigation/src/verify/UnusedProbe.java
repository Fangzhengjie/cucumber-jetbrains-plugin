package verify;

import io.github.fangzhengjie.cucumber.ide.StepIndex;
import io.github.fangzhengjie.cucumber.ide.StepUsageIndex;
import io.github.fangzhengjie.cucumber.ide.UnusedStepDefinitions;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.usageView.UsageInfo;
import com.intellij.usages.Usage;
import com.intellij.usages.UsageInfo2UsageAdapter;
import com.intellij.usages.UsageView;
import com.intellij.usages.UsageViewManager;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verification harness only (never shipped): runs "Find Unused Step Definitions" on a project
 * (the Find tool window included), deletes the unused definitions found, prints the edited files
 * and checks that every feature step still resolves; then undoes the deletion.
 * Args: projectDir outputFile file1 [file2 ...] (files to print, relative to the project).
 */
public final class UnusedProbe implements ApplicationStarter {

  private PrintStream out;

  @Override
  public int getRequiredModality() {
    return NOT_IN_EDT;
  }

  @Override
  public boolean isHeadless() {
    return true;
  }

  @Override
  public void main(List<String> args) {
    int exit = 0;
    try {
      Path projectDir = Path.of(args.get(1));
      out = new PrintStream(Files.newOutputStream(Path.of(args.get(2))), true, StandardCharsets.UTF_8);
      Project project = ProjectUtil.openOrImport(projectDir, null, false);
      if (project == null) {
        throw new IllegalStateException("project did not open");
      }
      for (int i = 0; i < 3; i++) {
        DumbService.getInstance(project).waitForSmartMode();
        Thread.sleep(2000);
      }
      run(project, projectDir, args.subList(3, args.size()));
    } catch (Throwable t) {
      exit = 1;
      if (out != null) {
        t.printStackTrace(out);
      } else {
        t.printStackTrace();
      }
    } finally {
      if (out != null) {
        out.println("DONE");
        out.close();
      }
    }
    System.exit(exit);
  }

  private void run(Project project, Path projectDir, List<String> files) throws Exception {
    List<UnusedStepDefinitions.Target> found = ReadAction.compute(() -> {
      StepUsageIndex.Usages usages = StepUsageIndex.INSTANCE.get(project);
      List<StepIndex.Definition> unused = UnusedStepDefinitions.INSTANCE.find(usages);
      out.println("[find] definitions=" + usages.getIndex().getDefinitions().size() + " steps=" + usages.getSteps().size()
          + " unused=" + unused.size());
      List<UnusedStepDefinitions.Target> targets = new ArrayList<>();
      for (StepIndex.Definition d : unused) {
        Document document = FileDocumentManager.getInstance().getDocument(d.getFile().getVirtualFile());
        out.println("  unused " + d.getFile().getName() + ":" + (document.getLineNumber(d.getInfo().getOffset()) + 1)
            + "  " + d.getInfo().getLanguage() + "  '" + d.getInfo().getPattern() + "'");
        targets.add(new UnusedStepDefinitions.Target(d.getFile().getVirtualFile(), d.getInfo().getOffset()));
      }
      for (UsageInfo info : UnusedStepDefinitions.INSTANCE.usageInfos(unused)) {
        Document document = FileDocumentManager.getInstance().getDocument(info.getVirtualFile());
        int start = info.getSegment().getStartOffset();
        int end = info.getSegment().getEndOffset();
        out.println("  usageInfo " + info.getVirtualFile().getName() + " [" + start + "," + end + ") '"
            + document.getText().substring(start, end) + "'");
      }
      return targets;
    });

    // The real action's view: the Find tool window.
    List<UnusedStepDefinitions.Target> fromView = new ArrayList<>();
    try {
      ApplicationManager.getApplication().invokeAndWait(() -> UnusedStepDefinitions.INSTANCE.show(project));
      AtomicReference<UsageView> viewRef = new AtomicReference<>();
      ApplicationManager.getApplication().invokeAndWait(
          () -> viewRef.set(UsageViewManager.getInstance(project).getSelectedUsageView()));
      UsageView view = viewRef.get();
      if (view == null) {
        out.println("[view] no usage view");
      } else {
        Thread.sleep(2000);
        ApplicationManager.getApplication().invokeAndWait(() -> {
          out.println("[view] usage view " + view.getClass().getSimpleName() + " usages=" + view.getUsagesCount());
          for (Usage usage : view.getUsages()) {
            UsageInfo info = ((UsageInfo2UsageAdapter) usage).getUsageInfo();
            out.println("  view usage " + info.getVirtualFile().getName() + ":" + (((UsageInfo2UsageAdapter) usage).getLine() + 1)
                + " '" + usage.getPresentation().getPlainText().trim() + "' navigable=" + usage.canNavigate());
          }
          fromView.addAll(UnusedStepDefinitions.INSTANCE.targets(view, false));
          out.println("[view] selected=" + UnusedStepDefinitions.INSTANCE.targets(view, true).size()
              + " buttons=" + buttons(view.getComponent()));
        });
        out.println("[view] targets from view (not excluded)=" + fromView.size() + " same as found=" + new LinkedHashSet<>(fromView).equals(new LinkedHashSet<>(found)));
      }
    } catch (Throwable t) {
      out.println("[view] threw " + t);
    }

    // The editor action, on a used definition (left alone) and on the unused Java one (dialog answered OK).
    editorAction(project, found);

    // Delete them (the dialog's code path without the dialog), as listed by the view.
    List<UnusedStepDefinitions.Target> targets = fromView.isEmpty() ? found : fromView;
    UnusedStepDefinitions.Plan plan = ReadAction.compute(() -> UnusedStepDefinitions.INSTANCE.plan(project, targets));
    out.println("[plan] deletions=" + plan.getDeletions().size() + " skipped=" + plan.getSkipped().size());
    for (UnusedStepDefinitions.Deletion deletion : plan.getDeletions()) {
      out.println("  delete " + deletion.getLabel() + "  block:\n" + indent(deletion.getBlock().text(deletion.getDocument().getText())));
    }
    for (String skipped : plan.getSkipped()) {
      out.println("  skipped " + skipped);
    }
    int[] deleted = new int[1];
    List<String> blockTexts = new ArrayList<>();
    for (UnusedStepDefinitions.Deletion deletion : plan.getDeletions()) {
      blockTexts.add(ReadAction.compute(() -> deletion.getBlock().text(deletion.getDocument().getText())));
    }
    ApplicationManager.getApplication().invokeAndWait(() -> deleted[0] = UnusedStepDefinitions.INSTANCE.apply(project, plan));
    out.println("[apply] deleted=" + deleted[0]);
    for (String relative : files) {
      VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectDir.resolve(relative));
      Document document = ReadAction.compute(() -> FileDocumentManager.getInstance().getDocument(vf));
      out.println("--- " + relative + " after deletion (unsaved document):");
      out.println(indent(ReadAction.compute(document::getText)));
    }
    resolveAll(project, "after deletion");

    // Undo it.
    try {
      Set<Document> documents = new LinkedHashSet<>();
      for (UnusedStepDefinitions.Deletion deletion : plan.getDeletions()) {
        documents.add(deletion.getDocument());
      }
      ApplicationManager.getApplication().invokeAndWait(() -> {
        Document first = documents.iterator().next();
        VirtualFile vf = FileDocumentManager.getInstance().getFile(first);
        FileEditor editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vf, 0), true) == null
            ? null : FileEditorManager.getInstance(project).getSelectedEditor(vf);
        UndoManager undo = UndoManager.getInstance(project);
        out.println("[undo] available=" + undo.isUndoAvailable(editor) + " action='" + undo.getUndoActionNameAndDescription(editor).first + "'");
        undo.undo(editor);
      });
      int restored = 0;
      for (int i = 0; i < plan.getDeletions().size(); i++) {
        String text = ReadAction.compute(plan.getDeletions().get(i).getDocument()::getText);
        if (text.contains(blockTexts.get(i))) {
          restored++;
        }
      }
      out.println("[undo] definitions back after undo: " + restored + "/" + plan.getDeletions().size());
      ReadAction.run(() -> out.println("[undo] unused after undo=" + UnusedStepDefinitions.INSTANCE.find(StepUsageIndex.INSTANCE.get(project)).size()));
    } catch (Throwable t) {
      out.println("[undo] threw " + t);
    }
  }

  /** Every feature step resolves to exactly one definition. */
  private void resolveAll(Project project, String when) {
    ReadAction.run(() -> {
      StepUsageIndex.Usages usages = StepUsageIndex.INSTANCE.get(project);
      int undefined = 0;
      int ambiguous = 0;
      for (StepUsageIndex.Usage step : usages.getSteps()) {
        int n = usages.getIndex().matching(step.getMatchText(), step.getEffectiveKeyword()).size();
        if (n == 0) {
          undefined++;
          out.println("  UNDEFINED " + step.describe());
        } else if (n > 1) {
          ambiguous++;
        }
      }
      out.println("[resolve " + when + "] definitions=" + usages.getIndex().getDefinitions().size() + " steps="
          + usages.getSteps().size() + " undefined=" + undefined + " ambiguous=" + ambiguous
          + " unused=" + UnusedStepDefinitions.INSTANCE.find(usages).size());
    });
  }

  private void editorAction(Project project, List<UnusedStepDefinitions.Target> found) {
    UnusedStepDefinitions.Target unused = found.stream().filter(t -> t.getFile().getName().equals("Steps.java")).findFirst().orElse(null);
    if (unused == null) {
      out.println("[editor action] no unused Steps.java definition");
      return;
    }
    VirtualFile vf = unused.getFile();
    Document document = ReadAction.compute(() -> FileDocumentManager.getInstance().getDocument(vf));
    int used = ReadAction.compute(() -> document.getText().indexOf("the java service is ready"));
    com.intellij.openapi.ui.TestDialogManager.setTestDialog(com.intellij.openapi.ui.TestDialog.OK);
    for (int offset : new int[] {used, unused.getOffset() + 3}) {
      try {
        ApplicationManager.getApplication().invokeAndWait(() -> {
          com.intellij.openapi.editor.Editor editor =
              FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vf, offset), true);
          editor.getCaretModel().moveToOffset(offset);
          com.intellij.psi.PsiFile psi = com.intellij.psi.PsiManager.getInstance(project).findFile(vf);
          com.intellij.openapi.actionSystem.AnAction action =
              com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("Cucumber.DeleteUnusedStepDefinition");
          com.intellij.openapi.actionSystem.DataContext context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
              .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
              .add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, editor)
              .add(com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE, psi)
              .add(com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE, vf)
              .build();
          com.intellij.openapi.actionSystem.ex.ActionUtil.invokeAction(
              action, context, com.intellij.openapi.actionSystem.ActionPlaces.EDITOR_POPUP, null, null);
        });
      } catch (Throwable t) {
        out.println("[editor action] threw " + t);
      }
      String text = ReadAction.compute(document::getText);
      out.println("[editor action] caret on line " + (offset == used ? "of the used 'the java service is ready'" : "of the unused probe step")
          + " -> still contains used=" + text.contains("the java service is ready")
          + " probe=" + text.contains("probeUnused") + " definitions in file="
          + ReadAction.compute(() -> StepUsageIndex.INSTANCE.get(project).definitionsIn(vf).size()));
    }
  }

  private static List<String> buttons(java.awt.Component component) {
    List<String> texts = new ArrayList<>();
    if (component instanceof javax.swing.AbstractButton button && button.getText() != null && !button.getText().isEmpty()) {
      texts.add(button.getText());
    }
    if (component instanceof java.awt.Container container) {
      for (java.awt.Component child : container.getComponents()) {
        texts.addAll(buttons(child));
      }
    }
    return texts;
  }

  private static String indent(String text) {
    return "      | " + text.replace("\n", "\n      | ");
  }
}
