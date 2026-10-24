import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  `maven-publish`
}

kotlin {
  compilerOptions {
    freeCompilerArgs.add("-Xexpect-actual-classes")
  }

  jvm {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
  }
  iosArm64()
  iosSimulatorArm64()

  sourceSets {
    commonMain {
      kotlin.srcDir("src/main/kotlin")
    }
    jvmMain {
      dependencies {
        implementation(kotlin("reflect"))
      }
    }
    jvmTest {
      kotlin.srcDir("src/test/kotlin")
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.junit.jupiter)
        runtimeOnly("org.junit.platform:junit-platform-launcher")
      }
    }
  }
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
}
