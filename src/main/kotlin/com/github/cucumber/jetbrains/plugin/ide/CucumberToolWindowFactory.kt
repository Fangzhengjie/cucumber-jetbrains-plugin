package com.github.cucumber.jetbrains.plugin.ide

import com.github.cucumber.jetbrains.plugin.CucumberIcons
import com.github.cucumber.jetbrains.plugin.core.Language
import com.github.cucumber.jetbrains.plugin.core.FeatureOutline
import com.github.cucumber.jetbrains.plugin.core.GherkinKeywordType
import com.github.cucumber.jetbrains.plugin.core.GherkinSyntax
import com.github.cucumber.jetbrains.plugin.core.Outlines
import com.github.cucumber.jetbrains.plugin.core.TagExpressions
import com.intellij.openapi.ui.Messages
import com.intellij.execution.ExecutionException
import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.EnumMap
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.Timer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** Right-hand "Cucumber" tool window: browse, filter, navigate and run features. */
class CucumberToolWindowFactory : ToolWindowFactory, DumbAware {

  internal data class StepEntry(
      val file: VirtualFile,
      val line: Int,
      val keyword: String,
      val text: String,
      val defined: Boolean,
      /** The text matched against definitions (Outline parameters substituted). */
      val matchText: String = text,
      /** `given` / `when` / `then`, with `And` / `But` resolved. */
      val effectiveKeyword: String? = null,
  )

  internal data class TestEntry(
      val file: VirtualFile,
      val line: Int,
      val name: String,
      val tags: List<String>,
      val steps: List<StepEntry>,
  )

  internal data class FeatureEntry(
      val file: VirtualFile,
      val line: Int,
      val title: String,
      val scenarios: List<TestEntry>,
      val language: Language?,
      val undefined: Int,
  )

  private data class RunTarget(
      val file: VirtualFile,
      val line: Int,
      val name: String,
      val language: Language?,
  )

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = CucumberPanel(project, toolWindow.disposable)
    project.putUserData(PANEL, panel)
    Disposer.register(toolWindow.disposable) { project.putUserData(PANEL, null) }
    val content = ContentFactory.getInstance().createContent(panel.component(), "", false)
    content.preferredFocusableComponent = panel.tree
    toolWindow.contentManager.addContent(content)
    panel.scheduleRefresh()
  }

  companion object {
    // Keywords are classified by GherkinSyntax in the dialect of the # language: header.
    private val TAGS = Regex("""^\s*(@\S+(\s+@\S+)*)\s*$""")
    private val OUTLINE_PARAM = Regex("<[^<>]+>")

    const val TOOL_WINDOW_ID = "Cucumber"
    private val PANEL: Key<CucumberPanel> = Key.create("cucumber.jetbrains.plugin.panel")

    /** Runs a whole feature (`line == 0`) or the scenario starting at a 1-based line. */
    @JvmStatic
    fun run(project: Project, file: VirtualFile, line: Int) {
      val toolWindow =
          ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
      toolWindow.show {
        val panel = project.getUserData(PANEL)
        panel?.runAt(file, line)
      }
    }
  }

  private class CucumberPanel(private val project: Project, private val disposable: Disposable) {
    private val root = DefaultMutableTreeNode("Features")
    private val model = DefaultTreeModel(root)
    val tree = Tree(model)
    private val renderer = Renderer()
    private val search = SearchTextField(false)
    private val summary = JBLabel()
    private val panel = SimpleToolWindowPanel(true, true)
    private val refreshTimer: Timer
    @Volatile private var features: List<FeatureEntry> = listOf()
    /** Set once the first scan finished: until then, an empty [features] means "not known yet". */
    @Volatile private var scanned = false
    /** The selected tree item, kept for the toolbar's background updates. */
    @Volatile private var selection: Any? = null
    /** Whether the selected editor shows a `.feature` file. */
    @Volatile private var editorFeature = false
    private lateinit var toolbar: ActionToolbar
    private var initialized = false
    /** Feature outlines (effective tags) for tag-expression filtering, by file and stamp. */
    private val outlines = HashMap<VirtualFile, Pair<Long, FeatureOutline.Outline>>()

    init {
      refreshTimer = Timer(400) { refresh() }
      refreshTimer.isRepeats = false
      Disposer.register(disposable) { refreshTimer.stop() }
      setUpTree()
      setUpLayout()
      setUpListeners()
    }

    fun component(): JComponent = panel

    // ---------------------------------------------------------------- UI setup

    private fun setUpTree() {
      tree.isRootVisible = false
      tree.showsRootHandles = true
      tree.cellRenderer = renderer
      tree.emptyText.setText("No .feature files found")
      tree.emptyText.appendSecondaryText(
          "Add a .feature file to the project, then refresh",
          SimpleTextAttributes.GRAYED_ATTRIBUTES,
          null,
      )
      TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
      tree.addTreeSelectionListener { selection = selectedValue() }
      editorFeature =
          FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.let { isFeature(it) } == true
      object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
              navigateSelection()
              return true
            }
          }
          .installOn(tree)
      tree.addKeyListener(
          object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
              if (e.keyCode == KeyEvent.VK_ENTER) {
                navigateSelection()
                e.consume()
              }
            }
          })
    }

    private fun setUpLayout() {
      // update() runs in the background (BGT): enabled states only read the volatile fields.
      val hasSelection = { selection != null }
      // Until the first scan is done the run actions stay enabled; a click then reports what's missing.
      val canRun = { features.isNotEmpty() || !scanned }

      val runSelected =
          SimpleAction(
              "Run Selected",
              "Run the selected feature or scenario, else the one at the caret (with the tag filter, if any)",
              AllIcons.Actions.Execute,
              { runSelection() },
              canRun,
          )
      val runAll =
          SimpleAction(
              "Run All Features",
              "Run every feature in the project (with the tag filter, if any)",
              AllIcons.Actions.RunAll,
              { runAll() },
              canRun,
          )
      val refreshAction =
          SimpleAction(
              "Refresh", "Rescan .feature files", AllIcons.Actions.Refresh, { refresh() }, { true })
      val expand = SimpleAction("Expand All", null, AllIcons.Actions.Expandall, { expandAll() }, { true })
      val collapse =
          SimpleAction("Collapse All", null, AllIcons.Actions.Collapseall, { collapseAll() }, { true })
      val locate =
          SimpleAction(
              "Select Opened File",
              "Select the feature/scenario at the caret",
              AllIcons.General.Locate,
              { locateOpenedFile() },
              { editorFeature },
          )
      val copyAction =
          SimpleAction(
              "Copy Run Command",
              "Copy the command that would run the selection",
              AllIcons.Actions.Copy,
              { copyCommand() },
              canRun,
          )
      val navigate =
          SimpleAction(
              "Jump to Source", null, AllIcons.Actions.EditSource, { navigateSelection() }, hasSelection)
      val settings =
          SimpleAction(
              "Settings",
              "Cucumber settings",
              AllIcons.General.Settings,
              {
                ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, CucumberConfigurable::class.java)
              },
              { true },
          )

      // Toolbar: run controls, tree controls, then rarely used actions behind a "More" menu.
      val more = DefaultActionGroup("More", true)
      more.templatePresentation.icon = AllIcons.Actions.More
      more.add(copyAction)
      ActionManager.getInstance().getAction("Cucumber.FindUnusedStepDefinitions")?.let { more.add(it) }
      more.addSeparator()
      more.add(settings)

      val toolbarGroup = DefaultActionGroup()
      toolbarGroup.add(runSelected)
      toolbarGroup.add(runAll)
      toolbarGroup.addSeparator()
      toolbarGroup.add(refreshAction)
      toolbarGroup.add(expand)
      toolbarGroup.add(collapse)
      toolbarGroup.add(locate)
      toolbarGroup.addSeparator()
      toolbarGroup.add(more)
      val toolbar =
          ActionManager.getInstance().createActionToolbar("CucumberToolWindow", toolbarGroup, true)
      toolbar.setTargetComponent(tree)
      this.toolbar = toolbar

      val goToDefinitionAction =
          SimpleAction(
              "Go to Step Definition",
              "Open the step definition matching this step",
              AllIcons.Gutter.ImplementedMethod,
              { goToDefinition() },
              { (selection as? StepEntry)?.defined == true },
          )
      val copyName =
          SimpleAction(
              "Copy Name", null, AllIcons.Actions.Copy, { copy(selectedName()) }, hasSelection)
      val copyPath =
          SimpleAction(
              "Copy Path",
              null,
              AllIcons.FileTypes.Text,
              {
                val file = selectedFile()
                copy(file?.path)
              },
              hasSelection,
          )
      val reveal =
          SimpleAction(
              "Reveal in Project View",
              null,
              AllIcons.Nodes.Folder,
              { revealInProject() },
              hasSelection,
          )
      val expandSelected =
          SimpleAction("Expand", null, AllIcons.Actions.Expandall, { expandSelection() }, hasSelection)

      // Context menu: only actions about the clicked item.
      val copyGroup = DefaultActionGroup("Copy", true)
      copyGroup.templatePresentation.icon = AllIcons.Actions.Copy
      copyGroup.add(copyName)
      copyGroup.add(copyPath)
      copyGroup.add(copyAction)

      val popup = DefaultActionGroup()
      popup.add(runSelected)
      popup.addSeparator()
      popup.add(navigate)
      popup.add(goToDefinitionAction)
      popup.add(reveal)
      popup.addSeparator()
      popup.add(copyGroup)
      popup.addSeparator()
      popup.add(expandSelected)
      popup.add(collapse)
      PopupHandler.installPopupMenu(tree, popup, "CucumberToolWindowPopup")

      search.addDocumentListener(
          object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
              rebuildTree()
            }
          })
      search.textEditor.emptyText.setText("Filter by name, step, @tag or tag expression")
      summary.foreground = UIUtil.getContextHelpForeground()
      summary.font = JBUI.Fonts.smallFont()
      summary.border = JBUI.Borders.empty(4, 2, 0, 0)

      // The summary gets its own line so the filter keeps its full width in a narrow window.
      val header = JPanel(BorderLayout())
      header.add(search, BorderLayout.NORTH)
      header.add(summary, BorderLayout.SOUTH)
      header.border =
          JBUI.Borders.compound(
              JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(4, 6, 6, 6))

      val top = JPanel(BorderLayout())
      top.add(header, BorderLayout.NORTH)
      top.add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)

      // Runs go to the standard Run tool window (test tree, rerun, stop).
      panel.setToolbar(toolbar.component)
      panel.setContent(top)
    }

    private fun setUpListeners() {
      val connection = project.messageBus.connect(disposable)
      connection.subscribe(
          VirtualFileManager.VFS_CHANGES,
          object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
              for (event in events) {
                val path = event.path
                if (path.endsWith(".feature") || Language.forFileName(path) != null) {
                  scheduleRefresh()
                  return
                }
              }
            }
          })
      connection.subscribe(
          FileEditorManagerListener.FILE_EDITOR_MANAGER,
          object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
              val file = event.newFile
              editorFeature = file != null && isFeature(file)
              if (file != null && isFeature(file)) {
                selectFile(file, -1)
              }
            }
          })
      EditorFactory.getInstance()
          .eventMulticaster
          .addDocumentListener(
              object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                  val file = FileDocumentManager.getInstance().getFile(event.document)
                  if (file != null && isFeature(file)) {
                    scheduleRefresh()
                  }
                }
              },
              disposable,
          )
    }

    // ---------------------------------------------------------------- scanning

    fun scheduleRefresh() {
      refreshTimer.restart()
    }

    private fun refresh() {
      ReadAction.nonBlocking<List<FeatureEntry>> { collect() }
          .inSmartMode(project)
          .expireWith(disposable)
          .coalesceBy(this)
          .finishOnUiThread(ModalityState.defaultModalityState()) { result ->
            features = result
            scanned = true
            rebuildTree()
            // The toolbar was last updated before the scan: refresh the run actions' state now.
            toolbar.updateActionsAsync()
          }
          .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun collect(): List<FeatureEntry> {
      val files = ArrayList<VirtualFile>()
      StepIndex.forEachProjectFile(project) { file ->
        if (isFeature(file)) {
          files.add(file)
        }
      }
      files.sortBy { it.path }
      val index = StepIndex.get(project)
      val fallback = fallbackLanguage(index)
      val result = ArrayList<FeatureEntry>()
      for (file in files) {
        result.add(parse(file, index, fallback))
      }
      return result.toList()
    }

    private fun fallbackLanguage(index: StepIndex.Index): Language? {
      for (definition in index.definitions) {
        val language = definition.info.language
        if (LanguageAvailability.isEnabled(language)) {
          return language
        }
      }
      return markerLanguage()
    }

    private fun markerLanguage(): Language? {
      val base = project.basePath ?: return null
      val markers =
          listOf(
              "package.json" to Language.JAVASCRIPT,
              "pom.xml" to Language.JAVA,
              "build.gradle" to Language.JAVA,
              "build.gradle.kts" to Language.JAVA,
              "requirements.txt" to Language.PYTHON,
              "pyproject.toml" to Language.PYTHON,
              "setup.py" to Language.PYTHON,
              "Gemfile" to Language.RUBY,
              "go.mod" to Language.GO,
              "Cargo.toml" to Language.RUST,
          )
      for ((marker, language) in markers) {
        if (Files.exists(Path.of(base, marker)) && LanguageAvailability.isEnabled(language)) {
          return language
        }
      }
      return null
    }

    private fun parse(file: VirtualFile, index: StepIndex.Index, fallback: Language?): FeatureEntry {
      val content = readText(file)
      val lines = content.split(Regex("\r?\n"))
      val dialect = GherkinSyntax.dialect(lines)
      val outlines by lazy { Outlines.scenarios(content) }
      var title = file.nameWithoutExtension
      var titleLine = 0
      val scenarios = ArrayList<TestEntry>()
      val pendingTags = ArrayList<String>()
      var currentSteps: MutableList<StepEntry>? = null
      val votes: MutableMap<Language, Int> = EnumMap(Language::class.java)
      var undefined = 0
      var docDelimiter: String? = null
      var previousKeyword: String? = null
      for (i in lines.indices) {
        val line = lines[i]
        val trimmed = line.trim()
        val fence = docDelimiter
        if (fence != null) {
          if (trimmed.startsWith(fence)) {
            docDelimiter = null
          }
          continue
        }
        if (GherkinSyntax.isDocStringDelimiter(trimmed)) {
          docDelimiter = trimmed.substring(0, 3)
          continue
        }
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue
        }
        val m = TAGS.matchEntire(line)
        if (m != null) {
          pendingTags.addAll(m.groupValues[1].trim().split(Regex("\\s+")))
          continue
        }
        val header = GherkinSyntax.header(trimmed, dialect)
        if (header != null) {
          previousKeyword = null
        }
        when (header?.type) {
          GherkinKeywordType.FEATURE -> {
            if (header.title.isNotEmpty()) {
              title = header.title
              titleLine = i
            }
            pendingTags.clear()
            continue
          }
          GherkinKeywordType.SCENARIO,
          GherkinKeywordType.SCENARIO_OUTLINE -> {
            val steps = ArrayList<StepEntry>()
            currentSteps = steps
            scenarios.add(
                TestEntry(file, i, header.title.ifEmpty { header.keyword }, pendingTags.toList(), steps))
            pendingTags.clear()
            continue
          }
          null -> {}
          else -> {
            if (header.type != GherkinKeywordType.EXAMPLES) {
              currentSteps = null
            }
            pendingTags.clear()
            continue
          }
        }
        val step = GherkinSyntax.step(trimmed, dialect)
        if (step != null && step.text.isNotEmpty() && currentSteps != null) {
          val text = step.text
          val keyword = GherkinSyntax.effectiveKeyword(step.type, previousKeyword)
          previousKeyword = keyword
          val matchText =
              if (OUTLINE_PARAM.containsMatchIn(text))
                  Outlines.scenarioAt(outlines, lineStart(content, i))?.substitute(text) ?: text
              else text
          var defined = true
          if (!OUTLINE_PARAM.containsMatchIn(matchText)) {
            val languages = HashSet<Language>()
            for (definition in index.matching(matchText, keyword)) {
              languages.add(definition.info.language)
            }
            defined = languages.isNotEmpty()
            for (language in languages) {
              if (LanguageAvailability.isEnabled(language)) {
                votes.merge(language, 1, Int::plus)
              }
            }
          }
          if (!defined) {
            undefined++
          }
          currentSteps.add(StepEntry(file, i, step.keyword, text, defined, matchText, keyword))
        }
      }
      val language = votes.entries.maxByOrNull { it.value }?.key ?: fallback
      val frozen = scenarios.map { it.copy(steps = it.steps.toList()) }
      return FeatureEntry(file, titleLine, title, frozen, language, undefined)
    }

    /** Offset of the start of the 0-based [line] in [content]. */
    private fun lineStart(content: String, line: Int): Int {
      var offset = 0
      repeat(line) {
        offset = content.indexOf('\n', offset) + 1
        if (offset == 0) {
          return content.length
        }
      }
      return offset
    }

    // ---------------------------------------------------------------- tree

    private fun rebuildTree() {
      val expanded = HashSet<String>()
      for (row in 0 until tree.rowCount) {
        val path = tree.getPathForRow(row)
        if (tree.isExpanded(path)) {
          expanded.add(key(path.lastPathComponent))
        }
      }
      val selectionPath = tree.selectionPath
      val selected = selectionPath?.let { key(it.lastPathComponent) }

      val filter = search.text.trim().lowercase()
      val tagFilter = tagExpression()
      root.removeAllChildren()
      var scenarioCount = 0
      val languages = LinkedHashSet<String>()
      for (feature in features) {
        val featureMatches =
            tagFilter == null &&
                (filter.isEmpty() || contains(feature.title, filter) || contains(feature.file.name, filter))
        val featureNode = DefaultMutableTreeNode(feature)
        for (test in feature.scenarios) {
          val matches =
              if (tagFilter != null) tagMatches(feature, test, tagFilter)
              else
                  featureMatches ||
                      contains(test.name, filter) ||
                      test.tags.any { contains(it, filter) } ||
                      test.steps.any { contains(it.text, filter) }
          if (!matches) {
            continue
          }
          val testNode = DefaultMutableTreeNode(test)
          for (step in test.steps) {
            testNode.add(DefaultMutableTreeNode(step))
          }
          featureNode.add(testNode)
          scenarioCount++
        }
        if (featureMatches || featureNode.childCount > 0) {
          root.add(featureNode)
          if (feature.language != null) {
            languages.add(feature.language.displayName())
          }
        }
      }
      renderer.showLanguage = features.mapNotNull { it.language }.distinct().size > 1
      model.reload()
      summary.text =
          root.childCount.toString() +
              " features · " +
              scenarioCount +
              " scenarios" +
              (if (languages.isEmpty()) "" else " · " + languages.joinToString(", "))

      if (filter.isNotEmpty()) {
        expandAll()
      } else if (!initialized) {
        for (i in tree.rowCount - 1 downTo 0) {
          tree.expandRow(i)
          val path = tree.getPathForRow(i)
          if (path != null && path.pathCount > 2) {
            tree.collapseRow(i)
          }
        }
        initialized = features.isNotEmpty()
      } else {
        for (row in 0 until tree.rowCount) {
          val path = tree.getPathForRow(row)
          if (expanded.contains(key(path.lastPathComponent))) {
            tree.expandPath(path)
          }
        }
      }
      if (selected != null) {
        for (row in 0 until tree.rowCount) {
          val path = tree.getPathForRow(row)
          if (selected == key(path.lastPathComponent)) {
            tree.selectionPath = path
            break
          }
        }
      }
    }

    private fun expandAll() {
      for (i in 0 until tree.rowCount) {
        tree.expandRow(i)
      }
    }

    // ---------------------------------------------------------------- tag expressions

    /**
     * The filter as a tag expression (`@smoke and not @wip`), or null. A single tag only counts
     * once some scenario carries it exactly, so typing `@sm` still filters by substring.
     */
    private fun tagExpression(): TagExpressions.Expression? {
      val text = search.text.trim()
      if (!TagExpressions.looksLikeExpression(text)) {
        return null
      }
      val expression = TagExpressions.parseOrNull(text) ?: return null
      if (TagExpressions.tags(text).size == 1 && !text.contains(' ') && !text.contains('(')) {
        val used = features.any { feature -> feature.scenarios.any { tagMatches(feature, it, expression) } }
        if (!used) {
          return null
        }
      }
      return expression
    }

    /** The active tag-expression filter text, passed on to runs. */
    private fun activeTags(): String? = if (tagExpression() != null) search.text.trim() else null

    /** Matches on effective tags: feature, rule, scenario and examples tags. */
    private fun tagMatches(
        feature: FeatureEntry,
        test: TestEntry,
        expression: TagExpressions.Expression,
    ): Boolean {
      val scenario = outline(feature.file).scenarios.firstOrNull { it.line == test.line + 1 }
      return scenario?.matches(expression) ?: expression.evaluate(test.tags)
    }

    private fun outline(file: VirtualFile): FeatureOutline.Outline {
      val stamp =
          FileDocumentManager.getInstance().getCachedDocument(file)?.modificationStamp
              ?: file.modificationStamp
      val cached = outlines[file]
      if (cached != null && cached.first == stamp) {
        return cached.second
      }
      val outline = FeatureOutline.parse(readText(file))
      outlines[file] = stamp to outline
      return outline
    }

    private fun collapseAll() {
      for (i in tree.rowCount - 1 downTo 0) {
        tree.collapseRow(i)
      }
    }

    private fun selectedValue(): Any? {
      val path = tree.selectionPath ?: return null
      return (path.lastPathComponent as DefaultMutableTreeNode).userObject
    }

    private fun selectedTarget(): RunTarget? {
      val path = tree.selectionPath ?: return null
      var node = path.lastPathComponent as DefaultMutableTreeNode
      if (node.userObject is StepEntry) {
        node = node.parent as DefaultMutableTreeNode
      }
      val value = node.userObject
      if (value is TestEntry) {
        val feature = (node.parent as DefaultMutableTreeNode).userObject as FeatureEntry
        return RunTarget(value.file, value.line + 1, value.name, feature.language)
      }
      if (value is FeatureEntry) {
        return RunTarget(value.file, 0, value.title, value.language)
      }
      return null
    }

    private fun navigateSelection() {
      var file: VirtualFile? = null
      var line = 0
      when (val value = selectedValue()) {
        is FeatureEntry -> {
          file = value.file
          line = value.line
        }
        is TestEntry -> {
          file = value.file
          line = value.line
        }
        is StepEntry -> {
          file = value.file
          line = value.line
        }
      }
      if (file != null && file.isValid) {
        OpenFileDescriptor(project, file, line, 0).navigate(true)
      }
    }

    private fun selectedFile(): VirtualFile? =
        when (val value = selectedValue()) {
          is FeatureEntry -> value.file
          is TestEntry -> value.file
          is StepEntry -> value.file
          else -> null
        }

    private fun selectedName(): String? =
        when (val value = selectedValue()) {
          is FeatureEntry -> value.title
          is TestEntry -> value.name
          is StepEntry -> value.keyword + " " + value.text
          else -> null
        }

    private fun goToDefinition() {
      val step = selectedValue() as? StepEntry ?: return
      val matches = StepNavigation.definitions(project, step.matchText, step.effectiveKeyword)
      val path = tree.selectionPath
      val bounds = if (path == null) null else tree.getPathBounds(path)
      val where =
          if (bounds == null) null
          else RelativePoint(tree, Point(bounds.x, bounds.y + bounds.height))
      if (!StepNavigation.navigate(project, matches, where)) {
        navigateSelection()
      }
    }

    private fun copy(text: String?) {
      if (text != null) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
      }
    }

    private fun revealInProject() {
      val file = selectedFile()
      if (file != null && file.isValid) {
        ProjectView.getInstance(project).select(null, file, true)
      }
    }

    private fun expandSelection() {
      val path = tree.selectionPath
      if (path == null) {
        expandAll()
        return
      }
      val node = path.lastPathComponent as DefaultMutableTreeNode
      for (element in node.depthFirstEnumeration()) {
        val child = element as DefaultMutableTreeNode
        if (!child.isLeaf) {
          tree.expandPath(TreePath(child.path))
        }
      }
    }

    private fun locateOpenedFile() {
      val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
      val file = FileDocumentManager.getInstance().getFile(editor.document)
      if (file != null) {
        selectFile(file, editor.caretModel.logicalPosition.line)
      }
    }

    private fun selectFile(file: VirtualFile, line: Int) {
      for (i in 0 until root.childCount) {
        val featureNode = root.getChildAt(i) as DefaultMutableTreeNode
        if ((featureNode.userObject as FeatureEntry).file != file) {
          continue
        }
        var target = featureNode
        if (line >= 0) {
          for (j in 0 until featureNode.childCount) {
            val testNode = featureNode.getChildAt(j) as DefaultMutableTreeNode
            if ((testNode.userObject as TestEntry).line <= line) {
              target = testNode
            }
          }
          if (target !== featureNode) {
            for (k in 0 until target.childCount) {
              val stepNode = target.getChildAt(k) as DefaultMutableTreeNode
              if ((stepNode.userObject as StepEntry).line == line) {
                target = stepNode
                break
              }
            }
          }
        }
        val path = TreePath(target.path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
        return
      }
    }

    // ---------------------------------------------------------------- running

    /** Runs through a (temporary) Cucumber run configuration; results go to the Run window. */
    private fun run(target: RunTarget?) {
      val language = target?.language ?: projectLanguage()
      try {
        CucumberLauncher.run(
            project,
            target?.file?.path,
            target?.line ?: 0,
            target?.name ?: "All Features",
            language,
            activeTags(),
        )
      } catch (e: Exception) {
        if (e is ControlFlowException) {
          throw e
        }
        LOG.warn("Cannot run ${target?.name ?: "all features"}", e)
        CucumberLauncher.notifyFailure(project, e.message ?: e.toString())
      }
    }

    private fun runAll() {
      if (scanned && features.isEmpty()) {
        Messages.showInfoMessage(project, "There are no .feature files in this project.", "Cucumber")
        return
      }
      run(null)
    }

    /** The selected tree item, else the feature / scenario at the caret of the current editor. */
    private fun runSelection() {
      val target = selectedTarget() ?: editorTarget()
      if (target == null) {
        Messages.showInfoMessage(
            project,
            "Select a feature or scenario in the Cucumber tool window (or open a .feature file), then press Run Selected.",
            "Cucumber")
        return
      }
      run(target)
    }

    private fun editorTarget(): RunTarget? {
      val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
      val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
      if (!isFeature(file)) {
        return null
      }
      val feature = features.firstOrNull { it.file == file }
      val caretLine = editor.caretModel.logicalPosition.line
      val scenario = feature?.scenarios?.lastOrNull { it.line <= caretLine }
      return if (scenario != null) {
        RunTarget(file, scenario.line + 1, scenario.name, feature.language)
      } else {
        RunTarget(file, 0, feature?.title ?: file.name, feature?.language)
      }
    }

    /** Runs the feature (line 0) or the scenario at a 1-based line, e.g. from the editor gutter. */
    fun runAt(file: VirtualFile, line: Int) {
      val feature = features.firstOrNull { it.file == file }
      var name = feature?.title ?: file.name
      if (feature != null && line > 0) {
        for (test in feature.scenarios) {
          if (test.line + 1 == line) {
            name = test.name
          }
        }
      }
      run(RunTarget(file, line, name, feature?.language))
      selectFile(file, maxOf(0, line - 1))
    }

    private fun copyCommand() {
      try {
        val command = buildCommand(selectedTarget())
        CopyPasteManager.getInstance()
            .setContents(StringSelection(ParametersListUtil.join(command.argv)))
      } catch (e: ExecutionException) {
        Messages.showWarningDialog(project, e.message ?: e.toString(), "Cucumber")
      }
    }

    @Throws(ExecutionException::class)
    private fun buildCommand(target: RunTarget?): RunCommand {
      val base = requireBase()
      val request =
          CucumberCommands.Request(
              base = base,
              language = target?.language ?: projectLanguage(),
              features =
                  if (target == null) listOf()
                  else listOf(target.file.path + (if (target.line > 0) ":" + target.line else "")),
              tags = activeTags(),
              gradleInitScript = CucumberCommands.gradleInitScript(),
          )
      try {
        return CucumberCommands.build(request)
      } catch (e: CucumberCommands.CommandException) {
        throw ExecutionException(e.message)
      }
    }

    private fun projectLanguage(): Language? {
      val counts: MutableMap<Language, Int> = EnumMap(Language::class.java)
      for (feature in features) {
        if (feature.language != null) {
          counts.merge(feature.language, 1, Int::plus)
        }
      }
      return counts.entries.maxByOrNull { it.value }?.key ?: markerLanguage()
    }

    @Throws(ExecutionException::class)
    private fun requireBase(): String =
        project.basePath ?: throw ExecutionException("The project has no base directory")

    companion object {
      private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(CucumberPanel::class.java)

      private fun readText(file: VirtualFile): String {
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        if (document != null) {
          return document.text
        }
        return try {
          String(file.contentsToByteArray(), StandardCharsets.UTF_8)
        } catch (e: IOException) {
          ""
        }
      }

      private fun isFeature(file: VirtualFile): Boolean =
          "feature".equals(file.extension, ignoreCase = true)

      private fun contains(text: String, filter: String): Boolean =
          text.lowercase().contains(filter)

      private fun key(node: Any?): String {
        val value = if (node is DefaultMutableTreeNode) node.userObject else node
        return when (value) {
          is FeatureEntry -> value.file.path
          is TestEntry -> value.file.path + ":" + value.line
          is StepEntry -> value.file.path + ":" + value.line + ":step"
          else -> value.toString()
        }
      }
    }
  }

  // ------------------------------------------------------------------ helpers

  private class SimpleAction(
      text: String,
      description: String?,
      icon: Icon?,
      private val runnable: () -> Unit,
      private val enabled: () -> Boolean,
  ) : DumbAwareAction(text, description, icon) {

    override fun actionPerformed(e: AnActionEvent) {
      runnable()
    }

    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = enabled()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
  }

  private class Renderer : ColoredTreeCellRenderer() {
    /** Per-feature language pills only help when the project mixes step-definition languages. */
    var showLanguage = false


    private fun pill(text: String, attributes: SimpleTextAttributes) {
      append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
      append(" $text ", attributes)
    }

    private fun appendStepText(text: String, plain: SimpleTextAttributes) {
      val pattern = Regex("""<[^<>]+>|"[^"]*"|'[^']*'|\b\d+(?:\.\d+)?\b""")
      var last = 0
      for (match in pattern.findAll(text)) {
        val start = match.range.first
        if (start > last) {
          append(text.substring(last, start), plain)
        }
        append(match.value, PARAMETER)
        last = match.range.last + 1
      }
      if (last < text.length) {
        append(text.substring(last), plain)
      }
    }

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
      val item = if (value is DefaultMutableTreeNode) value.userObject else value
      when (item) {
        is FeatureEntry -> {
          icon = CucumberIcons.CUCUMBER
          append(item.title, FEATURE)
          if (item.language != null && showLanguage) {
            pill(item.language.displayName(), badge(item.language))
          }
          val scenarios = item.scenarios.size
          append(
              "   " +
                  item.file.name +
                  "  ·  " +
                  scenarios +
                  (if (scenarios == 1) " scenario" else " scenarios"),
              META,
          )
          if (item.undefined > 0) {
            pill("${item.undefined} undefined", ERROR_PILL)
          }
        }
        is TestEntry -> {
          val undefined = item.steps.count { !it.defined }.toLong()
          icon =
              if (undefined > 0) AllIcons.RunConfigurations.TestError
              else AllIcons.RunConfigurations.TestState.Run
          append(item.name, SCENARIO_NAME)
          for (tag in item.tags) {
            pill(tag, TAG)
          }
          val steps = item.steps.size
          append("   " + steps + (if (steps == 1) " step" else " steps"), META)
          if (undefined > 0) {
            pill("$undefined undefined", ERROR_PILL)
          }
        }
        is StepEntry -> {
          icon =
              LetterIcon.forKeyword(GherkinSyntax.canonicalStepKeyword(item.keyword), item.defined)
          append(item.keyword + " ", keyword(item.keyword))
          if (item.defined) {
            appendStepText(item.text, SimpleTextAttributes.REGULAR_ATTRIBUTES)
          } else {
            append(item.text, UNDEFINED)
            pill("undefined", ERROR_PILL)
          }
        }
        null -> {}
        else -> append(item.toString())
      }
    }

    companion object {
      private val UNDEFINED =
          SimpleTextAttributes(SimpleTextAttributes.STYLE_WAVED, null, JBColor.RED)
      private val FEATURE = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, null)
      private val SCENARIO_NAME = SimpleTextAttributes.REGULAR_ATTRIBUTES
      private val TAG =
          SimpleTextAttributes(
              JBColor(0xFFF4D6, 0x4A3F22),
              JBColor(0x8A6500, 0xE3C26B),
              null,
              SimpleTextAttributes.STYLE_SMALLER,
          )
      private val GIVEN = bold(LetterIcon.GIVEN)
      private val WHEN = bold(LetterIcon.WHEN)
      private val THEN = bold(LetterIcon.THEN)
      private val CONJUNCTION = bold(LetterIcon.OTHER)
      private val PARAMETER =
          SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0x0A5FC2, 0x6FA8F0))
      private val META = SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES
      private val ERROR_PILL =
          SimpleTextAttributes(
              JBColor(0xFDE7E7, 0x5A2B2B),
              JBColor(0xC62828, 0xFF8A80),
              null,
              SimpleTextAttributes.STYLE_SMALLER or SimpleTextAttributes.STYLE_BOLD,
          )

      private fun bold(color: JBColor): SimpleTextAttributes =
          SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color)

      private fun badge(language: Language): SimpleTextAttributes {
        val rgb =
            when (language) {
              Language.JAVA -> intArrayOf(0xB07219, 0xE0A040)
              Language.KOTLIN -> intArrayOf(0x7F52FF, 0xA97BFF)
              Language.GROOVY -> intArrayOf(0x4298B8, 0x6FB8D6)
              Language.SCALA -> intArrayOf(0xC22D40, 0xE06070)
              Language.JAVASCRIPT -> intArrayOf(0x8C7A00, 0xF1E05A)
              Language.PYTHON -> intArrayOf(0x3572A5, 0x6FA8DC)
              Language.RUBY -> intArrayOf(0xA91401, 0xE06666)
              Language.GO -> intArrayOf(0x00838F, 0x00ADD8)
              Language.RUST -> intArrayOf(0x8D4B2A, 0xDEA584)
              Language.CSHARP -> intArrayOf(0x178600, 0x7BC67B)
              Language.CPP -> intArrayOf(0x9C2C5C, 0xF34B7D)
            }
        val light = Color(rgb[0])
        val dark = Color(rgb[1])
        val tint = JBColor(blend(light, Color.WHITE, 0.86f), blend(dark, Color(0x2B2D30), 0.75f))
        return SimpleTextAttributes(
            tint,
            JBColor(light, dark),
            null,
            SimpleTextAttributes.STYLE_SMALLER or SimpleTextAttributes.STYLE_BOLD,
        )
      }

      private fun blend(color: Color, base: Color, amount: Float): Color =
          Color(
              Math.round(color.red * (1 - amount) + base.red * amount),
              Math.round(color.green * (1 - amount) + base.green * amount),
              Math.round(color.blue * (1 - amount) + base.blue * amount),
          )

      private fun keyword(keyword: String): SimpleTextAttributes =
          when (GherkinSyntax.canonicalStepKeyword(keyword)) {
            "given" -> GIVEN
            "when" -> WHEN
            "then" -> THEN
            else -> CONJUNCTION
          }
    }
  }
}
