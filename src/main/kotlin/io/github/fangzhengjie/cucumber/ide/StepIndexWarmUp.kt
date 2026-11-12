package io.github.fangzhengjie.cucumber.ide

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Builds the step indexes in the background once the project is indexed, so the first step
 * definition file opened shows its gutter icons right away instead of waiting for a full scan
 * (seconds on large projects). Afterwards the indexes are updated incrementally on edits.
 */
class StepIndexWarmUp : ProjectActivity {

  override suspend fun execute(project: Project) {
    smartReadAction(project) { StepUsageIndex.get(project) }
    // Editors opened while the scan ran got their icons from the slow path; refresh them so the
    // fast path takes over from now on.
    DaemonCodeAnalyzer.getInstance(project).restart("Cucumber step index ready")
  }
}
