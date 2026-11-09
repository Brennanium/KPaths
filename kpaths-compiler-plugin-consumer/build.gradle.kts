plugins {
  alias(libs.plugins.kotlin.multiplatform)
}

/**
 * Deliberately does not load the compiler plugin.
 *
 * This verifies that the producer module's Kotlin metadata makes a generated `Type.Paths`
 * declaration visible to ordinary downstream Kotlin source.
 */
kotlin {
  jvm()

  sourceSets {
    jvmMain {
      kotlin.srcDir("src/main/kotlin")
      dependencies {
        implementation(project(":kpaths-core"))
        implementation(project(":kpaths-compiler-plugin-fixture"))
      }
    }
    jvmTest {
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.junit.jupiter)
        runtimeOnly("org.junit.platform:junit-platform-launcher")
      }
    }
  }
}

tasks.named<Test>("jvmTest") { useJUnitPlatform() }
