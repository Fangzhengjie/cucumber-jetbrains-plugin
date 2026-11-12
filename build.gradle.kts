import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  java
  id("org.jetbrains.kotlin.jvm") version "2.4.20"
  id("org.jetbrains.intellij.platform") version "2.19.0"
  id("com.diffplug.spotless") version "8.11.0"
  id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

group = providers.gradleProperty("pluginGroup").get()

version = providers.gradleProperty("pluginVersion").get()

/** platformLocalPath, else an installed IntelliJ IDEA, else null (download platformVersion). */
val localIde: String? =
    providers.gradleProperty("platformLocalPath").orNull?.takeIf { it.isNotBlank() }
        ?: providers
            .environmentVariable("LOCALAPPDATA")
            .orNull
            ?.let { file("$it/Programs/IntelliJ IDEA") }
            ?.takeIf { it.resolve("product-info.json").isFile }
            ?.absolutePath

repositories {
  maven(providers.gradleProperty("repositoryMaven").get())
  maven(providers.gradleProperty("repositoryIntellij").get())
  maven(providers.gradleProperty("repositoryIntellijDependencies").get())
  intellijPlatform { localPlatformArtifacts() }
}

dependencies {
  intellijPlatform {
    if (localIde != null) {
      local(localIde)
    } else {
      intellijIdea(providers.gradleProperty("platformVersion"))
    }
    // feature: run - test tree (SM runner) and the optional Java debugger integration.
    bundledPlugin("intellij.testRunner.plugin")
    bundledPlugin("com.intellij.java")
  }
  // The IDE provides the Kotlin stdlib to the plugin; SelfTest runs outside the IDE.
  testImplementation(kotlin("stdlib"))
}

java {
  sourceCompatibility = JavaVersion.VERSION_21
  targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
  compilerOptions {
    jvmTarget = JvmTarget.JVM_21
    freeCompilerArgs.add("-Xjdk-release=21")
  }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  compilerOptions.jvmTarget = JvmTarget.JVM_21
}

tasks.withType<JavaCompile>().configureEach {
  options.release = 21
  options.encoding = "UTF-8"
  options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-classfile,-options", "-Werror"))
}

intellijPlatform {
  instrumentCode = false
  buildSearchableOptions = false
  pluginConfiguration {
    version = providers.gradleProperty("pluginVersion")
    ideaVersion {
      sinceBuild = providers.gradleProperty("pluginSinceBuild")
      untilBuild = provider { null }
    }
  }
}

// SelfTest: unit cases, snippet round-trips and the multi-language fixture project.
// Check more projects with: ./gradlew selfTest -PcucumberProjects=C:\src\a,C:\src\b
val selfTest =
    tasks.register<JavaExec>("selfTest") {
      group = "verification"
      description = "Runs the IDE-free SelfTest."
      classpath = sourceSets.test.get().runtimeClasspath
      mainClass = "io.github.fangzhengjie.cucumber.SelfTest"
      args(
          layout.projectDirectory
              .dir("src/test/resources/testdata/multi-language")
              .asFile
              .absolutePath)
      providers
          .gradleProperty("cucumberProjects")
          .orNull
          ?.split(',')
          ?.filter { it.isNotBlank() }
          ?.forEach { args("--project", it.trim()) }
    }

// Windows only: arguments reach a .bat wrapper (gradlew.bat, npm shims) unchanged. Runs the real
// GeneralCommandLine, so the IntelliJ Platform jars (compile classpath) are added.
val windowsArgsTest =
    tasks.register<JavaExec>("windowsArgsTest") {
      group = "verification"
      description = "Checks command-line quoting through cmd.exe on Windows."
      classpath = sourceSets.test.get().runtimeClasspath + sourceSets.test.get().compileClasspath
      mainClass =
          providers
              .gradleProperty("argsMain")
              .orElse("io.github.fangzhengjie.cucumber.WindowsArgsMain")
      onlyIf { System.getProperty("os.name").lowercase().startsWith("windows") }
    }

// Formatting: ./gradlew spotlessApply; `check` runs spotlessCheck. Test fixtures are left as they
// are (the tests rely on their exact text), and GherkinDialectData.kt is generated.
spotless {
  kotlin {
    target("src/**/*.kt")
    targetExclude("src/test/resources/**", "**/GherkinDialectData.kt")
    ktfmt("0.53").metaStyle()
  }
  kotlinGradle {
    target("*.gradle.kts")
    ktfmt("0.53").metaStyle()
  }
}

// Static analysis: ./gradlew detekt (also run by `check`). Findings already in the code are listed
// in
// config/detekt/baseline.xml (./gradlew detektBaseline); only new ones fail the build.
detekt {
  buildUponDefaultConfig = true
  parallel = true
  source.setFrom("src/main/kotlin", "src/test/kotlin")
  baseline = file("config/detekt/baseline.xml")
}

// detekt 1.23 embeds the Kotlin 2.0 compiler; keep its own Kotlin dependencies at that version.
configurations
    .matching { it.name == "detekt" }
    .configureEach {
      resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
          useVersion(io.gitlab.arturbosch.detekt.getSupportedKotlinVersion())
        }
      }
    }

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach { jvmTarget = "21" }

tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
  jvmTarget = "21"
}

tasks {
  // There are no JUnit tests; SelfTest is a plain main class.
  test { enabled = false }
  check { dependsOn(selfTest, windowsArgsTest) }
}
