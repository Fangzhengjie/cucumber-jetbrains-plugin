package verify;

import io.github.fangzhengjie.cucumber.feature.FeatureStep;
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.codeInsight.lookup.LookupEx;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.codeInsight.lookup.impl.LookupImpl;
import com.intellij.codeInsight.template.impl.TemplateManagerImpl;
import com.intellij.codeInsight.template.impl.TemplateState;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorModificationUtil;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * feature: completion. Step completion in a real editor: types a step on a new line at the end of
 * the file's last scenario, invokes basic completion (like Ctrl+Space), prints the lookup, selects
 * a definition item and reports the line, caret and live template state, types a value and Tabs
 * through the template. Verification only (never shipped).
 */
final class CompletionProbe {

  private final PrintStream out;
  private final Project project;

  CompletionProbe(PrintStream out, Project project) {
    this.out = out;
    this.project = project;
  }

  void run(Path path) throws Exception {
    VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
    out.println();
    out.println("=== completion in " + path.getFileName() + (vf == null ? " NOT FOUND" : ""));
    if (vf == null) {
      return;
    }
    Document document = ReadAction.compute(() -> FileDocumentManager.getInstance().getDocument(vf));
    AtomicReference<Editor> editorRef = new AtomicReference<>();
    ApplicationManager.getApplication().invokeAndWait(() -> editorRef.set(
        FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vf, 0), true)));
    Editor editor = editorRef.get();
    if (editor == null || document == null) {
      out.println("[completion] no editor");
      return;
    }
    if (document.getText().contains("# language: zh-CN")) {
      step(editor, document, "\u5047\u5982the java ", "the java client sends", "5");
      return;
    }
    step(editor, document, "Given the java ", "the java client sends", "5");
    step(editor, document, "Given the java user", "the java user", "alice");
    step(editor, document, "Then java tot", "the java total is", "9");
    keywords(editor, document);
  }

  /** Types `typed` on a new line after the last non-blank line and invokes completion. */
  private LookupImpl complete(Editor editor, Document document, String typed) throws Exception {
    ApplicationManager.getApplication().invokeAndWait(() ->
        WriteCommandAction.runWriteCommandAction(project, () -> {
          String text = document.getText();
          int end = text.length();
          while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
          }
          int line = document.getLineNumber(end);
          int lineStart = document.getLineStartOffset(line);
          int indentEnd = lineStart;
          while (indentEnd < end && (text.charAt(indentEnd) == ' ' || text.charAt(indentEnd) == '\t')) {
            indentEnd++;
          }
          String insert = "\n" + text.substring(lineStart, indentEnd) + typed;
          document.insertString(end, insert);
          editor.getCaretModel().moveToOffset(end + insert.length());
          PsiDocumentManager.getInstance(project).commitDocument(document);
        }));
    ApplicationManager.getApplication().invokeAndWait(() ->
        CommandProcessor.getInstance().executeCommand(project, () ->
            new CodeCompletionHandlerBase(CompletionType.BASIC, true, false, true).invokeCompletion(project, editor),
            "probe completion", null));
    for (int i = 0; i < 40; i++) {
      AtomicReference<LookupImpl> found = new AtomicReference<>();
      ApplicationManager.getApplication().invokeAndWait(() -> {
        LookupEx active = LookupManager.getActiveLookup(editor);
        if (active instanceof LookupImpl impl && !impl.isCalculating()) {
          found.set(impl);
        }
      });
      if (found.get() != null) {
        return found.get();
      }
      Thread.sleep(250);
    }
    return null;
  }

  private LookupElement printItems(LookupImpl lookup, String choose, int max) throws Exception {
    AtomicReference<LookupElement> chosen = new AtomicReference<>();
    AtomicReference<LookupElement> fallback = new AtomicReference<>();
    ApplicationManager.getApplication().invokeAndWait(() -> {
      List<LookupElement> items = lookup.getItems();
      out.println("[completion] " + items.size() + " items, prefix '" + (items.isEmpty() ? "" : lookup.itemPattern(items.get(0))) + "'");
      int n = 0;
      for (LookupElement item : items) {
        LookupElementPresentation p = LookupElementPresentation.renderElement(item);
        if (n++ < max) {
          out.println("    " + n + ". '" + p.getItemText() + "'" + (p.getTailText() == null ? "" : p.getTailText())
              + "  [" + p.getTypeText() + "]" + (p.isItemTextBold() ? " bold" : "") + (p.getIcon() != null ? " icon" : "")
              + "  -> '" + item.getLookupString() + "'");
        }
        if (choose != null && chosen.get() == null && item.getLookupString().startsWith(choose)
            && p.getItemText() != null && p.getItemText().contains("<")) {
          chosen.set(item);
        }
        if (choose != null && fallback.get() == null && item.getLookupString().startsWith(choose)) {
          fallback.set(item);
        }
      }
    });
    return chosen.get() != null ? chosen.get() : fallback.get();
  }

  private void step(Editor editor, Document document, String typed, String choose, String value) throws Exception {
    out.println("--- typed '" + typed + "', choosing '" + choose + "...'");
    LookupImpl lookup = complete(editor, document, typed);
    if (lookup == null) {
      out.println("[completion] no lookup (a single item is inserted directly); line '" + line(editor) + "'");
      return;
    }
    LookupElement chosen = printItems(lookup, choose, 10);
    if (chosen == null) {
      out.println("[completion] no item starting with '" + choose + "'");
      ApplicationManager.getApplication().invokeAndWait(() -> lookup.hideLookup(true));
      return;
    }
    LookupElement item = chosen;
    ApplicationManager.getApplication().invokeAndWait(() -> {
      lookup.setCurrentItem(item);
      lookup.finishLookup(Lookup.NORMAL_SELECT_CHAR);
    });
    Thread.sleep(300);
    state(editor, "selected");
    ApplicationManager.getApplication().invokeAndWait(() ->
        WriteCommandAction.runWriteCommandAction(project, (Runnable) () ->
            EditorModificationUtil.insertStringAtCaret(editor, value, false, true)));
    state(editor, "typed '" + value + "'");
    for (int tab = 1; tab <= 4; tab++) {
      boolean[] active = new boolean[1];
      ApplicationManager.getApplication().invokeAndWait(() -> {
        TemplateState state = TemplateManagerImpl.getTemplateState(editor);
        active[0] = state != null && !state.isFinished();
        if (active[0]) {
          CommandProcessor.getInstance().executeCommand(project, state::nextTab, "tab", null);
        }
      });
      if (!active[0]) {
        break;
      }
      state(editor, "Tab " + tab);
    }
    ApplicationManager.getApplication().invokeAndWait(() -> {
      PsiDocumentManager.getInstance(project).commitDocument(document);
      PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(document);
      int lineEnd = document.getLineEndOffset(document.getLineNumber(editor.getCaretModel().getOffset()));
      FeatureStep step = file == null ? null : PsiTreeUtil.getParentOfType(file.findElementAt(lineEnd - 1), FeatureStep.class);
      int resolved = 0;
      if (step != null) {
        for (PsiReference reference : step.getReferences()) {
          if (reference instanceof PsiPolyVariantReference poly) {
            resolved += poly.multiResolve(false).length;
          }
        }
      }
      out.println("[completion] final line '" + line(editor) + "' -> step '" + (step == null ? null : step.stepText())
          + "' resolves to " + resolved + " definition(s)");
    });
  }

  /** Keyword and tag completion still work next to step completion. */
  private void keywords(Editor editor, Document document) throws Exception {
    out.println("--- typed 'Whe' (keyword completion)");
    LookupImpl lookup = complete(editor, document, "Whe");
    if (lookup == null) {
      out.println("[completion] no lookup (a single item is inserted directly); line '" + line(editor) + "'");
    } else {
      printItems(lookup, null, 5);
      ApplicationManager.getApplication().invokeAndWait(() -> lookup.hideLookup(true));
    }
    ApplicationManager.getApplication().invokeAndWait(() ->
        WriteCommandAction.runWriteCommandAction(project, () -> {
          int line = document.getLineNumber(editor.getCaretModel().getOffset());
          document.deleteString(document.getLineStartOffset(line) - 1, document.getLineEndOffset(line));
          int feature = document.getText().indexOf("Feature:");
          document.insertString(feature, "@probe-tag\n");
          PsiDocumentManager.getInstance(project).commitDocument(document);
        }));
    out.println("--- typed '@pro' (tag completion)");
    LookupImpl tags = complete(editor, document, "@pro");
    if (tags == null) {
      out.println("[completion] no lookup (a single item is inserted directly); line '" + line(editor) + "'");
    } else {
      printItems(tags, null, 5);
      ApplicationManager.getApplication().invokeAndWait(() -> tags.hideLookup(true));
    }
  }

  private void state(Editor editor, String label) throws Exception {
    ApplicationManager.getApplication().invokeAndWait(() -> {
      TemplateState state = TemplateManagerImpl.getTemplateState(editor);
      Document d = editor.getDocument();
      int caret = editor.getCaretModel().getOffset();
      int lineStart = d.getLineStartOffset(d.getLineNumber(caret));
      String line = line(editor);
      String marked = line.substring(0, caret - lineStart) + "|" + line.substring(caret - lineStart);
      String template;
      if (state == null || state.isFinished()) {
        template = "no active template";
      } else {
        TextRange range = state.getCurrentVariableRange();
        template = "template active, variable " + (state.getCurrentVariableNumber() + 1) + "/"
            + state.getTemplate().getVariableCount() + " range="
            + (range == null ? null : "'" + d.getText(range) + "'@" + (range.getStartOffset() - lineStart));
      }
      String selected = editor.getSelectionModel().getSelectedText();
      out.println("[completion] " + label + ": '" + marked + "' caret column " + (caret - lineStart)
          + (selected == null ? "" : " selection='" + selected + "'") + ", " + template);
    });
  }

  private static String line(Editor editor) {
    return ReadAction.compute(() -> {
      Document d = editor.getDocument();
      int line = d.getLineNumber(editor.getCaretModel().getOffset());
      return d.getText(new TextRange(d.getLineStartOffset(line), d.getLineEndOffset(line)));
    });
  }
}
