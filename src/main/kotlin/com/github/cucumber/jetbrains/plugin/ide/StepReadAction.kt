package com.github.cucumber.jetbrains.plugin.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import java.util.concurrent.Callable

/**
 * Runs `block` in a read action off the EDT, under a cancellable modal progress that only shows up
 * when the step indexes have to be rebuilt, and returns its result (null when cancelled).
 *
 * For actions started from the EDT (clicks, menu actions, intentions): computing the indexes
 * there directly would freeze the UI on large projects. The read action yields to write actions
 * and restarts, so it never blocks typing either.
 */
internal fun <T> stepReadAction(project: Project, title: String, block: () -> T): T? =
    try {
      ProgressManager.getInstance()
          .runProcessWithProgressSynchronously(
              ThrowableComputable<T, RuntimeException> {
                ReadAction.nonBlocking(Callable { block() }).expireWith(project).executeSynchronously()
              },
              title,
              true,
              project)
    } catch (cancelled: ProcessCanceledException) {
      null
    }

internal const val STEP_INDEX_PROGRESS = "Indexing Cucumber steps..."
