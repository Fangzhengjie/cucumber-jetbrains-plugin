package io.github.fangzhengjie.cucumber.feature

import io.github.fangzhengjie.cucumber.ide.CreateStepDefinitionFix
import io.github.fangzhengjie.cucumber.ide.StepIndex
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

class FeatureStepInspection : LocalInspectionTool() {

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
      object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
          val step = element as? FeatureStep ?: return
          val index: StepIndex.Index = StepIndex.get(step.project)
          val matches = index.matching(step)
          if (matches.isEmpty()) {
            val keyword = step.effectiveKeyword()
            val mismatch =
                io.github.fangzhengjie.cucumber.core.StepArguments.bestMismatch(
                    index.definitions.map { it.info }.filter { it.acceptsKeyword(keyword) },
                    FeatureOutlines.matchText(step))
            val message =
                if (mismatch == null) "Undefined Cucumber step"
                else "Undefined Cucumber step: " + mismatch.message
            if (index.files.any { entry -> entry.file.isWritable }) {
              holder.registerProblem(
                  step,
                  message,
                  ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                  CreateStepDefinitionFix())
            } else {
              holder.registerProblem(
                  step, message, ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
            }
          } else if (matches.size > 1) {
            holder.registerProblem(
                step,
                "Ambiguous Cucumber step (" + matches.size + " definitions)",
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
          }
        }
      }
}
