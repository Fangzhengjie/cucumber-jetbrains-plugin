package verify;

import com.github.cucumber.jetbrains.plugin.core.CucumberMessages;
import com.github.cucumber.jetbrains.plugin.core.RunResults;
import com.github.cucumber.jetbrains.plugin.ide.CucumberRunConfiguration;
import com.github.cucumber.jetbrains.plugin.ide.CucumberRunConfigurationProducer;
import com.github.cucumber.jetbrains.plugin.ide.CucumberRunLineMarkerContributor;
import com.github.cucumber.jetbrains.plugin.ide.CucumberRunResults;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl;
import com.intellij.execution.ExecutionListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.PsiLocation;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.TestStateStorage;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.ConfigurationFromContext;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.lineMarker.ExecutorAction;
import com.intellij.execution.lineMarker.RunLineMarkerContributor;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.testframework.AbstractTestProxy;
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter;
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener;
import com.intellij.execution.testframework.sm.runner.SMTestProxy;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.SyntaxTraverser;
import com.intellij.util.xmlb.XmlSerializer;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jdom.Element;

/**
 * Verification only: runs the Cucumber run configuration of a feature file (created by the
 * plugin's producer from the `Feature:` line, as the gutter does), waits for the process and the
 * test tree, then reports the gutter icons / tooltips of the feature's lines, the platform's
 * stored test states, invalidation after an edit and the persisted state round trip.
 */
final class ResultsProbe {

  private final PrintStream out;
  private final Project project;

  ResultsProbe(PrintStream out, Project project) {
    this.out = out;
    this.project = project;
  }

  void run(Path featurePath) throws Exception {
    VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(featurePath);
    out.println();
    out.println("=== results: " + featurePath + (vf == null ? " NOT FOUND" : ""));
    if (vf == null) {
      return;
    }
    PsiFile file = ReadAction.compute(() -> PsiManager.getInstance(project).findFile(vf));
    Document document = ReadAction.compute(() -> FileDocumentManager.getInstance().getDocument(vf));
    ApplicationManager.getApplication().invokeAndWait(() ->
        FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vf, 0), true));
    report("before the run", file, document, false);

    // The configuration the gutter's Run action uses: the producer, from the Feature: keyword.
    PsiElement featureKeyword = ReadAction.compute(() -> {
      for (PsiElement e : SyntaxTraverser.psiTraverser(file)) {
        if (e.getFirstChild() == null && e.getText().startsWith("Feature")) {
          return e;
        }
      }
      return null;
    });
    ConfigurationFromContext fromContext = ReadAction.compute(() -> new CucumberRunConfigurationProducer()
        .createConfigurationFromContext(ConfigurationContext.createEmptyContextForLocation(new PsiLocation<>(featureKeyword))));
    if (fromContext == null) {
      out.println("[run] producer created no configuration");
      return;
    }
    RunnerAndConfigurationSettings settings = fromContext.getConfigurationSettings();
    CucumberRunConfiguration configuration = (CucumberRunConfiguration) settings.getConfiguration();
    configuration.setExtraArgs("-o"); // offline Maven: everything is in the local repository
    RunManager.getInstance(project).addConfiguration(settings);
    out.println("[run] configuration '" + settings.getName() + "' feature=" + configuration.getFeaturePath()
        + " line=" + configuration.getLine());

    CountDownLatch terminated = new CountDownLatch(1);
    CountDownLatch testingFinished = new CountDownLatch(1);
    AtomicReference<SMTestProxy.SMRootTestProxy> root = new AtomicReference<>();
    StringBuilder output = new StringBuilder();
    project.getMessageBus().connect().subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
      @Override
      public void processStarted(String executorId, ExecutionEnvironment env, ProcessHandler handler) {
        out.println("[run] process started");
        handler.addProcessListener(new com.intellij.execution.process.ProcessListener() {
          @Override
          public void onTextAvailable(ProcessEvent event, com.intellij.openapi.util.Key outputType) {
            synchronized (output) {
              output.append(event.getText());
            }
          }
        });
      }

      @Override
      public void processTerminated(String executorId, ExecutionEnvironment env, ProcessHandler handler, int exitCode) {
        out.println("[run] process terminated, exit code " + exitCode);
        terminated.countDown();
      }
    });
    project.getMessageBus().connect().subscribe(SMTRunnerEventsListener.TEST_STATUS, new SMTRunnerEventsAdapter() {
      @Override
      public void onTestingFinished(SMTestProxy.SMRootTestProxy testsRoot) {
        root.set(testsRoot);
        testingFinished.countDown();
      }
    });
    long started = System.currentTimeMillis();
    ApplicationManager.getApplication().invokeAndWait(() ->
        ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance()));
    boolean ended = terminated.await(10, TimeUnit.MINUTES);
    boolean finished = testingFinished.await(60, TimeUnit.SECONDS);
    out.println("[run] terminated=" + ended + " testingFinished=" + finished + " after "
        + (System.currentTimeMillis() - started) / 1000 + "s");
    if (!finished) {
      synchronized (output) {
        String text = output.toString();
        out.println("[run] output tail:\n" + text.substring(Math.max(0, text.length() - 4000)));
      }
    }
    SMTestProxy.SMRootTestProxy testsRoot = root.get();
    if (testsRoot != null) {
      out.println("[tree] " + testsRoot.getAllTests().size() + " nodes");
      for (AbstractTestProxy test : testsRoot.getAllTests()) {
        if (test.getParent() != null && test.getParent().getParent() != null && !test.isLeaf()) {
          SMTestProxy proxy = (SMTestProxy) test;
          out.println("      " + proxy.getName() + " magnitude=" + proxy.getMagnitudeInfo() + " url=" + proxy.getLocationUrl());
        } else if (test.getParent() != null && test.getParent().getParent() == null) {
          out.println("      " + test.getName() + " magnitude=" + ((SMTestProxy) test).getMagnitudeInfo() + " url=" + test.getLocationUrl());
        }
      }
    }
    // The platform writes the test states in a background task; ours refreshes the gutter later.
    Thread.sleep(5000);

    TreeMap<Integer, String> stored = new TreeMap<>();
    TestStateStorage storage = TestStateStorage.getInstance(project);
    for (int line = 1; line <= document.getLineCount(); line++) {
      TestStateStorage.Record record = storage.getState(CucumberMessages.INSTANCE.location(vf.getPath(), line));
      if (record != null) {
        stored.put(line, "magnitude=" + record.magnitude + " errorMessage=" + oneLine(record.errorMessage));
      }
    }
    out.println("[storage] TestStateStorage records for " + vf.getName() + " (url cucumber://" + vf.getPath() + ":<line>): " + stored.size());
    stored.forEach((line, text) -> out.println("      line " + line + " " + text));

    report("after the run", file, document, true);

    // Editing the failing step line drops its result (the run icon of its scenario stays).
    int failingStep = findLastLine(document, "Then I have 3 cukes left");
    if (failingStep > 0) {
      WriteCommandAction.runWriteCommandAction(project, () -> {
        int end = document.getLineEndOffset(failingStep - 1);
        document.insertString(end, " now");
        PsiDocumentManager.getInstance(project).commitDocument(document);
      });
      out.println("[edit] appended ' now' to line " + failingStep);
      report("after editing line " + failingStep, file, document, false);
      WriteCommandAction.runWriteCommandAction(project, () -> {
        int end = document.getLineEndOffset(failingStep - 1);
        document.deleteString(end - 4, end);
        PsiDocumentManager.getInstance(project).commitDocument(document);
      });
      out.println("[edit] undid the edit");
      report("after undoing the edit", file, document, false);
    }

    // Persistence: serialize the service state and load it into a fresh instance (as on restart).
    CucumberRunResults.Saved saved = CucumberRunResults.Companion.getInstance(project).getState();
    Element element = XmlSerializer.serialize(saved);
    CucumberRunResults fresh = new CucumberRunResults(project);
    fresh.loadState(XmlSerializer.deserialize(element, CucumberRunResults.Saved.class));
    out.println("[persist] " + saved.getEntries().size() + " entries, XML " + com.intellij.openapi.util.JDOMUtil.write(element).length() + " chars");
    ReadAction.run(() -> {
      for (int line = 1; line <= document.getLineCount(); line++) {
        RunResults.LineResult result = fresh.resultAt(vf, line, CucumberRunLineMarkerContributor.Companion.lineText(document, line));
        if (result != null) {
          out.println("      reloaded line " + line + " " + result.getKind() + " " + result.describe());
        }
      }
    });
  }

  /** The gutter of the open editor (real daemon) plus the contributor's info per line. */
  private void report(String label, PsiFile file, Document document, boolean contributorDetails) throws Exception {
    DaemonCodeAnalyzerImpl daemon = (DaemonCodeAnalyzerImpl) DaemonCodeAnalyzer.getInstance(project);
    ApplicationManager.getApplication().invokeAndWait(() -> daemon.restart(file, "results probe"));
    List<LineMarkerInfo<?>> markers = List.of();
    for (int i = 0; i < 60; i++) {
      Thread.sleep(500);
      markers = ReadAction.compute(() -> DaemonCodeAnalyzerImpl.getLineMarkers(document, project));
      if (i > 2 && ReadAction.compute(() -> daemon.isAllAnalysisFinished(file))) {
        break;
      }
    }
    out.println("[gutter] " + label + ": " + markers.size() + " line markers (daemon finished="
        + ReadAction.compute(() -> daemon.isAllAnalysisFinished(file)) + ")");
    List<String> lines = new ArrayList<>();
    for (LineMarkerInfo<?> marker : markers) {
      String tooltip = ReadAction.compute(() -> marker.getLineMarkerTooltip());
      if (tooltip != null && tooltip.startsWith("Go to step definition")) {
        continue; // the navigation markers of defined steps
      }
      int line = document.getLineNumber(marker.startOffset) + 1;
      lines.add(String.format("      line %2d %-58s icon=%s tooltip='%s'", line,
          "'" + lineTextAt(document, line) + "'", iconName(marker.getIcon()), oneLine(tooltip)));
    }
    lines.sort(null);
    lines.forEach(out::println);
    if (!contributorDetails) {
      return;
    }
    CucumberRunLineMarkerContributor contributor = new CucumberRunLineMarkerContributor();
    ReadAction.run(() -> {
      for (PsiElement e : SyntaxTraverser.psiTraverser(file)) {
        if (e.getFirstChild() != null) {
          continue;
        }
        RunLineMarkerContributor.Info info = contributor.getInfo(e);
        if (info == null) {
          continue;
        }
        StringBuilder executors = new StringBuilder();
        for (AnAction action : info.actions) {
          executors.append(action instanceof ExecutorAction ? ((ExecutorAction) action).getExecutor().getId() : action.getClass().getSimpleName()).append(' ');
        }
        int line = document.getLineNumber(e.getTextRange().getStartOffset()) + 1;
        out.println("      getInfo line " + line + " icon=" + iconName(info.icon) + " actions=[" + executors.toString().trim()
            + "] tooltip='" + oneLine(info.tooltipProvider == null ? null : info.tooltipProvider.apply(e)) + "'");
      }
    });
  }

  private static int findLastLine(Document document, String text) {
    for (int line = document.getLineCount(); line >= 1; line--) {
      if (lineTextAt(document, line).equals(text)) {
        return line;
      }
    }
    return -1;
  }

  private static String lineTextAt(Document document, int line) {
    return document.getText().substring(document.getLineStartOffset(line - 1), document.getLineEndOffset(line - 1)).trim();
  }

  private static String iconName(javax.swing.Icon icon) {
    if (icon == null) {
      return "null";
    }
    String text = icon.toString();
    int slash = text.lastIndexOf('/');
    int end = text.indexOf(".svg", Math.max(slash, 0));
    return slash >= 0 && end > slash ? text.substring(slash + 1, end) : text;
  }

  private static String oneLine(String text) {
    return text == null ? null : text.replace("\n", " \\n ");
  }
}
