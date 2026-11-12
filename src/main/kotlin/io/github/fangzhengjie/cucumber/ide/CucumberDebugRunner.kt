package io.github.fangzhengjie.cucumber.ide

import com.intellij.debugger.impl.GenericDebuggerRunner
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RemoteConnection
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.WrappingRunConfiguration
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor

/**
 * Debugs Cucumber-JVM runs: the IDE debugger listens on a free port and the forked test JVM
 * (Surefire / Gradle `Test`) connects to it. Registered only when the Java plugin is present.
 */
class CucumberDebugRunner : GenericDebuggerRunner() {

  override fun getRunnerId(): String = "CucumberDebugRunner"

  override fun canRun(executorId: String, profile: RunProfile): Boolean {
    if (executorId != DefaultDebugExecutor.EXECUTOR_ID) {
      return false
    }
    val configuration =
        when (profile) {
          is CucumberRunConfiguration -> profile
          is WrappingRunConfiguration<*> -> profile.peer as? CucumberRunConfiguration
          else -> null
        } ?: return false
    return CucumberCommands.isJvm(configuration.resolvedLanguage())
  }

  override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
    val cucumber = state as? CucumberRunState ?: return super.doExecute(state, environment)
    val port = cucumber.debugPort ?: throw ExecutionException("No debugger port was allocated")
    val connection = RemoteConnection(true, "127.0.0.1", port.toString(), true)
    return attachVirtualMachine(state, environment, connection, false)
  }
}
