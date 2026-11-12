package verify;

import io.github.fangzhengjie.cucumber.ide.CucumberRunConfiguration;
import io.github.fangzhengjie.cucumber.ide.CucumberRunConfigurationType;
import io.github.fangzhengjie.cucumber.ide.CucumberToolWindowFactory;
import com.intellij.execution.ExecutionListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter;
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener;
import com.intellij.execution.testframework.sm.runner.SMTestProxy;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.ide.DataManager;
import com.intellij.notification.Notification;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TestDialog;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl;
import com.intellij.ui.treeStructure.Tree;
import java.awt.Component;
import java.awt.Container;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

/**
 * Verification only: creates the Cucumber tool window content, waits for its feature scan, reports
 * the toolbar actions' state (as the toolbar shows them and after a fresh update), selects a
 * scenario (or nothing, for Run All), clicks the run action and reports what was started.
 *
 * <p>Modes: {@code toolwindow:<feature>[@<scenario name>]} runs the selected scenario and waits for
 * the test tree ({@code toolwindow:} with nothing selected must show a message); {@code
 * toolwindow-all:} clicks Run All Features; {@code toolwindow-norun:} only reports the actions.
 */
final class ToolWindowProbe {

  private final PrintStream out;
  private final Project project;
  private static ToolWindow toolWindow;

  ToolWindowProbe(PrintStream out, Project project) {
    this.out = out;
    this.project = project;
  }

  void run(String mode, Path projectDir, String argument) throws Exception {
    out.println();
    out.println("=== " + mode + ": " + argument);
    // Messages in a headless IDE go to the test dialog: record them instead of failing.
    TestDialogManager.setTestDialog(message -> {
      out.println("[dialog] " + message.replace("\n", " \\n "));
      return 0;
    });
    project.getMessageBus().connect().subscribe(Notifications.TOPIC, new Notifications() {
      @Override
      public void notify(Notification notification) {
        out.println("[notification] " + notification.getGroupId() + " " + notification.getType() + ": "
            + notification.getTitle() + " | " + notification.getContent().replace("\n", " \\n "));
      }
    });
    // Offline Maven for the e2e project (everything is in the local repository).
    CucumberRunConfiguration template = (CucumberRunConfiguration) RunManager.getInstance(project)
        .getConfigurationTemplate(CucumberRunConfigurationType.Companion.instance().factory()).getConfiguration();
    template.setExtraArgs("-o");

    JComponent content = createContent();
    Object panel = panel();
    out.println("[toolwindow] content=" + (content == null ? null : content.getClass().getSimpleName())
        + " panel=" + (panel == null ? null : panel.getClass().getSimpleName()));
    if (panel == null) {
      return;
    }
    ActionToolbar toolbar = find(content, ActionToolbar.class);
    Tree tree = find(content, Tree.class);
    if (toolbar == null || tree == null) {
      out.println("[toolbar] toolbar=" + toolbar + " tree=" + tree);
      return;
    }
    // A shown tool window updates its toolbar right away (addNotify), before the scan is done.
    ApplicationManager.getApplication().invokeAndWait(toolbar::updateActionsImmediately);
    out.println("[toolwindow] features=" + featureCount(panel) + " at the first toolbar update");
    reportButtons("first update (window shown)", toolbar);
    // Wait for the scan (the panel's refresh) to fill the features.
    long started = System.currentTimeMillis();
    int count = -1;
    for (int i = 0; i < 120; i++) {
      count = featureCount(panel);
      if (count > 0) {
        break;
      }
      Thread.sleep(500);
    }
    out.println("[toolwindow] features=" + count + " after " + (System.currentTimeMillis() - started) + "ms");
    Thread.sleep(1500);

    AtomicReference<Integer> rows = new AtomicReference<>();
    ApplicationManager.getApplication().invokeAndWait(() -> rows.set(tree.getRowCount()));
    out.println("[toolbar] " + toolbar.getPlace() + " tree rows=" + rows.get());
    reportButtons("after the scan, without a forced update", toolbar);
    ApplicationManager.getApplication().invokeAndWait(toolbar::updateActionsImmediately);
    Thread.sleep(1000);
    reportButtons("after updateActionsImmediately", toolbar);
    reportUpdated(toolbar, tree);
    if (mode.equals("toolwindow-norun")) {
      return;
    }

    AnAction action;
    if (mode.equals("toolwindow-all")) {
      action = action(toolbar, "Run All Features");
      ApplicationManager.getApplication().invokeAndWait(tree::clearSelection);
    } else {
      action = action(toolbar, "Run Selected");
      if (argument.isEmpty()) {
        // Nothing selected and no editor: the click must say so.
        ApplicationManager.getApplication().invokeAndWait(tree::clearSelection);
        out.println("[select] nothing");
      } else {
        String[] parts = argument.split("@", 2);
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectDir.resolve(parts[0]));
        String scenario = parts.length > 1 ? parts[1] : null;
        AtomicReference<String> selected = new AtomicReference<>();
        ApplicationManager.getApplication().invokeAndWait(() -> selected.set(select(tree, vf, scenario)));
        out.println("[select] " + selected.get());
      }
    }
    if (action == null) {
      out.println("[click] action NOT FOUND");
      return;
    }

    CountDownLatch processStarted = new CountDownLatch(1);
    CountDownLatch terminated = new CountDownLatch(1);
    CountDownLatch testingFinished = new CountDownLatch(1);
    AtomicReference<ProcessHandler> handlerRef = new AtomicReference<>();
    AtomicReference<SMTestProxy.SMRootTestProxy> root = new AtomicReference<>();
    var connection = project.getMessageBus().connect();
    connection.subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
      @Override
      public void processStartScheduled(String executorId, ExecutionEnvironment env) {
        out.println("[run] start scheduled: " + env.getRunProfile().getName() + " executor=" + executorId
            + " runner=" + env.getRunner().getRunnerId());
      }

      @Override
      public void processNotStarted(String executorId, ExecutionEnvironment env, Throwable cause) {
        out.println("[run] process NOT started: " + env.getRunProfile().getName() + " cause=" + cause);
        terminated.countDown();
      }

      @Override
      public void processStarted(String executorId, ExecutionEnvironment env, ProcessHandler handler) {
        out.println("[run] process started: " + env.getRunProfile().getName());
        handlerRef.set(handler);
        processStarted.countDown();
      }

      @Override
      public void processTerminated(String executorId, ExecutionEnvironment env, ProcessHandler handler, int exitCode) {
        out.println("[run] process terminated, exit code " + exitCode);
        terminated.countDown();
      }
    });
    connection.subscribe(SMTRunnerEventsListener.TEST_STATUS, new SMTRunnerEventsAdapter() {
      @Override
      public void onTestingFinished(SMTestProxy.SMRootTestProxy testsRoot) {
        root.set(testsRoot);
        testingFinished.countDown();
      }
    });

    AtomicReference<String> clicked = new AtomicReference<>();
    ApplicationManager.getApplication().invokeAndWait(() -> {
      try {
        AnActionEvent event = event(action, tree);
        ActionUtil.updateAction(action, event);
        clicked.set("enabled=" + event.getPresentation().isEnabled());
        if (event.getPresentation().isEnabled()) {
          ActionUtil.performActionDumbAwareWithCallbacks(action, event);
        }
      } catch (Throwable t) {
        clicked.set("threw " + t);
      }
    });
    out.println("[click] " + action.getTemplatePresentation().getText() + " " + clicked.get());
    RunnerAndConfigurationSettings selected = RunManager.getInstance(project).getSelectedConfiguration();
    out.println("[run] selected configuration: " + (selected == null ? null : selected.getName()
        + (selected.getConfiguration() instanceof CucumberRunConfiguration c
            ? " feature=" + c.getFeaturePath() + " line=" + c.getLine() + " language=" + c.getLanguage() : "")));

    boolean started2 = processStarted.await(mode.equals("toolwindow-all") ? 120 : 60, TimeUnit.SECONDS);
    out.println("[run] process started=" + started2);
    List<RunContentDescriptor> descriptors = new ArrayList<>();
    ApplicationManager.getApplication().invokeAndWait(() ->
        descriptors.addAll(RunContentManager.getInstance(project).getAllDescriptors()));
    for (RunContentDescriptor descriptor : descriptors) {
      out.println("[run] descriptor: " + descriptor.getDisplayName());
    }
    if (!started2) {
      return;
    }
    if (mode.equals("toolwindow-all")) {
      ProcessHandler handler = handlerRef.get();
      boolean ended = terminated.await(10, TimeUnit.MINUTES);
      out.println("[run] terminated=" + ended + (handler == null ? "" : " exitCode=" + handler.getExitCode()));
    } else {
      boolean ended = terminated.await(10, TimeUnit.MINUTES);
      out.println("[run] terminated=" + ended);
    }
    boolean finished = testingFinished.await(60, TimeUnit.SECONDS);
    out.println("[tree] testingFinished=" + finished);
    SMTestProxy.SMRootTestProxy testsRoot = root.get();
    if (testsRoot != null) {
      for (var test : testsRoot.getAllTests()) {
        SMTestProxy proxy = (SMTestProxy) test;
        int depth = 0;
        for (var p = proxy.getParent(); p != null; p = p.getParent()) {
          depth++;
        }
        out.println("      " + "  ".repeat(depth) + proxy.getName() + " " + proxy.getMagnitudeInfo());
      }
    }
    connection.disconnect();
  }

  /** The tool window content, created as the IDE does when the window is first shown. */
  private JComponent createContent() {
    AtomicReference<JComponent> result = new AtomicReference<>();
    ApplicationManager.getApplication().invokeAndWait(() -> {
      if (toolWindow == null) {
        ToolWindowManager manager = ToolWindowManager.getInstance(project);
        ToolWindow window = manager.getToolWindow(CucumberToolWindowFactory.TOOL_WINDOW_ID);
        out.println("[toolwindow] manager=" + manager.getClass().getSimpleName() + " window="
            + (window == null ? null : window.getClass().getSimpleName()));
        if (window == null || window instanceof ToolWindowHeadlessManagerImpl.MockToolWindow) {
          // The headless manager registers no factories: create the content like the real one.
          window = new ToolWindowHeadlessManagerImpl.MockToolWindow(project);
          Disposer.register(project, window.getDisposable());
          new CucumberToolWindowFactory().createToolWindowContent(project, window);
        } else {
          window.show();
        }
        toolWindow = window;
      }
      var contents = toolWindow.getContentManager().getContents();
      out.println("[toolwindow] contents=" + contents.length);
      if (contents.length > 0) {
        result.set(contents[0].getComponent());
      }
    });
    return result.get();
  }

  private Object panel() {
    Key<?> key = Key.findKeyByName("cucumber.jetbrains.plugin.panel");
    return key == null ? null : project.getUserData(key);
  }

  private static int featureCount(Object panel) throws Exception {
    Field field = panel.getClass().getDeclaredField("features");
    field.setAccessible(true);
    return ((Collection<?>) field.get(panel)).size();
  }

  private void reportButtons(String label, ActionToolbar toolbar) throws Exception {
    List<String> lines = new ArrayList<>();
    ApplicationManager.getApplication().invokeAndWait(() -> {
      for (ActionButton button : findAll(toolbar.getComponent(), ActionButton.class)) {
        Presentation p = button.getPresentation();
        lines.add(String.format("      button %-22s enabled=%s visible=%s", "'" + p.getText() + "'", p.isEnabled(), p.isVisible()));
      }
    });
    out.println("[toolbar] " + label + ": " + lines.size() + " buttons");
    lines.forEach(out::println);
  }

  /** Every toolbar action updated now, with the toolbar's data context. */
  private void reportUpdated(ActionToolbar toolbar, Tree tree) throws Exception {
    List<String> lines = new ArrayList<>();
    ApplicationManager.getApplication().invokeAndWait(() -> {
      for (AnAction action : ((DefaultActionGroup) toolbar.getActionGroup()).getChildActionsOrStubs()) {
        if (action instanceof Separator) {
          continue;
        }
        AnActionEvent event = event(action, tree);
        ActionUtil.updateAction(action, event);
        lines.add(String.format("      action %-22s enabled=%s visible=%s", "'" + event.getPresentation().getText() + "'",
            event.getPresentation().isEnabled(), event.getPresentation().isVisible()));
      }
    });
    out.println("[toolbar] actions updated now:");
    lines.forEach(out::println);
  }

  private static AnActionEvent event(AnAction action, Tree tree) {
    DataContext context = DataManager.getInstance().getDataContext(tree);
    return AnActionEvent.createEvent(action, context, action.getTemplatePresentation().clone(),
        "CucumberToolWindow", ActionUiKind.TOOLBAR, null);
  }

  private static AnAction action(ActionToolbar toolbar, String text) {
    for (AnAction action : ((DefaultActionGroup) toolbar.getActionGroup()).getChildActionsOrStubs()) {
      if (text.equals(action.getTemplatePresentation().getText())) {
        return action;
      }
    }
    return null;
  }

  /** Selects the feature's scenario named [scenario] (or its first one); returns what was selected. */
  private static String select(Tree tree, VirtualFile file, String scenario) {
    DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
    for (int i = 0; i < root.getChildCount(); i++) {
      DefaultMutableTreeNode featureNode = (DefaultMutableTreeNode) root.getChildAt(i);
      if (!file.equals(property(featureNode.getUserObject(), "getFile"))) {
        continue;
      }
      for (int j = 0; j < featureNode.getChildCount(); j++) {
        DefaultMutableTreeNode testNode = (DefaultMutableTreeNode) featureNode.getChildAt(j);
        Object name = property(testNode.getUserObject(), "getName");
        if (scenario == null || scenario.equals(name)) {
          TreePath path = new TreePath(testNode.getPath());
          tree.setSelectionPath(path);
          return "scenario '" + name + "' line " + property(testNode.getUserObject(), "getLine") + " selected="
              + path.equals(tree.getSelectionPath());
        }
      }
    }
    return "nothing (" + file + " / " + scenario + " not in the tree)";
  }

  private static Object property(Object value, String getter) {
    try {
      Method method = value.getClass().getMethod(getter);
      method.setAccessible(true);
      return method.invoke(value);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  private static <T> T find(Component component, Class<T> type) {
    List<T> all = findAll(component, type);
    return all.isEmpty() ? null : all.get(0);
  }

  private static <T> List<T> findAll(Component component, Class<T> type) {
    List<T> result = new ArrayList<>();
    collect(component, type, result);
    return result;
  }

  private static <T> void collect(Component component, Class<T> type, List<T> result) {
    if (component == null) {
      return;
    }
    if (type.isInstance(component)) {
      result.add(type.cast(component));
    }
    if (component instanceof JComponent jc) {
      Object toolbar = jc.getClientProperty(ActionToolbar.ACTION_TOOLBAR_PROPERTY_KEY);
      if (type.isInstance(toolbar) && !result.contains(type.cast(toolbar))) {
        result.add(type.cast(toolbar));
      }
    }
    if (component instanceof Container container) {
      for (Component child : container.getComponents()) {
        collect(child, type, result);
      }
    }
  }
}
