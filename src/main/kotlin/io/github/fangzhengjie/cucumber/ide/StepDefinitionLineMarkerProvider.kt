package io.github.fangzhengjie.cucumber.ide

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.awt.RelativePoint
import io.github.fangzhengjie.cucumber.CucumberIcons
import io.github.fangzhengjie.cucumber.core.Language
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

/**
 * Gutter icon on each step definition in code, in any language: the Cucumber icon when feature
 * steps use it (the tooltip gives the count; click to open the step, or choose among several), or a
 * grey icon for an unused definition.
 *
 * Registered for every language (`language=""`), so it also runs on files the IDE only knows as
 * plain text (e.g. Go or Rust in IntelliJ IDEA): such a file is a single PSI leaf, and the markers
 * are anchored to the leaf at each pattern's offset with a range on the pattern's line.
 */
class StepDefinitionLineMarkerProvider : LineMarkerProviderDescriptor() {

  override fun getName(): String = "Cucumber step definition usages"

  override fun getIcon(): Icon = CucumberIcons.CUCUMBER

  override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
    // Fast path, run before every other marker of the file: only when the usage index is already
    // built (it is warmed up at startup), so the icons appear with the editor's first markers.
    if (element.firstChild != null) {
      return null
    }
    val file = element.containingFile ?: return null
    val virtualFile = file.viewProvider.virtualFile
    if (Language.forExtension(virtualFile.extension) == null) {
      return null
    }
    val usages = StepUsageIndex.getIfUpToDate(file.project) ?: return null
    val range = element.textRange ?: return null
    val inLeaf =
        usages.definitionsIn(virtualFile).filter {
          it.info.offset in range.startOffset until range.endOffset
        }
    // A fast provider gives one marker per element; a plain-text file is one leaf with several.
    if (inLeaf.size != 1) {
      return null
    }
    val definition = inLeaf[0]
    fastMarkers(file, usages).add(definition.info.offset)
    return marker(element, definition.info.offset, range, usages.of(definition))
  }

  override fun collectSlowLineMarkers(
      elements: MutableList<out PsiElement>,
      result: MutableCollection<in LineMarkerInfo<*>>,
  ) {
    val first = elements.firstOrNull() ?: return
    val file = first.containingFile ?: return
    val virtualFile = file.viewProvider.virtualFile
    if (Language.forExtension(virtualFile.extension) == null) {
      return
    }
    val project = file.project
    val usages = StepUsageIndex.get(project)
    val definitions = usages.definitionsIn(virtualFile)
    if (definitions.isEmpty()) {
      return
    }
    val alreadyShown =
        file
            .getUserData(FAST)
            ?.takeIf { it.stamp == file.modificationStamp && it.usages === usages }
            ?.offsets
    for (element in elements) {
      if (element.firstChild != null || element.containingFile != file) {
        continue
      }
      val range = element.textRange ?: continue
      for (definition in definitions) {
        val offset = definition.info.offset
        if (offset < range.startOffset || offset >= range.endOffset) {
          continue
        }
        if (alreadyShown != null && offset in alreadyShown) {
          continue
        }
        ProgressManager.checkCanceled()
        result.add(marker(element, offset, range, usages.of(definition)))
      }
    }
  }

  /** Offsets the fast path already marked for this file version and index. */
  private fun fastMarkers(file: PsiFile, usages: StepUsageIndex.Usages): MutableSet<Int> {
    val stamp = file.modificationStamp
    val current = file.getUserData(FAST)
    if (current != null && current.stamp == stamp && current.usages === usages) {
      return current.offsets
    }
    val fresh = FastMarkers(stamp, usages, ConcurrentHashMap.newKeySet())
    file.putUserData(FAST, fresh)
    return fresh.offsets
  }

  private class FastMarkers(
      val stamp: Long,
      val usages: StepUsageIndex.Usages,
      val offsets: MutableSet<Int>
  )

  private fun marker(
      element: PsiElement,
      offset: Int,
      leafRange: TextRange,
      usages: List<StepUsageIndex.Usage>,
  ): LineMarkerInfo<PsiElement> {
    val count = usages.size
    val icon: Icon =
        if (count == 0) IconLoader.getDisabledIcon(CucumberIcons.CUCUMBER)
        else CucumberIcons.CUCUMBER
    val tooltip =
        when (count) {
          0 -> "Unused step definition: no feature step uses it"
          1 -> "Used by 1 feature step: " + usages[0].describe()
          else -> "Used by $count feature steps (click to choose)"
        }
    return LineMarkerInfo(
        element,
        TextRange(offset, minOf(offset + 1, leafRange.endOffset)),
        icon,
        { tooltip },
        { mouseEvent: MouseEvent, target: PsiElement ->
          val project = target.project
          val current =
              stepReadAction(project, STEP_INDEX_PROGRESS) {
                val all = StepUsageIndex.get(project)
                val virtualFile = target.containingFile?.viewProvider?.virtualFile
                val definition =
                    all.definitionsIn(virtualFile).firstOrNull { it.info.offset == offset }
                if (definition == null) emptyList() else all.of(definition)
              } ?: return@LineMarkerInfo
          StepUsagesPopup.show(
              project,
              current,
              "Feature Steps Using This Definition",
              null,
              RelativePoint(mouseEvent))
        },
        GutterIconRenderer.Alignment.RIGHT,
        { if (count == 0) "Unused step definition" else "Step definition usages" })
  }

  private companion object {
    val FAST =
        com.intellij.openapi.util.Key.create<FastMarkers>(
            "cucumber.jetbrains.plugin.fastStepMarkers")
  }
}
