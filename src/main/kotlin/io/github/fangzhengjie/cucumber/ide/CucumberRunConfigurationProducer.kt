package io.github.fangzhengjie.cucumber.ide

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import io.github.fangzhengjie.cucumber.core.FeatureOutline
import io.github.fangzhengjie.cucumber.core.IgnoredPaths

/**
 * Creates Cucumber run configurations from the context: a scenario or example row at the caret, a
 * `.feature` file, or a directory containing feature files.
 */
class CucumberRunConfigurationProducer : LazyRunConfigurationProducer<CucumberRunConfiguration>() {

  private data class Target(val path: String, val line: Int, val name: String)

  override fun getConfigurationFactory(): ConfigurationFactory =
      CucumberRunConfigurationType.instance().factory()

  override fun setupConfigurationFromContext(
      configuration: CucumberRunConfiguration,
      context: ConfigurationContext,
      sourceElement: Ref<PsiElement>,
  ): Boolean {
    val target = target(context.psiLocation ?: return false) ?: return false
    configuration.featurePath = target.path
    configuration.line = target.line
    configuration.targetName = target.name
    configuration.setGeneratedName()
    return true
  }

  override fun isConfigurationFromContext(
      configuration: CucumberRunConfiguration,
      context: ConfigurationContext,
  ): Boolean {
    val target = target(context.psiLocation ?: return false) ?: return false
    return configuration.absoluteFeaturePath() == target.path &&
        configuration.line == target.line &&
        configuration.tags.isBlank()
  }

  private fun target(element: PsiElement): Target? {
    if (element is PsiDirectory) {
      val dir = element.virtualFile
      return if (containsFeatures(dir)) Target(dir.path, 0, dir.name) else null
    }
    val file = element.containingFile ?: return null
    val virtualFile = file.virtualFile ?: return null
    if (!"feature".equals(virtualFile.extension, ignoreCase = true)) {
      return null
    }
    val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
    val outline = FeatureOutline.parse(document.text)
    val line =
        if (element is PsiFile) 0 else document.getLineNumber(element.textRange.startOffset) + 1
    val target = FeatureOutline.targetAt(outline, line)
    val name = target.name.ifBlank { virtualFile.nameWithoutExtension }
    return Target(virtualFile.path, if (target.isFeature) 0 else target.line, name)
  }

  private fun containsFeatures(dir: VirtualFile): Boolean {
    var found = false
    VfsUtilCore.visitChildrenRecursively(
        dir,
        object : VirtualFileVisitor<Unit>(VirtualFileVisitor.limit(8)) {
          override fun visitFile(file: VirtualFile): Boolean {
            if (found) {
              return false
            }
            if (!file.isDirectory && "feature".equals(file.extension, ignoreCase = true)) {
              found = true
              return false
            }
            if (!file.isDirectory || file == dir) {
              return true
            }
            val relative = VfsUtilCore.getRelativePath(file, dir, '/') ?: return false
            return !IgnoredPaths.isIgnoredDirectory(relative)
          }
        })
    return found
  }
}
