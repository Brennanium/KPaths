package dev.kpaths.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class KPathsGradlePluginTest {
  @Test
  fun `plugin supplies compiler artifact to a downstream JVM build`(@TempDir directory: Path) {
    val project = Path.of(checkNotNull(System.getProperty("kpaths.projectDir")))
    val version = checkNotNull(System.getProperty("kpaths.version"))
    val kotlinVersion = checkNotNull(System.getProperty("kpaths.kotlinVersion"))
    val repo = directory.resolve("repo")
    val module = repo.resolve("io/github/brennanium/kpaths-compiler-plugin-jvm/$version")
    Files.createDirectories(module)
    Files.copy(project.resolve("kpaths-compiler-plugin/build/libs/kpaths-compiler-plugin-jvm-$version.jar"),
      module.resolve("kpaths-compiler-plugin-jvm-$version.jar"))
    Files.writeString(module.resolve("kpaths-compiler-plugin-jvm-$version.pom"), """
      <project><modelVersion>4.0.0</modelVersion><groupId>io.github.brennanium</groupId>
      <artifactId>kpaths-compiler-plugin-jvm</artifactId><version>$version</version></project>
    """.trimIndent())
    val gradleModule = repo.resolve("io/github/brennanium/kpaths-gradle-plugin/$version")
    Files.createDirectories(gradleModule)
    Files.copy(project.resolve("kpaths-gradle-plugin/build/libs/kpaths-gradle-plugin-$version.jar"),
      gradleModule.resolve("kpaths-gradle-plugin-$version.jar"))
    Files.writeString(gradleModule.resolve("kpaths-gradle-plugin-$version.pom"), """
      <project><modelVersion>4.0.0</modelVersion><groupId>io.github.brennanium</groupId>
      <artifactId>kpaths-gradle-plugin</artifactId><version>$version</version>
      <dependencies><dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-gradle-plugin-api</artifactId>
      <version>$kotlinVersion</version></dependency></dependencies></project>
    """.trimIndent())
    val marker = repo.resolve("dev/kpaths/compiler/dev.kpaths.compiler.gradle.plugin/$version")
    Files.createDirectories(marker)
    Files.writeString(marker.resolve("dev.kpaths.compiler.gradle.plugin-$version.pom"), """
      <project><modelVersion>4.0.0</modelVersion><groupId>dev.kpaths.compiler</groupId>
      <artifactId>dev.kpaths.compiler.gradle.plugin</artifactId><version>$version</version><packaging>pom</packaging>
      <dependencies><dependency><groupId>io.github.brennanium</groupId><artifactId>kpaths-gradle-plugin</artifactId>
      <version>$version</version></dependency></dependencies></project>
    """.trimIndent())
    Files.writeString(directory.resolve("settings.gradle.kts"), """
      pluginManagement { repositories { maven { url = uri("${repo.toGradlePath()}") }; gradlePluginPortal(); mavenCentral() } }
      dependencyResolutionManagement { repositories { maven { url = uri("${repo.toGradlePath()}") }; mavenCentral() } }
      rootProject.name = "kpaths-gradle-consumer"
    """.trimIndent())
    val core = project.resolve("kpaths-core/build/libs/kpaths-core-jvm-$version.jar")
    val annotations = project.resolve("kpaths-annotations/build/libs/kpaths-annotations-jvm-$version.jar")
    Files.writeString(directory.resolve("build.gradle.kts"), """
      plugins { kotlin("jvm") version "$kotlinVersion"; id("dev.kpaths.compiler") version "$version" }
      dependencies { implementation(files("${core.toGradlePath()}", "${annotations.toGradlePath()}")) }
      tasks.register<JavaExec>("runFixture") {
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("fixture.MainKt")
      }
    """.trimIndent())
    val source = Files.createDirectories(directory.resolve("src/main/kotlin/fixture"))
    Files.writeString(source.resolve("Main.kt"), """
      package fixture
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.*
      @Pathable data class Person(var name: String)
      @Pathable sealed interface Event { data class Text(val value: String) : Event }
      fun main() {
        val path: WritableKeyPath<Person, String> = Person.Paths.name
        check(path.set(Person("before"), "after") == Person("after"))
        check(Event.Paths.Text.value.embed("hello") == Event.Text("hello"))
        println("KPATHS_GRADLE_PLUGIN_OK")
      }
    """.trimIndent())
    fun run() = GradleRunner.create().withProjectDir(directory.toFile())
      .withArguments("runFixture", "--configuration-cache", "--stacktrace").build()
    val first = run()
    assertContains(first.output, "KPATHS_GRADLE_PLUGIN_OK")
    val second = run()
    assertContains(second.output, "Configuration cache entry reused")
    assertEquals("dev.kpaths.compiler", KPathsGradlePlugin().getCompilerPluginId())
  }

  private fun Path.toGradlePath(): String = toAbsolutePath().toString().replace("\\", "\\\\")
}
