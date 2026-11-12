package io.github.fangzhengjie.cucumber

/**
 * Prints each argument as `<arg>` on its own line (target of the Windows `.bat` quoting test).
 * Non-ASCII characters are written as `\uXXXX`, so the console code page cannot garble them.
 */
object ArgDump {
  @JvmStatic
  fun main(args: Array<String>) {
    for (arg in args) {
      println("<" + escape(arg) + ">")
    }
  }

  fun escape(text: String): String =
      text.map { if (it.code in 32..126) it.toString() else "\\u%04x".format(it.code) }.joinToString("")
}

/** Entry point of the `windowsArgsTest` Gradle task (needs the IntelliJ Platform on the classpath). */
object WindowsArgsMain {
  @JvmStatic
  fun main(args: Array<String>) {
    WindowsArgsTest.run()
    SelfTest.finish()
  }
}
