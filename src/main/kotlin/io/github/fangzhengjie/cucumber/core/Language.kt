package io.github.fangzhengjie.cucumber.core

import io.github.fangzhengjie.cucumber.lang.CSharpAdapter
import io.github.fangzhengjie.cucumber.lang.CppAdapter
import io.github.fangzhengjie.cucumber.lang.GoAdapter
import io.github.fangzhengjie.cucumber.lang.JavaScriptAdapter
import io.github.fangzhengjie.cucumber.lang.JvmAdapter
import io.github.fangzhengjie.cucumber.lang.PythonAdapter
import io.github.fangzhengjie.cucumber.lang.RubyAdapter
import io.github.fangzhengjie.cucumber.lang.RustAdapter
import java.util.Locale

/**
 * A language whose step definitions the plugin understands, and the file extensions it owns.
 *
 * @property keywordSensitive whether the framework binds a `Given` definition to `Given` steps
 *   only (behave, pytest-bdd, Reqnroll / SpecFlow, cucumber-rs). Cucumber-JVM, cucumber-js,
 *   cucumber-ruby, godog and Cucumber-CPP ignore the keyword when matching.
 */
enum class Language(
    private val label: String,
    private val fileExtensions: List<String>,
    val keywordSensitive: Boolean = false,
) {
  RUST("Rust (cucumber-rs)", listOf("rs"), keywordSensitive = true),
  GO("Go (godog)", listOf("go")),
  PYTHON("Python (behave, pytest-bdd)", listOf("py"), keywordSensitive = true),
  JAVASCRIPT(
      "JavaScript / TypeScript (cucumber-js)",
      listOf("js", "mjs", "cjs", "jsx", "ts", "mts", "cts", "tsx")),
  JAVA("Java (cucumber-jvm)", listOf("java")),
  KOTLIN("Kotlin (cucumber-jvm)", listOf("kt", "kts")),
  GROOVY("Groovy (cucumber-groovy)", listOf("groovy")),
  SCALA("Scala (Cucumber Scala)", listOf("scala", "sc")),
  CPP("C++ (Cucumber-CPP)", listOf("cpp", "cc", "cxx", "hpp", "hh", "hxx")),
  CSHARP("C# (Reqnroll, SpecFlow)", listOf("cs"), keywordSensitive = true),
  RUBY("Ruby (cucumber-ruby)", listOf("rb"));

  fun displayName(): String = label

  fun extensions(): List<String> = fileExtensions

  fun adapter(): LanguageAdapter =
      when (this) {
        RUST -> RustAdapter
        GO -> GoAdapter
        PYTHON -> PythonAdapter
        JAVASCRIPT -> JavaScriptAdapter
        JAVA -> JvmAdapter.JAVA
        KOTLIN -> JvmAdapter.KOTLIN
        GROOVY -> JvmAdapter.GROOVY
        SCALA -> JvmAdapter.SCALA
        CPP -> CppAdapter
        CSHARP -> CSharpAdapter
        RUBY -> RubyAdapter
      }

  companion object {
    /** The language owning a file extension (without the dot), or null. */
    @JvmStatic
    fun forExtension(extension: String?): Language? {
      if (extension == null) {
        return null
      }
      val lower = extension.lowercase(Locale.ROOT)
      for (language in entries) {
        if (language.fileExtensions.contains(lower)) {
          return language
        }
      }
      return null
    }

    /** The language of a file name such as `steps.rs`, or null. */
    @JvmStatic
    fun forFileName(fileName: String): Language? {
      val dot = fileName.lastIndexOf('.')
      return if (dot < 0) null else forExtension(fileName.substring(dot + 1))
    }
  }
}
