pluginManagement {
  repositories {
    maven(providers.gradleProperty("repositoryGradlePlugins").get())
    maven(providers.gradleProperty("repositoryMaven").get())
  }
  // Resolve the IntelliJ Platform plugin by its module, so plain Maven mirrors without
  // Gradle plugin markers work too.
  resolutionStrategy {
    eachPlugin {
      if (requested.id.id.startsWith("org.jetbrains.intellij.platform")) {
        useModule(
            "org.jetbrains.intellij.platform:intellij-platform-gradle-plugin:${requested.version}")
      }
    }
  }
}

rootProject.name = "cucumber-jetbrains-plugin"
