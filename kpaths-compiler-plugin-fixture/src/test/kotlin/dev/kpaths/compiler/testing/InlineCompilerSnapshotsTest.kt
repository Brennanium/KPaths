package dev.kpaths.compiler.testing

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class InlineCompilerSnapshotsTest {
  @Test
  fun `ambiguous version ranges fail without modifying the test file`(@TempDir directory: Path) {
    val file = directory.resolve("Ambiguous.kt")
    val original = """
      fun test() {
        assertCompiler(
          source = "source",
          fir = SnapshotExpectation("shared", versions = mapOf("2.5.*" to "one", ">=2.5.0-dev-1" to "two")),
        )
      }
    """.trimIndent()
    Files.writeString(file, original)
    assertFailsWith<IllegalArgumentException> {
      InlineCompilerSnapshots.write(file, 2, mapOf(CompilerSnapshotKind.FIR to "updated"), "2.5.0-dev-10106", false)
    }
    assertEquals(original, Files.readString(file))
  }

  @Test
  fun `recording updates the selected range without changing other overrides`(@TempDir directory: Path) {
    val file = directory.resolve("Range.kt")
    Files.writeString(file, """
      fun test() {
        assertCompiler(
          source = "source",
          fir = SnapshotExpectation("shared", versions = mapOf("2.5.*" to "old", "2.4.20" to "older")),
        )
      }
    """.trimIndent())
    InlineCompilerSnapshots.write(file, 2, mapOf(CompilerSnapshotKind.FIR to "updated"), "2.5.0-dev-10106", false)
    val updated = Files.readString(file)
    assertContains(updated, "\"2.5.*\" to \"\"\"")
    assertContains(updated, "updated")
    assertContains(updated, "\"2.4.20\" to \"older\"")
    assertContains(updated, "\"shared\"")
  }

  @Test
  fun `versioned recording adds exact override without replacing a matching range`(@TempDir directory: Path) {
    val file = directory.resolve("Range.kt")
    Files.writeString(file, """
      fun test() {
        assertCompiler(
          source = "source",
          fir = SnapshotExpectation("shared", versions = mapOf("2.5.*" to "range")),
        )
      }
    """.trimIndent())
    InlineCompilerSnapshots.write(file, 2, mapOf(CompilerSnapshotKind.FIR to "specific"), "2.5.0-dev-10106", true)
    val updated = Files.readString(file)
    assertContains(updated, "\"2.5.*\" to \"range\"")
    assertContains(updated, "\"2.5.0-dev-10106\" to")
    assertContains(updated, "specific")
  }
  @Test
  fun `multiple compiled source locations record safely in either order`(@TempDir directory: Path) {
    val original = """
      fun first() {
        assertCompiler(
          source = "first",
        )
      }
      fun second() {
        assertCompiler(
          source = "second",
        )
      }
    """.trimIndent()
    for ((name, lines) in listOf("forward" to listOf(2, 7), "reverse" to listOf(7, 2))) {
      val file = directory.resolve("$name.kt")
      Files.writeString(file, original)
      for (line in lines) {
        InlineCompilerSnapshots.write(
          file, line, mapOf(CompilerSnapshotKind.FIR to "result for $line\nsecond line"), "2.4.21", false,
          expectedSource = original, compiledSourceLine = true,
        )
      }
      val updated = Files.readString(file)
      val first = updated.indexOf("result for 2")
      val second = updated.indexOf("result for 7")
      assertTrue(first >= 0 && second > first, updated)
    }
  }

  @Test
  fun `concurrent recordings from one compiled file do not lose updates`(@TempDir directory: Path) {
    val original = """
      fun first() {
        assertCompiler(
          source = "first",
        )
      }
      fun second() {
        assertCompiler(
          source = "second",
        )
      }
    """.trimIndent()
    val file = directory.resolve("concurrent.kt")
    Files.writeString(file, original)
    val start = CountDownLatch(1)
    val executor = Executors.newFixedThreadPool(2)
    try {
      val tasks = listOf(2, 7).map { line ->
        executor.submit {
          start.await()
          InlineCompilerSnapshots.write(
            file, line, mapOf(CompilerSnapshotKind.IR to "result for $line"), "2.4.21", false,
            expectedSource = original, compiledSourceLine = true,
          )
        }
      }
      start.countDown()
      tasks.forEach { it.get() }
    } finally {
      executor.shutdownNow()
    }
    val updated = Files.readString(file)
    assertContains(updated, "result for 2")
    assertContains(updated, "result for 7")
  }

  @Test
  fun `external edits after recording are never overwritten`(@TempDir directory: Path) {
    val file = directory.resolve("edited.kt")
    val original = "fun first() {\n  assertCompiler(\n    source = \"first\",\n  )\n}\nfun second() {\n  assertCompiler(\n    source = \"second\",\n  )\n}"
    Files.writeString(file, original)
    InlineCompilerSnapshots.write(
      file, 2, mapOf(CompilerSnapshotKind.FIR to "first"), "2.4.21", false,
      expectedSource = original, compiledSourceLine = true,
    )
    val edited = Files.readString(file) + "\n// user edit\n"
    Files.writeString(file, edited)
    assertFailsWith<IllegalStateException> {
      InlineCompilerSnapshots.write(
        file, 7, mapOf(CompilerSnapshotKind.FIR to "second"), "2.4.21", false,
        expectedSource = original, compiledSourceLine = true,
      )
    }
    assertEquals(edited, Files.readString(file))
  }

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
