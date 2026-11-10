pluginManagement {
  val testKotlinVersion = providers.gradleProperty("kpaths.testKotlinVersion").get()
  val kpathsVersion = file("../gradle.properties").readLines()
    .single { it.startsWith("VERSION_NAME=") }.substringAfter('=')
  plugins {
    kotlin("multiplatform") version testKotlinVersion
    id("dev.kpaths.compiler") version kpathsVersion
  }
  repositories {
    maven { url = uri("../build/consumer-verification/repository") }
    gradlePluginPortal()
    mavenCentral()
  }
}
dependencyResolutionManagement {
  repositories {
    maven { url = uri("../build/consumer-verification/repository") }
    mavenCentral()
  }
}
rootProject.name = "kpaths-compiler-compatibility-fixture"
