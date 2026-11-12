package io.github.fangzhengjie.cucumber.feature

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.fangzhengjie.cucumber.CucumberIcons
import io.github.fangzhengjie.cucumber.core.GherkinOutline
import io.github.fangzhengjie.cucumber.ide.LetterIcon
import javax.swing.Icon

/**
 * Structure tool window: Feature -> Background / Rule -> Scenario (Outline) -> Examples / steps.
 */
class FeatureStructureViewFactory : PsiStructureViewFactory {

  override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
    if (psiFile !is FeatureFile) {
      return null
    }
    return object : TreeBasedStructureViewBuilder() {
      override fun createStructureViewModel(editor: Editor?): StructureViewModel =
          FeatureStructureViewModel(psiFile, editor)

      override fun isRootNodeShown(): Boolean = false
    }
  }
}

private class FeatureStructureViewModel(private val file: PsiFile, private val editor: Editor?) :
    StructureViewModelBase(file, editor, FeatureTreeElement(file, null)),
    StructureViewModel.ElementInfoProvider {

  override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false

  override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean =
      element is FeatureTreeElement && element.section?.kind == GherkinOutline.Kind.STEP

  override fun getCurrentEditorElement(): Any? {
    val caret = editor?.caretModel?.offset ?: return null
    var best: GherkinOutline.Node? = null
    for (node in GherkinOutline.parse(file.viewProvider.contents).all()) {
      if (node.startOffset <= caret && caret <= node.endOffset) {
        if (best == null || node.kind.level > best.kind.level) {
          best = node
        }
      }
    }
    return best?.let { elementAt(file, it) }
  }
}

/** The file (section == null) or a section / step, keyed by the PSI element at its keyword. */
private class FeatureTreeElement(
    private val element: PsiElement,
    val section: GherkinOutline.Node?,
) : StructureViewTreeElement, ItemPresentation {

  override fun getValue(): Any = element

  override fun getPresentation(): ItemPresentation = this

  override fun getChildren(): Array<TreeElement> {
    if (!element.isValid) {
      return TreeElement.EMPTY_ARRAY
    }
    val file = element.containingFile ?: return TreeElement.EMPTY_ARRAY
    val nodes = section?.children ?: GherkinOutline.parse(file.viewProvider.contents).roots
    return nodes
        .mapNotNull { node -> elementAt(file, node)?.let { FeatureTreeElement(it, node) } }
        .toTypedArray()
  }

  override fun navigate(requestFocus: Boolean) {
    val file = element.containingFile?.virtualFile ?: return
    val offset = section?.startOffset ?: 0
    PsiNavigationSupport.getInstance()
        .createNavigatable(element.project, file, offset)
        .navigate(requestFocus)
  }

  override fun canNavigate(): Boolean =
      element.isValid && element.containingFile?.virtualFile != null

  override fun canNavigateToSource(): Boolean = canNavigate()

  override fun getPresentableText(): String {
    val node = section ?: return element.containingFile?.name ?: ""
    return if (node.kind == GherkinOutline.Kind.STEP) "${node.keyword} ${node.title}"
    else node.toString()
  }

  override fun getIcon(unused: Boolean): Icon? =
      when (section?.kind) {
        null -> FeatureFileType.icon
        GherkinOutline.Kind.FEATURE -> CucumberIcons.CUCUMBER
        GherkinOutline.Kind.RULE -> AllIcons.Nodes.TestGroup
        GherkinOutline.Kind.BACKGROUND -> AllIcons.Nodes.ClassInitializer
        GherkinOutline.Kind.SCENARIO -> AllIcons.Nodes.Test
        GherkinOutline.Kind.SCENARIO_OUTLINE -> AllIcons.Nodes.Template
        GherkinOutline.Kind.EXAMPLES -> AllIcons.Nodes.DataTables
        GherkinOutline.Kind.STEP -> LetterIcon.forKeyword(section.keyword, true)
      }

  override fun equals(other: Any?): Boolean =
      other is FeatureTreeElement && other.element == element

  override fun hashCode(): Int = element.hashCode()
}

private fun elementAt(file: PsiFile, node: GherkinOutline.Node): PsiElement? {
  val leaf = file.findElementAt(node.startOffset) ?: return null
  return if (leaf.parent is FeatureStep) leaf.parent else leaf
}
