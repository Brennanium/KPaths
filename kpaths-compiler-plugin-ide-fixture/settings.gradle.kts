pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "kpaths-compiler-plugin-ide-fixture"
val knapshots = providers.gradleProperty("knapshots.checkout").orElse(providers.environmentVariable("KNAPSHOTS_CHECKOUT")).orElse(if (settingsDir.resolve("../.knapshots/settings.gradle.kts").isFile) "../.knapshots" else "../../Knapshots").get()
includeBuild(knapshots)
includeBuild(file(knapshots).resolve("knapshots-intellij-fixture"))
