import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  `maven-publish`
}

kotlin {
  jvm {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
  }

  sourceSets {
    jvmMain {
      kotlin.srcDir("src/main/kotlin")
      resources.srcDir("src/main/resources")
      dependencies {
        implementation(project(":kpaths-annotations"))
        implementation(project(":kpaths-core"))
        implementation(libs.ksp.api)
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

dependencies {
  add("jvmTestImplementation", gradleTestKit())
}

tasks.named<Test>("jvmTest") {
  dependsOn(":kpaths-annotations:jvmJar", ":kpaths-core:jvmJar", ":kpaths-ksp:jvmJar")
  systemProperty("kpaths.projectDir", rootProject.projectDir.absolutePath)
  useJUnitPlatform()
}
