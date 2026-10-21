package dev.kpaths.ksp

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

class KPathsProcessorIntegrationTest {
  @Test
  fun `reports an actionable error at Pathable for unsupported generic sealed variants`(
    @TempDir fixtureDirectory: Path,
  ) {
    val projectDirectory = Path.of(checkNotNull(System.getProperty("kpaths.projectDir")))
    val annotationsJar = projectDirectory.resolve("kpaths-annotations/build/libs/kpaths-annotations.jar")
    val coreJar = projectDirectory.resolve("kpaths-core/build/libs/kpaths-core.jar")
    val processorJar = projectDirectory.resolve("kpaths-ksp/build/libs/kpaths-ksp.jar")

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
          ksp(files("${processorJar.gradlePath()}", "${annotationsJar.gradlePath()}"))
        }
      """.trimIndent(),
    )
    val sourceDirectory = Files.createDirectories(fixtureDirectory.resolve("src/main/kotlin/fixture"))
    Files.writeString(
      sourceDirectory.resolve("InvalidLoadable.kt"),
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        sealed interface InvalidLoadable<Value> {
          data object Idle : InvalidLoadable<Nothing>
        }
      """.trimIndent(),
    )

    val result = GradleRunner.create()
      .withProjectDir(fixtureDirectory.toFile())
      .withArguments("compileKotlin", "--stacktrace")
      .buildAndFail()

    assertContains(result.output, "InvalidLoadable.kt:")
    assertContains(result.output, "[KPaths] Cannot generate 'Idle'")
    assertContains(result.output, "must forward that type parameter, or refine it to Nothing through an `out` parameter")
  }

  private fun Path.gradlePath(): String = toString().replace("\\", "\\\\")
}
