package io.github.fangzhengjie.cucumber.ide

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import java.util.Objects

/**
 * Navigation target at an exact offset, for files the IDE has no fine-grained PSI for (e.g. a
 * `.go` file in RustRover is one plain-text element, which would navigate to line 1).
 * Never serialized (the `Serializable` comes from the PSI base class).
 */
internal class StepTarget(
    private val file: PsiFile,
    private val offset: Int,
    private val label: String,
) : FakePsiElement() {

  override fun getParent(): PsiElement = file

  override fun getContainingFile(): PsiFile = file

  override fun getProject(): Project = file.project

  override fun getManager(): PsiManager = file.manager

  override fun isValid(): Boolean = file.isValid

  override fun getTextOffset(): Int = offset

  override fun getTextRange(): TextRange = TextRange.from(offset, 0)

  override fun getName(): String = label

  override fun getPresentableText(): String = label

  override fun getLocationString(): String = file.name

  override fun navigate(requestFocus: Boolean) {
    val virtualFile = file.virtualFile
    if (virtualFile != null) {
      OpenFileDescriptor(getProject(), virtualFile, offset).navigate(requestFocus)
    }
  }

  override fun canNavigate(): Boolean = file.virtualFile != null

  override fun canNavigateToSource(): Boolean = canNavigate()

  override fun equals(other: Any?): Boolean =
      other is StepTarget && other.file == file && other.offset == offset

  override fun hashCode(): Int = Objects.hash(file, offset)
}
