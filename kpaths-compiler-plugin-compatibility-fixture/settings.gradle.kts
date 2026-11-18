pluginManagement {
  val testKotlinVersion = providers.gradleProperty("kpaths.testKotlinVersion").get()
  val kpathsVersion = file("../gradle.properties").readLines()
    .single { it.startsWith("VERSION_NAME=") }.substringAfter('=')
  plugins {
    kotlin("multiplatform") version testKotlinVersion
    id("dev.kpaths.gradle-plugin") version kpathsVersion
  }
  if ("-dev-" in testKotlinVersion) {
    resolutionStrategy.eachPlugin {
      if (requested.id.id == "org.jetbrains.kotlin.multiplatform") {
        useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:$testKotlinVersion")
      }
    }
  }
  repositories {
    maven { url = uri("../build/consumer-verification/repository") }
    if ("-dev-" in testKotlinVersion) {
      listOf("dev", "experimental", "bootstrap").forEach { channel ->
        maven("https://packages.jetbrains.team/maven/p/kt/$channel/")
      }
    }
    gradlePluginPortal()
    mavenCentral()
  }
}
dependencyResolutionManagement {
  repositories {
    maven { url = uri("../build/consumer-verification/repository") }
    if ("-dev-" in providers.gradleProperty("kpaths.testKotlinVersion").get()) {
      listOf("dev", "experimental", "bootstrap").forEach { channel ->
        maven("https://packages.jetbrains.team/maven/p/kt/$channel/")
      }
    }
    mavenCentral()
  }
}
rootProject.name = "kpaths-compiler-compatibility-fixture"
