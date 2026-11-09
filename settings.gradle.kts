pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
  }
}

rootProject.name = "KPaths"

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
)
