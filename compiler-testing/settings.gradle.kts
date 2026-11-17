pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "compiler-testing"
includeBuild("../snapshot-testing") {
  dependencySubstitution {
    substitute(module("dev.kpaths.testing:snapshot-testing")).using(project(":"))
  }
}
