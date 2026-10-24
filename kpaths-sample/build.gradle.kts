import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.ksp)
}

kotlin {
  jvm {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
  }
  iosArm64()
  iosSimulatorArm64()

  sourceSets {
    commonMain {
      kotlin.srcDir("src/main/kotlin")
      dependencies {
        implementation(project(":kpaths-annotations"))
        implementation(project(":kpaths-core"))
      }
    }
    commonTest {
      kotlin.srcDir("src/test/kotlin")
      dependencies {
        implementation(kotlin("test"))
      }
    }
    jvmTest {
      dependencies {
        implementation(libs.junit.jupiter)
        runtimeOnly("org.junit.platform:junit-platform-launcher")
      }
    }
  }
}

dependencies {
  add("kspJvm", project(":kpaths-ksp"))
  add("kspIosArm64", project(":kpaths-ksp"))
  add("kspIosSimulatorArm64", project(":kpaths-ksp"))
}

tasks.named<Test>("jvmTest") {
  useJUnitPlatform()
}
