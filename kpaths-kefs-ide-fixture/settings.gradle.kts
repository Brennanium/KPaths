pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "kpaths-kefs-ide-fixture"
includeBuild(providers.gradleProperty("knapshots.checkout").orElse(providers.environmentVariable("KNAPSHOTS_CHECKOUT")).orElse(if (settingsDir.resolve("../.knapshots/settings.gradle.kts").isFile) "../.knapshots" else "../../Knapshots").get())
