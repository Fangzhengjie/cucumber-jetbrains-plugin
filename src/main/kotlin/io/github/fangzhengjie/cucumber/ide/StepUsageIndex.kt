package io.github.fangzhengjie.cucumber.ide

import io.github.fangzhengjie.cucumber.core.Language
import io.github.fangzhengjie.cucumber.core.StepRename
import io.github.fangzhengjie.cucumber.feature.FeatureOutlines
import io.github.fangzhengjie.cucumber.feature.FeatureStep
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import java.util.regex.Pattern

/**
 * The reverse of [StepIndex]: for each step definition, the feature steps it matches. Feature
 * files are found by scanning the project like [StepIndex]; each file's steps are cached until
 * that file changes, and step texts are only matched again when the definitions' patterns change.
 * Rebuilt only when step definitions or feature files change (see [CucumberModificationTrackers]).
 * All functions must be called in a read action.
 */
object StepUsageIndex {

  /**
   * A step in a feature file. `textOffset` is where the step text (after the keyword) starts;
   * `matchText` is the text matched against definitions (Scenario Outline `<param>`s substituted);
   * `effectiveKeyword` is its `given` / `when` / `then` (`And` / `But` resolved).
   */
  data class Usage(
      val file: VirtualFile,
      val keyword: String,
      val text: String,
      val textOffset: Int,
      val line: Int,
      val matchText: String = text,
      val effectiveKeyword: String? = null,
  ) {
    fun describe(): String = file.name + ":" + (line + 1) + "  " + keyword + " " + text
  }

  class Usages
  internal constructor(
      val index: StepIndex.Index,
      val steps: List<Usage>,
      private val byDefinition: Map<StepIndex.Definition, List<Usage>>,
      private val byFile: Map<VirtualFile, List<StepIndex.Definition>>,
  ) {

    fun of(definition: StepIndex.Definition): List<Usage> = byDefinition[definition] ?: emptyList()

    /** The step definitions in `file`, in source order. */
    fun definitionsIn(file: VirtualFile?): List<StepIndex.Definition> =
        if (file == null) emptyList() else byFile[file] ?: emptyList()
  }

  /** Step texts matched against one list of patterns (indices into it). */
  private class Matches(val regexes: List<String>, val patterns: List<Pattern?>) {
    val byText = HashMap<String, IntArray>()

    fun of(text: String): IntArray =
        byText.getOrPut(text) {
          val hits = ArrayList<Int>()
          for (i in patterns.indices) {
            val pattern = patterns[i]
            if (pattern != null && pattern.matcher(text).find()) {
              hits.add(i)
            }
          }
          hits.toIntArray()
        }
  }

  private val USAGES: Key<CachedValue<Usages>> = Key.create("cucumber.jetbrains.plugin.usages")
  private val FEATURE_FILES: Key<CachedValue<List<VirtualFile>>> =
      Key.create("cucumber.jetbrains.plugin.featureFiles")
  private val FILE_STEPS: Key<CachedValue<List<Usage>>> =
      Key.create("cucumber.jetbrains.plugin.fileSteps")
  private val MATCHES: Key<Matches> = Key.create("cucumber.jetbrains.plugin.matches")

  fun get(project: Project): Usages =
      CachedValuesManager.getManager(project)
          .getCachedValue(
              project,
              USAGES,
              CachedValueProvider {
                CachedValueProvider.Result.create(
                    compute(project),
                    CucumberModificationTrackers.getInstance(project).features,
                    *StepIndex.dependencies(project))
              },
              false)

  /** The index if it is already built and current, else null; never computes anything. */
  fun getIfUpToDate(project: Project): Usages? {
    val cached = project.getUserData(USAGES) ?: return null
    return if (cached.hasUpToDateValue()) cached.value else null
  }

  /** The project's `.feature` files. */
  fun featureFiles(project: Project): List<VirtualFile> =
      CachedValuesManager.getManager(project)
          .getCachedValue(
              project,
              FEATURE_FILES,
              CachedValueProvider {
                val files = ArrayList<VirtualFile>()
                StepIndex.forEachProjectFile(project) { file ->
                  if ("feature".equals(file.extension, ignoreCase = true)) {
                    files.add(file)
                  }
                }
                CachedValueProvider.Result.create<List<VirtualFile>>(
                    files.distinct(),
                    CucumberModificationTrackers.getInstance(project).files,
                    ProjectRootModificationTracker.getInstance(project))
              },
              false)

  /** The steps of one feature file, cached until it changes. */
  fun fileSteps(file: PsiFile): List<Usage> =
      CachedValuesManager.getManager(file.project)
          .getCachedValue(
              file,
              FILE_STEPS,
              CachedValueProvider {
                val virtualFile = file.viewProvider.virtualFile
                val text = file.viewProvider.contents
                val steps =
                    PsiTreeUtil.findChildrenOfType(file, FeatureStep::class.java).map { step ->
                      val offset = step.stepTextOffset()
                      Usage(
                          virtualFile,
                          step.keyword(),
                          step.stepText(),
                          offset,
                          StringUtil.offsetToLineNumber(text, offset),
                          FeatureOutlines.matchText(step),
                          step.effectiveKeyword())
                    }
                CachedValueProvider.Result.create(steps, file)
              },
              false)

  private fun compute(project: Project): Usages {
    val index = StepIndex.get(project)
    val definitions = index.definitions
    val regexes = definitions.map { it.info.regex }
    var matches = project.getUserData(MATCHES)
    if (matches == null || matches.regexes != regexes) {
      matches = Matches(regexes, definitions.map { it.info.compiled })
      project.putUserData(MATCHES, matches)
    }
    val psiManager = PsiManager.getInstance(project)
    val steps = ArrayList<Usage>()
    for (file in featureFiles(project)) {
      if (!file.isValid) {
        continue
      }
      val psiFile = psiManager.findFile(file) ?: continue
      steps.addAll(fileSteps(psiFile))
    }
    val byDefinition = HashMap<StepIndex.Definition, MutableList<Usage>>()
    synchronized(matches) {
      matches.byText.keys.retainAll(steps.mapTo(HashSet()) { it.matchText })
      for (step in steps) {
        for (i in matches.of(step.matchText)) {
          val definition = definitions[i]
          if (definition.info.acceptsKeyword(step.effectiveKeyword)) {
            byDefinition.getOrPut(definition) { ArrayList() }.add(step)
          }
        }
      }
    }
    val byFile = LinkedHashMap<VirtualFile, MutableList<StepIndex.Definition>>()
    for (definition in definitions) {
      val file = definition.file.viewProvider.virtualFile
      byFile.getOrPut(file) { ArrayList() }.add(definition)
    }
    return Usages(index, steps, byDefinition, byFile)
  }

  /**
   * The step definition whose pattern literal contains `offset` in `file` (`text` is its
   * content), else the one on the same line.
   */
  fun definitionAt(
      usages: Usages,
      file: VirtualFile,
      text: CharSequence,
      offset: Int,
  ): StepIndex.Definition? {
    val definitions = usages.definitionsIn(file)
    if (definitions.isEmpty()) {
      return null
    }
    val language = Language.forExtension(file.extension) ?: return null
    val source = text.toString()
    for (definition in definitions) {
      val literal = StepRename.literalAt(language, source, definition.info.offset)
      val end = literal?.end ?: definition.info.offset
      if (definition.info.offset <= offset && offset <= end) {
        return definition
      }
    }
    val line = StringUtil.offsetToLineNumber(source, offset)
    return definitions.firstOrNull { definition ->
      definition.info.offset <= source.length &&
          StringUtil.offsetToLineNumber(source, definition.info.offset) == line
    }
  }
}
