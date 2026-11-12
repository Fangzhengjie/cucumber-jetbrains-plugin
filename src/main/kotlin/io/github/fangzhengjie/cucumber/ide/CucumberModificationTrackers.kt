package io.github.fangzhengjie.cucumber.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import io.github.fangzhengjie.cucumber.core.IgnoredPaths
import io.github.fangzhengjie.cucumber.core.Language

/**
 * What the step indexes depend on, finer than "any PSI change": [definitions] counts edits of files
 * in a step definition language, [features] edits of `.feature` files, so typing in an unrelated
 * file rebuilds nothing. Each follows what its index reads: step definitions are parsed from
 * document text (document events), feature steps from PSI (PSI events, i.e. after commit). Changes
 * made outside the IDE arrive as VFS events. [files] counts files or directories that could hold
 * them appearing, disappearing, moving or being renamed; unlike
 * [VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS] it ignores build output and other files, so a
 * build writing `target/` doesn't rescan the project.
 */
@Service(Service.Level.PROJECT)
class CucumberModificationTrackers(project: Project) : Disposable {

  val definitions = SimpleModificationTracker()
  val features = SimpleModificationTracker()
  val files = SimpleModificationTracker()
  private val basePath: String? = project.basePath

  init {
    EditorFactory.getInstance()
        .eventMulticaster
        .addDocumentListener(
            object : DocumentListener {
              override fun documentChanged(event: DocumentEvent) {
                val name = FileDocumentManager.getInstance().getFile(event.document)?.name
                if (kind(name) == Kind.DEFINITIONS) {
                  definitions.incModificationCount()
                }
              }
            },
            this)
    PsiManager.getInstance(project)
        .addPsiTreeChangeListener(
            object : PsiTreeChangeAdapter() {
              override fun childrenChanged(event: PsiTreeChangeEvent) = psiChanged(event)

              override fun childAdded(event: PsiTreeChangeEvent) = psiChanged(event)

              override fun childRemoved(event: PsiTreeChangeEvent) = psiChanged(event)

              override fun childReplaced(event: PsiTreeChangeEvent) = psiChanged(event)

              override fun childMoved(event: PsiTreeChangeEvent) = psiChanged(event)
            },
            this)
    ApplicationManager.getApplication()
        .messageBus
        .connect(this)
        .subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
              override fun after(events: List<VFileEvent>) {
                for (event in events) {
                  if (changesFileSet(event)) {
                    files.incModificationCount()
                  }
                  if (!scannedFile(event.path)) {
                    continue
                  }
                  when (kind(event.path.substringAfterLast('/'))) {
                    Kind.DEFINITIONS -> definitions.incModificationCount()
                    Kind.FEATURES -> features.incModificationCount()
                    null -> {}
                  }
                }
              }
            })
  }

  private fun psiChanged(event: PsiTreeChangeEvent) {
    if (kind(event.file?.name) == Kind.FEATURES) {
      features.incModificationCount()
    }
  }

  /**
   * Whether a VFS event adds, removes, moves or renames a feature or step definition file, or a
   * directory that may contain them (build output, dependencies and hidden directories don't).
   */
  private fun changesFileSet(event: VFileEvent): Boolean =
      when (event) {
        is VFileCreateEvent ->
            if (event.isDirectory) scannedDirectory(event.path) else relevantFile(event.path)
        is VFileDeleteEvent ->
            if (event.file.isDirectory) scannedDirectory(event.path) else relevantFile(event.path)
        is VFileMoveEvent ->
            if (event.file.isDirectory)
                scannedDirectory(event.oldPath) || scannedDirectory(event.newPath)
            else relevantFile(event.oldPath) || relevantFile(event.newPath)
        is VFileCopyEvent ->
            if (event.file.isDirectory) scannedDirectory(event.path) else relevantFile(event.path)
        is VFilePropertyChangeEvent ->
            event.propertyName == VirtualFile.PROP_NAME &&
                (if (event.file.isDirectory)
                    scannedDirectory(event.oldPath) || scannedDirectory(event.newPath)
                else relevantFile(event.oldPath) || relevantFile(event.newPath))
        else -> false
      }

  /**
   * A feature or step definition file outside build output, dependencies and hidden directories.
   */
  private fun relevantFile(path: String): Boolean =
      kind(path.substringAfterLast('/')) != null && scannedFile(path)

  private fun scannedFile(path: String): Boolean = !IgnoredPaths.isIgnored(relative(path))

  /** Directories are judged relative to the project directory (see [IgnoredPaths]). */
  private fun scannedDirectory(path: String): Boolean =
      !IgnoredPaths.isIgnoredDirectory(relative(path))

  /**
   * `path` relative to the project directory; outside it, just the last name (file or directory).
   */
  private fun relative(path: String): String {
    val base = basePath
    return if (base != null && path.startsWith("$base/")) path.substring(base.length + 1)
    else path.substringAfterLast('/')
  }

  private enum class Kind {
    DEFINITIONS,
    FEATURES,
  }

  private fun kind(name: String?): Kind? {
    val dot = name?.lastIndexOf('.') ?: -1
    if (dot < 0) {
      return null
    }
    val extension = name!!.substring(dot + 1)
    return when {
      "feature".equals(extension, ignoreCase = true) -> Kind.FEATURES
      Language.forExtension(extension) != null -> Kind.DEFINITIONS
      else -> null
    }
  }

  override fun dispose() {}

  companion object {
    fun getInstance(project: Project): CucumberModificationTrackers =
        project.getService(CucumberModificationTrackers::class.java)
  }
}
