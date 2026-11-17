pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "editor-testing"
includeBuild("../snapshot-testing") {
  dependencySubstitution {
    substitute(module("dev.kpaths.testing:snapshot-testing")).using(project(":"))
  }
}
