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
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "InvalidLoadable.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        sealed interface InvalidLoadable<Value> {
          data object Idle : InvalidLoadable<Nothing>
        }
      """.trimIndent(),
    )

    assertContains(output, "InvalidLoadable.kt:")
    assertContains(output, "[KPaths] Cannot generate 'Idle'")
    assertContains(output, "must forward that type parameter, or refine it to Nothing through an `out` parameter")
  }

  @Test
  fun `reports use of KPaths reserved internal prefix at Pathable`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "ReservedPrefix.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        data class ReservedPrefix(val __kpaths_rootType: String)
      """.trimIndent(),
    )

    assertContains(output, "ReservedPrefix.kt:")
    assertContains(output, "[KPaths] Cannot generate paths because property '__kpaths_rootType'")
    assertContains(output, "uses the reserved '__kpaths_' prefix")
  }

  @Test
  fun `reports property and case path collisions at Pathable`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "PropertyCaseCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        sealed class Connection {
          abstract val Connected: String

          data class Connected(override val Connected: String) : Connection()
        }
      """.trimIndent(),
    )

    assertContains(output, "PropertyCaseCollision.kt:")
    assertContains(output, "[KPaths] Cannot generate paths because 'Connected' would name both")
    assertContains(output, "the key path for property 'Connected' and the case path for 'Connected'")
  }

  @Test
  fun `reports top level generated namespace collisions at Pathable`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "TopLevelCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        data class Person(val name: String)

        object PersonPaths
      """.trimIndent(),
    )

    assertContains(output, "TopLevelCollision.kt:")
    assertContains(output, "[KPaths] Cannot generate 'PersonPaths' because it conflicts with existing top-level")
    assertContains(output, "type 'fixture.PersonPaths'")
    assertContains(output, "@Pathable(\"CustomPersonPaths\")")
  }

  @Test
  fun `reports duplicate custom generated namespaces at Pathable`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "DuplicateCustomNames.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable("SharedPaths")
        data class First(val value: String)

        @Pathable("SharedPaths")
        data class Second(val value: Int)
      """.trimIndent(),
    )

    assertContains(output, "DuplicateCustomNames.kt:")
    assertContains(output, "[KPaths] Cannot generate 'SharedPaths' because @Pathable also requests that name")
    assertContains(output, "'fixture.First'")
    assertContains(output, "'fixture.Second'")
  }

  @Test
  fun `reports top level type alias collisions at Pathable`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "TypeAliasCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        data class Person(val name: String)

        typealias PersonPaths = String
      """.trimIndent(),
    )

    assertContains(output, "TypeAliasCollision.kt:")
    assertContains(output, "[KPaths] Cannot generate 'PersonPaths' because it conflicts with existing top-level")
    assertContains(output, "type alias 'fixture.PersonPaths'")
  }

  @Test
  fun `reports reserved prefixes on sealed cases at Pathable`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "ReservedCasePrefix.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        sealed interface ReservedCasePrefix {
          data object __kpaths_rootType : ReservedCasePrefix
        }
      """.trimIndent(),
    )

    assertContains(output, "ReservedCasePrefix.kt:")
    assertContains(output, "[KPaths] Cannot generate paths because case '__kpaths_rootType'")
    assertContains(output, "uses the reserved '__kpaths_' prefix")
  }

  @Test
  fun `reports generic companion collisions at Pathable`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "CompanionCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        data class Box<Value>(val Companion: Value)
      """.trimIndent(),
    )

    assertContains(output, "CompanionCollision.kt:")
    assertContains(output, "[KPaths] Cannot generate paths because 'Companion' would name both")
    assertContains(output, "the generated generic-path companion and the key path for property 'Companion'")
  }

  @Test
  fun `reports generated source file collisions at Pathable`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "PersonPaths.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        data class Person(val name: String)
      """.trimIndent(),
    )

    assertContains(output, "PersonPaths.kt:")
    assertContains(output, "[KPaths] Cannot generate 'PersonPaths.kt' because source file")
    assertContains(output, "@Pathable(\"CustomPersonPaths\")")
  }

  private fun compileFailure(
    fixtureDirectory: Path,
    fileName: String,
    source: String,
  ): String {
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
    Files.writeString(sourceDirectory.resolve(fileName), source)

    return GradleRunner.create()
      .withProjectDir(fixtureDirectory.toFile())
      .withArguments("compileKotlin", "--stacktrace")
      .buildAndFail()
      .output
  }

  private fun Path.gradlePath(): String = toString().replace("\\", "\\\\")
}
