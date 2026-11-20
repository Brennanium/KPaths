pluginManagement {
  includeBuild(providers.gradleProperty("knapshots.checkout").orElse(providers.environmentVariable("KNAPSHOTS_CHECKOUT")).orElse(if (settingsDir.resolve(".knapshots/settings.gradle.kts").isFile) ".knapshots" else "../Knapshots").get())
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    if (providers.gradleProperty("kpaths.testKotlinVersion").orNull?.contains("-dev-") == true) {
      listOf("dev", "experimental", "bootstrap").forEach { channel ->
        maven("https://packages.jetbrains.team/maven/p/kt/$channel/") {
          content { includeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?") }
        }
      }
    }
    mavenCentral()
  }
}

rootProject.name = "KPaths"

// Use a local Knapshots checkout while the testing toolkit is in development.
includeBuild(providers.gradleProperty("knapshots.checkout").orElse(providers.environmentVariable("KNAPSHOTS_CHECKOUT")).orElse(if (settingsDir.resolve(".knapshots/settings.gradle.kts").isFile) ".knapshots" else "../Knapshots").get())

include(
  ":kpaths-annotations",
  ":kpaths-core",
  ":kpaths-jvm",
  ":kpaths-native",
  ":kpaths-reflection-jvm",
  ":kpaths-codegen-model",
  ":kpaths-ksp-codegen",
  ":kpaths-ksp",
  ":kpaths-sample",
  ":kpaths-compiler-plugin",
  ":kpaths-gradle-plugin",
  ":kpaths-compiler-plugin-contributor",
  ":kpaths-compiler-plugin-integration-fixture",
  ":kpaths-compiler-plugin-fixture",
  ":kpaths-compiler-plugin-consumer",
  ":kpaths-editor-fixture",
  ":kpaths-compatibility",
)
