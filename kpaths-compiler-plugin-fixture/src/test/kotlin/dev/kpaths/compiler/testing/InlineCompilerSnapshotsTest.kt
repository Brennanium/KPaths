package dev.kpaths.compiler.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class InlineCompilerSnapshotsTest {
  @Test
  fun `records both missing phases and preserves unrelated source`(@TempDir directory: Path) {
    val file = directory.resolve("Example.kt")
    val source = ("""
      fun test() {
        assertCompiler(
          directory = temp,
          source = """ + "\"\"\"" + """
            val text = "assertCompiler(irrelevant)"
          """ + "\"\"\"" + """,
        )
      }
    """).trimIndent()
    Files.writeString(file, source)
    InlineCompilerSnapshots.write(file, 2, mapOf(CompilerSnapshotKind.FIR to "fir output", CompilerSnapshotKind.IR to "ir output"), "2.4.21", false)
    val updated = Files.readString(file)
    assertContains(updated, "fir = SnapshotExpectation(")
    assertContains(updated, "ir = SnapshotExpectation(")
    assertContains(updated, "fir output")
    assertContains(updated, "ir output")
    assertContains(updated, "assertCompiler(irrelevant)")
  }

  @Test
  fun `records specific version while preserving other versions and default`(@TempDir directory: Path) {
    val file = directory.resolve("Example.kt")
    Files.writeString(file, """
      fun test() {
        assertCompiler(
          source = "source",
          fir = SnapshotExpectation("shared", versions = mapOf("2.4.20" to "older", "2.4.21" to "old")),
        )
      }
    """.trimIndent())
    InlineCompilerSnapshots.write(file, 2, mapOf(CompilerSnapshotKind.FIR to "new"), "2.4.21", false)
    val updated = Files.readString(file)
    assertContains(updated, "\"shared\"")
    assertContains(updated, "\"2.4.20\" to \"older\"")
    assertContains(updated, "\"2.4.21\" to \"\"\"")
    assertContains(updated, "new")
  }

  @Test
  fun `adds a version override and safely encodes dollar signs`(@TempDir directory: Path) {
    val file = directory.resolve("Example.kt")
    Files.writeString(file, """
      fun test() {
        assertCompiler(
          source = "source",
          ir = SnapshotExpectation("shared"),
        )
      }
    """.trimIndent())
    InlineCompilerSnapshots.write(file, 2, mapOf(CompilerSnapshotKind.IR to "class Example${'$'}Nested"), "2.4.20", true)
    val updated = Files.readString(file)
    assertContains(updated, "versions = mapOf(\"2.4.20\" to")
    assertContains(updated, "class Example${'$'}{'${'$'}'}Nested")
    assertContains(updated, "\"shared\"")
  }

  @Test
  fun `replaces only the selected missing phase`(@TempDir directory: Path) {
    val file = directory.resolve("Example.kt")
    Files.writeString(file, """
      fun test() {
        assertCompiler(
          source = "source",
          fir = snapshot(),
        )
      }
    """.trimIndent())
    InlineCompilerSnapshots.write(file, 2, mapOf(CompilerSnapshotKind.FIR to "fir output"), "2.4.21", false)
    val updated = Files.readString(file)
    assertContains(updated, "fir = SnapshotExpectation(")
    assertEquals(false, "\n    ir =" in updated)
  }

  @Test
  fun `refuses unsupported expectation without changing source`(@TempDir directory: Path) {
    val file = directory.resolve("Example.kt")
    val original = "fun test() { assertCompiler(source = \"source\", fir = expected) }"
    Files.writeString(file, original)
    assertFailsWith<IllegalStateException> {
      InlineCompilerSnapshots.write(file, 1, mapOf(CompilerSnapshotKind.FIR to "output"), "2.4.21", false)
    }
    assertEquals(original, Files.readString(file))
  }

  @Test
  fun `refuses ambiguous calls on the same line`(@TempDir directory: Path) {
    val file = directory.resolve("Example.kt")
    val original = "fun test() { assertCompiler(source = \"a\"); assertCompiler(source = \"b\") }"
    Files.writeString(file, original)
    assertFailsWith<IllegalArgumentException> {
      InlineCompilerSnapshots.write(file, 1, mapOf(CompilerSnapshotKind.FIR to "output"), "2.4.21", false)
    }
    assertEquals(original, Files.readString(file))
  }
}
