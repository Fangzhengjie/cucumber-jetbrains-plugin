package io.github.fangzhengjie.cucumber.feature

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import io.github.fangzhengjie.cucumber.core.Outlines

/**
 * Scenario Outline checks: `<param>` without an Examples column, unused Examples columns, outlines
 * without Examples and `<param>` in plain scenarios.
 */
class OutlineInspection : LocalInspectionTool() {

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
      object : PsiElementVisitor() {
        override fun visitFile(file: PsiFile) {
          if (file !is FeatureFile) {
            return
          }
          for (problem in Outlines.problems(FeatureOutlines.scenarios(file))) {
            val (element, range) = FeatureOutlines.anchor(file, problem.start, problem.end)
            val type =
                if (problem.severity == Outlines.Severity.WEAK_WARNING)
                    ProblemHighlightType.WEAK_WARNING
                else ProblemHighlightType.GENERIC_ERROR_OR_WARNING
            holder.registerProblem(element, problem.message, type, range)
          }
        }
      }
}
