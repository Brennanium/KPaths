plugins {
  alias(libs.plugins.kotlin.multiplatform)
}

/**
 * A stand-in for a separately published integration library.
 *
 * The consumer fixture uses these binary annotations while only the consumer applies the compiler
 * plugin. This proves KPaths can discover opt-ins and type adapters from dependency metadata.
 */
kotlin {
  jvm()
  iosSimulatorArm64()

  sourceSets {
    commonMain {
      kotlin.srcDir("src/main/kotlin")
      dependencies {
        implementation(project(":kpaths-annotations"))
      }
    }
  }
}
