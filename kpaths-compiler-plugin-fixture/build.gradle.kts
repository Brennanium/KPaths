import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import org.gradle.api.tasks.testing.Test

plugins {
  alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
  jvm()
  iosSimulatorArm64()

  sourceSets {
    jvmMain {
      kotlin.srcDir("src/main/kotlin")
      dependencies {
        implementation(project(":kpaths-annotations"))
        implementation(project(":kpaths-core"))
        implementation(project(":kpaths-compiler-plugin-integration-fixture"))
      }
    }

    iosSimulatorArm64Main {
      kotlin.srcDir("src/iosSimulatorArm64Main/kotlin")
      dependencies {
        implementation(project(":kpaths-annotations"))
        implementation(project(":kpaths-core"))
        implementation(project(":kpaths-compiler-plugin-integration-fixture"))
      }
    }

    jvmTest {
      kotlin.srcDir("src/test/kotlin")
      dependencies {
        implementation(kotlin("test"))
        implementation(libs.junit.jupiter)
        implementation(libs.asm)
        implementation("dev.zacsweers.kctfork:core:0.14.0")
        implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
        implementation(project(":kpaths-compiler-plugin"))
        runtimeOnly("org.junit.platform:junit-platform-launcher")
      }
    }
  }
}

dependencies {
  add("jvmTestImplementation", gradleTestKit())
}

tasks.named<Test>("jvmTest") {
  useJUnitPlatform {
    excludeTags("compiler-plugin-contract")
  }
  systemProperty("kpaths.projectDir", rootProject.projectDir.absolutePath)
}

val pathsPluginJar = project(":kpaths-compiler-plugin").tasks.named("jvmJar")
val contributorPluginJar = project(":kpaths-compiler-plugin-contributor").tasks.named("jvmJar")
val pathsPluginFile = project(":kpaths-compiler-plugin")
  .layout
  .buildDirectory
  .file("libs/kpaths-compiler-plugin-jvm-${project.version}.jar")
val contributorPluginFile = project(":kpaths-compiler-plugin-contributor")
  .layout
  .buildDirectory
  .file("libs/kpaths-compiler-plugin-contributor-jvm-${project.version}.jar")

val jvmTest = tasks.named<Test>("jvmTest")
tasks.register<Test>("compilerPluginContractTest") {
  group = "verification"
  description = "Runs downstream compiler-plugin API acceptance contracts."
  dependsOn("compileTestKotlinJvm", pathsPluginJar, contributorPluginJar)
  testClassesDirs = jvmTest.get().testClassesDirs
  classpath = jvmTest.get().classpath
  useJUnitPlatform {
    includeTags("compiler-plugin-contract")
  }
  systemProperty("kpaths.projectDir", rootProject.projectDir.absolutePath)
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
  dependsOn(pathsPluginJar)
  dependsOn(contributorPluginJar)
  inputs.files(pathsPluginFile, contributorPluginFile).withPropertyName("kpathsCompilerPlugins")
  compilerOptions.freeCompilerArgs.addAll(
    listOf(
      "-Xplugin=${contributorPluginFile.get().asFile.absolutePath}",
      "-Xplugin=${pathsPluginFile.get().asFile.absolutePath}",
    ),
  )
}
