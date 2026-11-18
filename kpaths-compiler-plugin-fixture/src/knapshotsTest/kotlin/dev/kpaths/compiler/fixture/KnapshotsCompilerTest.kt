package dev.kpaths.compiler.fixture

import dev.knapshots.testing.compiler.InlineSnapshotLocation
import dev.knapshots.testing.compiler.SnapshotExpectation
import dev.knapshots.testing.compiler.SnapshotRecording
import dev.knapshots.testing.compiler.assertCompiler
import dev.knapshots.testing.compiler.snapshot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class KnapshotsCompilerTest {
  @Test fun `generates a property path`() {
    assertCompiler("""
      import dev.kpaths.annotations.Pathable
      @Pathable data class Person(val name: String)
      val name = Person.Paths.name
    """) { succeeds() }
  }

  @Test fun `diagnoses an invalid property name`() {
    assertCompiler("""
      import dev.kpaths.annotations.PathName
      import dev.kpaths.annotations.Pathable
      @Pathable data class Person(@PathName("not a name") val name: String)
    """) {
      fails()
      diagnostics { error("@PathName value 'not a name' must be a single Kotlin identifier") }
    }
  }

  @Test fun `compares generated FIR and IR`() {
    assertCompiler(
      source = """
        package fixture
        import dev.kpaths.annotations.Pathable
        @Pathable(copyWritableVals = true) data class Note(val body: String)
        val body = Note.Paths.body
      """,
      fir = SnapshotExpectation(default = "This fallback should not be selected", versions = mapOf("2.4.*" to """
        KPaths resolved FIR callables

        fixture/Note.Paths.body
        public final val body: R|dev/kpaths/core/WritableKeyPath<fixture/Note, kotlin/String>|
            public get(): R|dev/kpaths/core/WritableKeyPath<fixture/Note, kotlin/String>|
      """)),
      ir = SnapshotExpectation("""
        KPaths post-generation IR

        class fixture.Note.Paths
          extends kotlin.Any
          val body: dev.kpaths.core.WritableKeyPath<fixture.Note, kotlin.String>

        class fixture.Note.Paths.__KPaths_body_Path
          extends dev.kpaths.core.WritableKeyPathBase<fixture.Note, kotlin.String>
          val __kpaths_id: dev.kpaths.core.PathId
          val __kpaths_valueType: dev.kpaths.core.TypeId
          fun get(root: fixture.Note): kotlin.String
          fun get(root: kotlin.Any): kotlin.Any
          fun set(root: fixture.Note, value: kotlin.String): fixture.Note
      """),
    )
  }

  @Test fun `discovers and records the calling test source`(@TempDir directory: Path) {
    val root = Path.of(checkNotNull(System.getProperty("kpaths.projectDir")))
    val relative = Path.of("dev/kpaths/compiler/fixture/KnapshotsCompilerTest.kt")
    val copy = directory.resolve("src/knapshotsTest/kotlin").resolve(relative)
    Files.createDirectories(copy.parent)
    Files.copy(root.resolve("kpaths-compiler-plugin-fixture/src/knapshotsTest/kotlin").resolve(relative), copy)
    val previous = System.getProperty("user.dir")
    val previousRecording = System.getProperty("knapshots.compiler.record")
    try {
      System.setProperty("user.dir", directory.toString())
      System.setProperty("knapshots.compiler.record", "missing")
      val failure = assertFailsWith<AssertionError> {
        assertCompiler(
          source = "import dev.kpaths.annotations.Pathable\n@Pathable data class Book(val title: String)\nval title = Book.Paths.title",
          fir = snapshot(),
        )
      }
      assertContains(failure.message.orEmpty(), "review and rerun")
      assertContains(Files.readString(copy), "Book.Paths.title")
      assertContains(Files.readString(copy), "fir = SnapshotExpectation(")
    } finally {
      System.setProperty("user.dir", previous)
      if (previousRecording == null) System.clearProperty("knapshots.compiler.record")
      else System.setProperty("knapshots.compiler.record", previousRecording)
    }
  }

  @Test fun `project defaults record a version override and explicit arguments take precedence`(@TempDir directory: Path) {
    val file = directory.resolve("ExampleTest.kt")
    val original = "fun example() {\n  assertCompiler(source = \"example\", fir = SnapshotExpectation(\"shared\"))\n}\n"
    Files.writeString(file, original)
    val oldRecord = System.getProperty("knapshots.compiler.record")
    val oldVersion = System.getProperty("knapshots.compiler.recordVersion")
    try {
      System.setProperty("knapshots.compiler.record", "missing")
      System.setProperty("knapshots.compiler.recordVersion", "true")
      assertFailsWith<AssertionError> {
        assertCompiler(
          source = "import dev.kpaths.annotations.Pathable\n@Pathable data class Note(val body: String)\nval body = Note.Paths.body",
          fir = SnapshotExpectation("shared"), location = InlineSnapshotLocation(file, 2),
        )
      }
      val recorded = Files.readString(file)
      assertContains(recorded, org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION)
      assertContains(recorded, "Note.Paths.body")
      assertFailsWith<IllegalStateException> {
        assertCompiler(
          source = "import dev.kpaths.annotations.Pathable\n@Pathable data class Note(val body: String)\nval body = Note.Paths.body",
          fir = snapshot(), record = SnapshotRecording.NONE, location = InlineSnapshotLocation(file, 2),
        )
      }
      assertEquals(recorded, Files.readString(file))
    } finally {
      if (oldRecord == null) System.clearProperty("knapshots.compiler.record") else System.setProperty("knapshots.compiler.record", oldRecord)
      if (oldVersion == null) System.clearProperty("knapshots.compiler.recordVersion") else System.setProperty("knapshots.compiler.recordVersion", oldVersion)
    }
  }

  @Test fun `records a missing snapshot for review`(@TempDir directory: Path) {
    val file = directory.resolve("ExampleTest.kt")
    Files.writeString(file, "fun example() {\n  assertCompiler(source = \"example\", fir = snapshot())\n}\n")
    val failure = assertFailsWith<AssertionError> {
      assertCompiler(
        source = "import dev.kpaths.annotations.Pathable\n@Pathable data class Note(val body: String)\nval body = Note.Paths.body",
        fir = snapshot(), record = SnapshotRecording.MISSING,
        location = InlineSnapshotLocation(file, 2),
      )
    }
    assertContains(failure.message.orEmpty(), "review and rerun")
    assertContains(Files.readString(file), "Note.Paths.body")
  }
}
