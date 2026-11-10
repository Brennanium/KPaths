pluginManagement {
  repositories {
    maven { url = uri("../../build/consumer-verification/repository") }
    gradlePluginPortal()
    mavenCentral()
  }
}
dependencyResolutionManagement {
  repositories {
    maven { url = uri("../../build/consumer-verification/repository") }
    mavenCentral()
  }
}
rootProject.name = "kpaths-kefs-consumer"
