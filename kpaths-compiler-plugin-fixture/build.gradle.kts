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
        api(project(":kpaths-compiler-plugin-integration-fixture"))
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

    iosSimulatorArm64Test {
      dependencies {
        implementation(kotlin("test"))
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
        implementation(project(":kpaths-compiler-plugin-contributor"))
        runtimeOnly("org.junit.platform:junit-platform-launcher")
      }
    }
  }
}

dependencies {
  add("jvmTestImplementation", gradleTestKit())
}

val snapshotRecording = providers.gradleProperty("kpaths.snapshotRecording")
  .orElse(providers.environmentVariable("CI").map { if (it.isNotBlank() && !it.equals("false", ignoreCase = true)) "none" else "missing" })
  .orElse("missing")

tasks.named<Test>("jvmTest") {
  systemProperty("idea.home.path", rootProject.projectDir.absolutePath)
  useJUnitPlatform {
    excludeTags("compiler-plugin-contract")
  }
  systemProperty("kpaths.projectDir", rootProject.projectDir.absolutePath)
  systemProperty("kpaths.testKotlinVersion", providers.gradleProperty("kpaths.testKotlinVersion").orElse(libs.versions.kotlin).get())
  systemProperty("kpaths.snapshotRecording", snapshotRecording.get())
  systemProperty("kpaths.snapshotRecordVersion", providers.gradleProperty("kpaths.snapshotRecordVersion").orElse("false").get())
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
  systemProperty("idea.home.path", rootProject.projectDir.absolutePath)
  group = "verification"
  description = "Runs downstream compiler-plugin API acceptance contracts."
  dependsOn("compileTestKotlinJvm", pathsPluginJar, contributorPluginJar)
  inputs.dir(layout.projectDirectory.dir("src/test/snapshots"))
  testClassesDirs = jvmTest.get().testClassesDirs
  classpath = jvmTest.get().classpath
  useJUnitPlatform {
    includeTags("compiler-plugin-contract")
  }
  systemProperty("kpaths.projectDir", rootProject.projectDir.absolutePath)
  systemProperty("kpaths.testKotlinVersion", providers.gradleProperty("kpaths.testKotlinVersion").orElse(libs.versions.kotlin).get())
  systemProperty("kpaths.snapshotRecording", snapshotRecording.get())
  systemProperty("kpaths.snapshotRecordVersion", providers.gradleProperty("kpaths.snapshotRecordVersion").orElse("false").get())
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
