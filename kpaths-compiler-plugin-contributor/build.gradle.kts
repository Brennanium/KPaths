import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
}

/**
 * A second compiler plugin used solely to prove KPaths' public contribution API.
 *
 * It deliberately does not depend on KPaths annotations: it asks the KPaths plugin to make an
 * externally owned declaration pathable through the session-scoped request API.
 */
kotlin {
  jvm {
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_11)
      freeCompilerArgs.add("-opt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi")
    }
  }

  sourceSets {
    jvmMain {
      kotlin.srcDir("src/main/kotlin")
      resources.srcDir("src/main/resources")
      dependencies {
        compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
        compileOnly(project(":kpaths-compiler-plugin"))
      }
    }
  }
}
