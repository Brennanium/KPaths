pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "kpaths-compiler-plugin-ide-fixture"
includeBuild(providers.gradleProperty("knapshots.checkout").orElse("../../Knapshots").get())
