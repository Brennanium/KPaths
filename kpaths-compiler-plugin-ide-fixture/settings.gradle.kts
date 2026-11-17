pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "kpaths-compiler-plugin-ide-fixture"
includeBuild("../editor-testing") {
  dependencySubstitution {
    substitute(module("dev.kpaths.testing:editor-testing")).using(project(":"))
  }
}
