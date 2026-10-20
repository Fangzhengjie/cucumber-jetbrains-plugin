package com.github.cucumber.jetbrains.plugin

import com.github.cucumber.jetbrains.plugin.SelfTest.equal
import com.github.cucumber.jetbrains.plugin.ide.CucumberProcesses
import com.github.cucumber.jetbrains.plugin.ide.RunCommand
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Windows only: runs a `.bat` wrapper (like `gradlew.bat` or an npm `.cmd` shim, which forward
 * `%*`) through [CucumberProcesses] and checks every argument arrives unchanged.
 */
internal object WindowsArgsTest {

  /** Arguments cmd.exe would otherwise expand, split or re-escape. */
  val TRICKY =
      listOf(
          "plain",
          "with space",
          "x&y",
          "p|q",
          "<lt>gt",
          "(paren)",
          "50%",
          "%PATH%",
          "%CUCUMBER_PLUGIN_PERCENT%",
          "%%",
          "a^b",
          "!bang!",
          "say \"hi\"",
          "trailing\\",
          "back\\\"slash",
          "@smoke and not (@slow or @wip)",
          "Pay & ship: 100% ^done^",
          "中文 ünï",
          "")

  fun run() {
    if (!System.getProperty("os.name").lowercase().startsWith("windows")) {
      return
    }
    val dir = Files.createTempDirectory("cuke args")
    try {
      val java = Path.of(System.getProperty("java.home"), "bin", "java.exe")
      val classpath = System.getProperty("java.class.path")
      val bat = dir.resolve("dump args.bat")
      Files.writeString(
          bat,
          "@echo off\r\n\"$java\" -cp \"$classpath\" ${ArgDump::class.java.name} %*\r\n",
          StandardCharsets.UTF_8)
      // java.exe reads its arguments in the ANSI code page (e.g. 1252 loses CJK) even when started
      // directly, so only arguments it can represent say anything about cmd.exe.
      val ansi = Charset.forName(System.getProperty("sun.jnu.encoding")).newEncoder()
      val tricky = TRICKY.filter { ansi.canEncode(it) }
      val command = RunCommand(listOf(bat.toString()) + tricky, dir.toString(), emptyMap())
      val process = CucumberProcesses.commandLine(command).withRedirectErrorStream(true).createProcess()
      val output = String(process.inputStream.readAllBytes(), StandardCharsets.UTF_8)
      process.waitFor(30, TimeUnit.SECONDS)
      val received = output.lines().filter { it.startsWith("<") }.map { it.removePrefix("<").removeSuffix(">") }
      equal(tricky.size, received.size, "windows .bat: argument count (output:\n$output)")
      for (i in tricky.indices) {
        equal(ArgDump.escape(tricky[i]), received.getOrNull(i), "windows .bat: argument ${tricky[i]}")
      }
    } finally {
      dir.toFile().deleteRecursively()
    }
  }
}
