package dev.kpaths.ksp

import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.GradleRunner

internal fun compileFixture(
  fixtureDirectory: Path,
  fileName: String,
  source: String,
  expectFailure: Boolean,
  runMain: Boolean = false,
  dependencySource: String? = null,
): String {
  val projectDirectory = Path.of(checkNotNull(System.getProperty("kpaths.projectDir")))
  val annotationsJar = projectDirectory.jvmArtifact("kpaths-annotations")
  val codegenModelJar = projectDirectory.jvmArtifact("kpaths-codegen-model")
  val coreJar = projectDirectory.jvmArtifact("kpaths-core")
  val codegenJar = projectDirectory.jvmArtifact("kpaths-ksp-codegen")
  val processorJar = projectDirectory.jvmArtifact("kpaths-ksp")

  Files.writeString(
    fixtureDirectory.resolve("settings.gradle.kts"),
    """
      pluginManagement {
        repositories {
          gradlePluginPortal()
          mavenCentral()
        }
      }
      dependencyResolutionManagement {
        repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
        repositories {
          mavenCentral()
        }
      }
      rootProject.name = "invalid-kpaths-fixture"
      ${if (dependencySource != null) "include(\":adapters\")" else ""}
    """.trimIndent(),
  )
  Files.writeString(
    fixtureDirectory.resolve("build.gradle.kts"),
    """
      plugins {
        kotlin("jvm") version "2.4.21"
        id("com.google.devtools.ksp") version "2.3.12"
      }

      dependencies {
        implementation(files("${annotationsJar.gradlePath()}"))
        implementation(files("${coreJar.gradlePath()}"))
        ${if (dependencySource != null) "implementation(project(\":adapters\"))" else ""}
        ksp("com.squareup:kotlinpoet:2.2.0")
        ksp(files(
          "${processorJar.gradlePath()}",
          "${codegenJar.gradlePath()}",
          "${codegenModelJar.gradlePath()}",
          "${annotationsJar.gradlePath()}",
        ))
      }

      ${if (runMain) """
      tasks.register<JavaExec>("runFixture") {
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("fixture.DeclarativeAdapterKt")
      }
      """ else ""}
    """.trimIndent(),
  )
  val sourceDirectory = Files.createDirectories(fixtureDirectory.resolve("src/main/kotlin/fixture"))
  Files.writeString(sourceDirectory.resolve(fileName), source)

  if (dependencySource != null) {
    val dependencyDirectory = Files.createDirectories(fixtureDirectory.resolve("adapters/src/main/kotlin/fixture"))
    Files.writeString(dependencyDirectory.resolve("Reader.kt"), dependencySource)
    Files.writeString(fixtureDirectory.resolve("adapters/build.gradle.kts"), """
      plugins { kotlin("jvm") version "2.4.21" }
      dependencies {
        implementation(files("${annotationsJar.gradlePath()}"))
        implementation(files("${coreJar.gradlePath()}"))
      }
    """.trimIndent())
  }

  val runner = GradleRunner.create()
    .withProjectDir(fixtureDirectory.toFile())
    .withArguments(if (runMain) "runFixture" else "compileKotlin", "--stacktrace")
  return (if (expectFailure) runner.buildAndFail() else runner.build()).output
}
