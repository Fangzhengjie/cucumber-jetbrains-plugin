package verify;

import com.github.cucumber.jetbrains.plugin.ide.CucumberModificationTrackers;
import com.github.cucumber.jetbrains.plugin.ide.StepDefinitionLineMarkerProvider;
import com.github.cucumber.jetbrains.plugin.ide.StepIndex;
import com.github.cucumber.jetbrains.plugin.ide.StepUsageIndex;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl;
import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.util.ProperTextRange;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.codeInsight.daemon.impl.LineMarkersPass;
import com.intellij.codeInsight.navigation.CtrlMouseData;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationOrUsageHandler2;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.impl.SimpleDataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.SyntaxTraverser;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verification harness only (never shipped): opens a project headless and reports, for step
 * definition files, the gutter markers, Ctrl+click targets and StepUsageIndex results.
 * Args: projectDir outputFile file1 [file2 ...] (files relative to the project).
 */
public final class NavigationProbe implements ApplicationStarter {

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
      DumbService.getInstance(project).waitForSmartMode();
      timing(project);
      scopeTiming(project);
      for (String relative : args.subList(3, args.size())) {
        if (relative.startsWith("results:")) {
          new ResultsProbe(out, project).run(projectDir.resolve(relative.substring("results:".length())));
          continue;
        }
        if (relative.startsWith("toolwindow")) {
          int colon = relative.indexOf(':');
          new ToolWindowProbe(out, project).run(relative.substring(0, colon), projectDir, relative.substring(colon + 1));
          continue;
        }
        if (relative.startsWith("completion:")) {
          new CompletionProbe(out, project).run(projectDir.resolve(relative.substring("completion:".length())));
          continue;
        }
        probe(project, projectDir.resolve(relative));
      }
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

  /** Cold / warm / after-edit cost of the indexes behind the step definition gutter icons. */
  private void timing(Project project) throws Exception {
    for (int round = 0; round < 4; round++) {
      long vfs = com.intellij.openapi.vfs.VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS.getModificationCount();
      long roots = com.intellij.openapi.roots.ProjectRootModificationTracker.getInstance(project).getModificationCount();
      long defsTracker = CucumberModificationTrackers.Companion.getInstance(project).getDefinitions().getModificationCount();
      long t0 = System.nanoTime();
      int[] files = new int[1];
      ReadAction.run(() -> StepIndex.INSTANCE.forEachProjectFile(project, f -> { files[0]++; return kotlin.Unit.INSTANCE; }));
      long t1 = System.nanoTime();
      int defs = ReadAction.compute(() -> StepIndex.INSTANCE.get(project).getDefinitions().size());
      long t2 = System.nanoTime();
      int steps = ReadAction.compute(() -> StepUsageIndex.INSTANCE.get(project).getSteps().size());
      long t3 = System.nanoTime();
      out.printf("[timing] round=%d projectFiles=%d walk=%dms definitions=%d StepIndex=%dms steps=%d usages=%dms"
              + " | changed: vfsStructure=%d roots=%d defsTracker=%d%n",
          round, files[0], (t1 - t0) / 1_000_000, defs, (t2 - t1) / 1_000_000, steps, (t3 - t2) / 1_000_000,
          com.intellij.openapi.vfs.VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS.getModificationCount() - vfs,
          com.intellij.openapi.roots.ProjectRootModificationTracker.getInstance(project).getModificationCount() - roots,
          CucumberModificationTrackers.Companion.getInstance(project).getDefinitions().getModificationCount() - defsTracker);
      Thread.sleep(3000);
    }
    CucumberModificationTrackers.Companion.getInstance(project).getDefinitions().incModificationCount();
    long t4 = System.nanoTime();
    ReadAction.compute(() -> StepUsageIndex.INSTANCE.get(project).getSteps().size());
    out.printf("[timing] after a step-file edit: %dms%n", (System.nanoTime() - t4) / 1_000_000);
    CucumberModificationTrackers.Companion.getInstance(project).getFeatures().incModificationCount();
    long t6 = System.nanoTime();
    ReadAction.compute(() -> StepUsageIndex.INSTANCE.get(project).getSteps().size());
    out.printf("[timing] after a feature-file edit: %dms%n", (System.nanoTime() - t6) / 1_000_000);
    for (int attempt = 1; attempt <= 2; attempt++) {
      CucumberModificationTrackers trackers = CucumberModificationTrackers.Companion.getInstance(project);
      long files0 = trackers.getFiles().getModificationCount();
      long defs0 = trackers.getDefinitions().getModificationCount();
      long roots0 = com.intellij.openapi.roots.ProjectRootModificationTracker.getInstance(project).getModificationCount();
      ApplicationManager.getApplication().invokeAndWait(() -> ApplicationManager.getApplication().runWriteAction(() -> {
        try {
          VirtualFile target = project.getBaseDir().findChild("target");
          if (target == null) {
            target = project.getBaseDir().createChildDirectory(this, "target");
          }
          VirtualFile dir = target.createChildDirectory(this, "probe-tmp");
          dir.createChildData(this, "Generated.java");
          dir.delete(this);
        } catch (java.io.IOException e) {
          throw new RuntimeException(e);
        }
      }));
      Thread.sleep(1000);
      long t9 = System.nanoTime();
      ReadAction.compute(() -> StepUsageIndex.INSTANCE.get(project).getSteps().size());
      out.printf("[timing] after creating/deleting target/probe-tmp/Generated.java (attempt %d): %dms"
              + " | changed: files=%d definitions=%d roots=%d%n",
          attempt, (System.nanoTime() - t9) / 1_000_000,
          trackers.getFiles().getModificationCount() - files0,
          trackers.getDefinitions().getModificationCount() - defs0,
          com.intellij.openapi.roots.ProjectRootModificationTracker.getInstance(project).getModificationCount() - roots0);
    }
  }

  /**
   * Cold StepIndex builds (all plugin caches cleared), cycling through: every candidate read
   * (full scan), word-index pre-filter counting words in comments too, and the default pre-filter
   * (words outside comments). The definitions found must be identical to the full scan's.
   */
  private void scopeTiming(Project project) throws Exception {
    // Wait until imports/indexing have settled, so every round measures the same project state.
    com.intellij.openapi.util.ModificationTracker dumb = DumbService.getInstance(project).getModificationTracker();
    for (int i = 0; i < 24; i++) {
      long before = dumb.getModificationCount();
      DumbService.getInstance(project).waitForSmartMode();
      Thread.sleep(5000);
      if (dumb.getModificationCount() == before && !DumbService.getInstance(project).isDumb()) {
        break;
      }
    }
    java.util.Set<String> baseline = null;
    for (int round = 0; round < 9; round++) {
      boolean full = round % 3 == 0;
      boolean commentWords = round % 3 == 1;
      System.setProperty("cucumber.stepIndex.fullScan", Boolean.toString(full));
      System.setProperty("cucumber.stepIndex.commentWords", Boolean.toString(commentWords));
      DumbService.getInstance(project).waitForSmartMode();
      StepIndex.INSTANCE.clearCaches(project);
      CucumberModificationTrackers.Companion.getInstance(project).getDefinitions().incModificationCount();
      long t0 = System.nanoTime();
      java.util.Set<String> found = ReadAction.compute(() -> {
        java.util.Set<String> keys = new java.util.TreeSet<>();
        for (StepIndex.Definition d : StepIndex.INSTANCE.get(project).getDefinitions()) {
          keys.add(d.getFile().getVirtualFile().getPath() + ":" + d.getInfo().getOffset() + ":" + d.getInfo().getPattern());
        }
        return keys;
      });
      long t1 = System.nanoTime();
      int steps = ReadAction.compute(() -> StepUsageIndex.INSTANCE.get(project).getSteps().size());
      if (baseline == null) {
        baseline = found;
      } else if (!found.equals(baseline)) {
        java.util.Set<String> missing = new java.util.TreeSet<>(baseline);
        missing.removeAll(found);
        java.util.Set<String> extra = new java.util.TreeSet<>(found);
        extra.removeAll(baseline);
        out.println("[scope]   missing: " + missing + " extra: " + extra);
        for (String key : missing) {
          String path = key.substring(0, key.indexOf(':', 3));
          VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(path);
          if (vf != null) {
            out.println("[scope]   " + vf.getName() + " fileType=" + vf.getFileType().getName()
                + " indexer=" + com.intellij.psi.impl.cache.impl.id.IdTableBuilding.getFileTypeIndexer(vf.getFileType()));
            java.util.Map<com.intellij.psi.impl.cache.impl.id.IdIndexEntry, Integer> data = ReadAction.compute(() ->
                com.intellij.util.indexing.FileBasedIndex.getInstance().getFileData(
                    com.intellij.psi.impl.cache.impl.id.IdIndex.NAME, vf, project));
            StringBuilder sb = new StringBuilder("[scope]   idIndex entries=" + data.size());
            for (String w : new String[] {"Given", "When", "Then", "given", "when", "ready", "KotlinSteps", "En", "count", "init"}) {
              sb.append(' ').append(w).append('=').append(data.get(new com.intellij.psi.impl.cache.impl.id.IdIndexEntry(w, true)))
                  .append('/').append(data.get(new com.intellij.psi.impl.cache.impl.id.IdIndexEntry(w, false)));
            }
            out.println(sb);
            break;
          }
        }
      }
      String mode = full ? "full scan         " : commentWords ? "word index (any)  " : "word index (code) ";
      out.printf("[scope] cold %s: StepIndex=%dms definitions=%d steps=%d %s identicalToFullScan=%s%n",
          mode, (t1 - t0) / 1_000_000, found.size(), steps,
          StepIndex.INSTANCE.getLastScan(), found.equals(baseline));
      Thread.sleep(2000);
    }
    System.clearProperty("cucumber.stepIndex.fullScan");
    System.clearProperty("cucumber.stepIndex.commentWords");
  }

  private void probe(Project project, Path path) throws Exception {
    VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
    out.println();
    out.println("=== " + path.getFileName() + (vf == null ? " NOT FOUND" : ""));
    if (vf == null) {
      return;
    }
    PsiFile file = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(vf));
    Document document = ReadAction.compute(() -> FileDocumentManager.getInstance().getDocument(vf));
    out.println("language=" + ReadAction.compute(() -> file.getLanguage().getID())
        + " psi=" + file.getClass().getSimpleName()
        + " fileSeparator=" + ReadAction.compute(() -> String.valueOf(FileDocumentManager.getInstance().getLineSeparator(vf, project)).replace("\r", "\\r").replace("\n", "\\n"))
        + " inContent=" + ReadAction.compute(() -> ProjectFileIndex.getInstance(project).isInContent(vf))
        + " inSource=" + ReadAction.compute(() -> ProjectFileIndex.getInstance(project).isInSourceContent(vf)));

    // a) StepUsageIndex.
    List<StepIndex.Definition> definitions = ReadAction.compute(() -> StepUsageIndex.INSTANCE.get(project).definitionsIn(vf));
    out.println("[index] definitions=" + definitions.size());
    for (StepIndex.Definition definition : definitions) {
      ReadAction.run(() -> {
        int offset = definition.getInfo().getOffset();
        List<StepUsageIndex.Usage> usages = StepUsageIndex.INSTANCE.get(project).of(definition);
        out.println("  line " + (document.getLineNumber(offset) + 1) + " offset " + offset + " text@offset='"
            + snippet(document, offset) + "' usages=" + usages.size());
        for (StepUsageIndex.Usage usage : usages) {
          out.println("      -> " + usage.describe());
        }
      });
    }

    // b) Line markers.
    List<LineMarkerProvider> providers = ReadAction.compute(() -> LineMarkersPass.getMarkerProviders(file.getLanguage(), project));
    boolean registered = providers.stream().anyMatch(p -> p instanceof StepDefinitionLineMarkerProvider);
    out.println("[markers] our provider among " + providers.size() + " providers for language: " + registered);
    // The real daemon: all highlighting passes over the open file, then the gutter's line markers.
    ApplicationManager.getApplication().invokeAndWait(() ->
        FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vf, 0), true));
    try {
      DaemonCodeAnalyzerImpl daemon = (DaemonCodeAnalyzerImpl) DaemonCodeAnalyzer.getInstance(project);
      List<LineMarkerInfo<?>> queried = new ArrayList<>();
      DaemonProgressIndicator indicator = new DaemonProgressIndicator();
      ProgressManager.getInstance().runProcess(() -> ReadAction.run(() -> daemon.runInsideAdditionalHighlightingSession(
          file, EditorColorsManager.getInstance().getGlobalScheme(), ProperTextRange.create(file.getTextRange()), false,
          session -> queried.addAll(LineMarkersPass.queryLineMarkers(file, document)))), indicator);
      printMarkers("LineMarkersPass.queryLineMarkers (highlighting session)", document, queried);
    } catch (Throwable t) {
      out.println("[markers] queryLineMarkers threw " + t);
    }
    // The real daemon on the open editor: restart it and wait for the gutter's line markers.
    try {
      DaemonCodeAnalyzerImpl daemon = (DaemonCodeAnalyzerImpl) DaemonCodeAnalyzer.getInstance(project);
      ApplicationManager.getApplication().invokeAndWait(() -> daemon.restart(file, "probe"));
      List<LineMarkerInfo<?>> markers = List.of();
      for (int i = 0; i < 40; i++) {
        Thread.sleep(500);
        markers = ReadAction.compute(() -> DaemonCodeAnalyzerImpl.getLineMarkers(document, project));
        if (markers.stream().anyMatch(m -> isOurs(m)) && ReadAction.compute(() -> daemon.isAllAnalysisFinished(file))) {
          break;
        }
      }
      out.println("[markers] daemon finished=" + ReadAction.compute(() -> daemon.isAllAnalysisFinished(file)));
      printMarkers("real daemon getLineMarkers", document, markers);
    } catch (Throwable t) {
      out.println("[markers] daemon threw " + t);
    }
    List<LineMarkerInfo<?>> direct = new ArrayList<>();
    ReadAction.run(() -> {
      List<PsiElement> leaves = new ArrayList<>();
      for (PsiElement e : SyntaxTraverser.psiTraverser(file)) {
        if (e.getFirstChild() == null) {
          leaves.add(e);
        }
      }
      new StepDefinitionLineMarkerProvider().collectSlowLineMarkers(leaves, direct);
      int fast = 0;
      for (PsiElement leaf : leaves) {
        if (new StepDefinitionLineMarkerProvider().getLineMarkerInfo(leaf) != null) {
          fast++;
        }
      }
      out.println("[markers] fast path (index already built): " + fast + " markers");
    });
    printMarkers("direct collectSlowLineMarkers", document, direct);

    // c) Goto declaration, at a few offsets inside each pattern literal.
    AtomicReference<Editor> editorRef = new AtomicReference<>();
    ApplicationManager.getApplication().invokeAndWait(() -> {
      Editor editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vf, 0), false);
      if (editor == null) {
        editor = EditorFactory.getInstance().createEditor(document, project);
      }
      editorRef.set(editor);
    });
    Editor editor = editorRef.get();
    out.println("[goto] editor=" + editor.getClass().getSimpleName());
    for (StepIndex.Definition definition : definitions) {
      int start = definition.getInfo().getOffset();
      for (int delta : new int[] {0, 1, 5}) {
        int offset = start + delta;
        ReadAction.run(() -> {
          PsiElement leaf = file.findElementAt(offset);
          out.println("  line " + (document.getLineNumber(offset) + 1) + " +" + delta + " leaf="
              + (leaf == null ? null : leaf.getClass().getSimpleName() + leaf.getTextRange()));
          for (GotoDeclarationHandler handler : GotoDeclarationHandler.EP_NAME.getExtensionList()) {
            PsiElement[] targets;
            try {
              targets = handler.getGotoDeclarationTargets(leaf, offset, editor);
            } catch (Throwable t) {
              out.println("      handler " + handler.getClass().getName() + " threw " + t);
              continue;
            }
            if (targets != null && targets.length > 0) {
              out.println("      handler " + handler.getClass().getSimpleName() + " -> " + describe(targets));
            }
          }
        });
        if (delta != 1) {
          continue;
        }
        try {
          PsiElement[] all = ReadAction.compute(() -> GotoDeclarationAction.findAllTargetElements(project, editor, offset));
          out.println("      GotoDeclarationAction.findAllTargetElements -> " + describe(all));
        } catch (Throwable t) {
          out.println("      findAllTargetElements threw " + t);
        }
        try {
          GotoDeclarationOrUsageHandler2.GTDUOutcome outcome =
              ReadAction.compute(() -> GotoDeclarationOrUsageHandler2.testGTDUOutcome(editor, file, offset));
          CtrlMouseData data = ReadAction.compute(() -> GotoDeclarationOrUsageHandler2.getCtrlMouseData(editor, file, offset));
          String literal = ReadAction.compute(() -> {
            com.github.cucumber.jetbrains.plugin.core.Language lang =
                com.github.cucumber.jetbrains.plugin.core.Language.Companion.forExtension(vf.getExtension());
            com.github.cucumber.jetbrains.plugin.core.Literals.Literal lit = lang == null ? null
                : com.github.cucumber.jetbrains.plugin.core.StepRename.literalAt(lang, document.getText(), start);
            PsiElement leaf = file.findElementAt(offset);
            return (lit == null ? "null" : "(" + lit.getStart() + "," + lit.getEnd() + ")")
                + " leafParent=" + (leaf == null || leaf.getParent() == null ? null : leaf.getParent().getClass().getSimpleName());
          });
          String expected = literal.substring(0, literal.indexOf(' '));
          out.println("      GTDU outcome=" + outcome + " literal=" + literal
              + " rangeIsLiteral=" + (data != null && data.getRanges().toString().equals("[" + expected + "]"))
              + " ctrlMouse="
              + (data == null ? null : "ranges=" + data.getRanges() + " navigatable=" + data.isNavigatable() + " hint=" + strip(data.getHintText())));
        } catch (Throwable t) {
          out.println("      GTDU threw " + t);
        }
      }
      int usages = ReadAction.compute(() -> StepUsageIndex.INSTANCE.get(project).of(definition).size());
      if (usages >= 1) {
        invokeAction(project, file, vf, editor, start + 1);
      }
    }
  }

  /** Runs the real "GotoDeclaration" action (Ctrl+B / Ctrl+click) and reports where it navigated. */
  private void invokeAction(Project project, PsiFile file, VirtualFile vf, Editor editor, int offset) throws Exception {
    AnAction action = ActionManager.getInstance().getAction("GotoDeclaration");
    ApplicationManager.getApplication().invokeAndWait(() -> {
      FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vf, offset), true);
      editor.getCaretModel().moveToOffset(offset);
      DataContext context = SimpleDataContext.builder()
          .add(CommonDataKeys.PROJECT, project)
          .add(CommonDataKeys.EDITOR, editor)
          .add(CommonDataKeys.PSI_FILE, file)
          .add(CommonDataKeys.VIRTUAL_FILE, vf)
          .build();
      try {
        ActionUtil.invokeAction(action, context, ActionPlaces.EDITOR_POPUP, null, null);
      } catch (Throwable t) {
        out.println("      ACTION threw " + t);
      }
    });
    Thread.sleep(3000);
    AtomicReference<String> where = new AtomicReference<>();
    ApplicationManager.getApplication().invokeAndWait(() -> {
      Editor selected = FileEditorManager.getInstance(project).getSelectedTextEditor();
      if (selected == null) {
        where.set("no selected editor");
        return;
      }
      VirtualFile at = FileDocumentManager.getInstance().getFile(selected.getDocument());
      int caret = selected.getCaretModel().getOffset();
      int line = selected.getDocument().getLineNumber(caret);
      where.set((at == null ? "?" : at.getName()) + ":" + (line + 1) + "  '" + lineText(selected.getDocument(), line) + "'");
    });
    out.println("      ACTION GotoDeclaration at +1 -> caret now in " + where.get());
  }

  private static boolean isOurs(LineMarkerInfo<?> marker) {
    String tooltip = strip(marker.getLineMarkerTooltip());
    return tooltip != null && (tooltip.contains("feature step") || tooltip.contains("Unused step definition"));
  }

  private void printMarkers(String label, Document document, Collection<? extends LineMarkerInfo<?>> markers) {
    int ours = 0;
    java.util.Set<Integer> distinct = new java.util.HashSet<>();
    StringBuilder lines = new StringBuilder();
    for (LineMarkerInfo<?> marker : markers) {
      if (!isOurs(marker)) {
        continue;
      }
      distinct.add(marker.startOffset);
      String tooltip = strip(marker.getLineMarkerTooltip());
      ours++;
      int start = marker.startOffset;
      lines.append("      line ").append(document.getLineNumber(start) + 1).append(" range ")
          .append(start).append("-").append(marker.endOffset).append(" icon=").append(marker.getIcon())
          .append(" '").append(tooltip).append("'\n");
    }
    out.println("[markers] " + label + ": " + markers.size() + " total, ours=" + ours + " distinctOffsets=" + distinct.size());
    out.print(lines);
  }

  private static String describe(PsiElement[] targets) {
    if (targets == null) {
      return "null";
    }
    StringBuilder b = new StringBuilder("[" + targets.length + "]");
    for (PsiElement t : targets) {
      PsiFile f = t.getContainingFile();
      Document d = f == null ? null : PsiDocumentManager.getInstance(t.getProject()).getDocument(f);
      int o = t.getTextOffset();
      b.append(" ").append(t.getClass().getSimpleName()).append("@")
          .append(f == null ? "?" : f.getName()).append(":")
          .append(d == null ? "?" : String.valueOf(d.getLineNumber(o) + 1));
      if (d != null) {
        b.append(" '").append(lineText(d, d.getLineNumber(o))).append("'");
      }
    }
    return b.toString();
  }

  private static String lineText(Document d, int line) {
    return d.getText().substring(d.getLineStartOffset(line), d.getLineEndOffset(line)).trim();
  }

  private static String snippet(Document d, int offset) {
    String text = d.getText();
    if (offset > text.length()) {
      return "<past end>";
    }
    return text.substring(offset, Math.min(text.length(), offset + 25)).replace("\n", "\\n").replace("\r", "\\r");
  }

  private static String strip(String html) {
    return html == null ? null : html.replaceAll("<[^>]+>", "");
  }
}
