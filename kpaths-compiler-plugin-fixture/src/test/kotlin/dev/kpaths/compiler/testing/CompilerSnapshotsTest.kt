package dev.kpaths.compiler.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir

class CompilerSnapshotsTest {
  @Test
  fun `missing recording can create a version override without replacing the shared expectation`() {
    val expectation = SnapshotExpectation("shared", mapOf("2.4.20" to "older"))
    assertTrue(shouldRecordSnapshot(expectation, "shared", "2.4.21", SnapshotRecording.MISSING, recordVersion = true))
    assertFalse(shouldRecordSnapshot(expectation, "older", "2.4.20", SnapshotRecording.MISSING, recordVersion = true))
    assertFalse(shouldRecordSnapshot(expectation, "shared", "2.4.21", SnapshotRecording.MISSING, recordVersion = false))
    assertTrue(shouldRecordSnapshot(expectation, "changed", "2.4.21", SnapshotRecording.FAILED, recordVersion = false))
  }
  @Test
  fun `file snapshots record explicitly and select exact version overrides`(@TempDir directory: Path) {
    fun checkSnapshot(version: String, output: String, mode: SnapshotRecording = SnapshotRecording.NONE, versioned: Boolean = false) =
      assertCompilerSnapshotFiles("source", version, directory, "example", setOf(CompilerSnapshotKind.FIR), mode, versioned) { _, _, _ ->
        mapOf(CompilerSnapshotKind.FIR to output)
      }
    assertFailsWith<IllegalStateException> { checkSnapshot("2.4.20", "shared", SnapshotRecording.MISSING) }
    checkSnapshot("2.4.21", "shared")
    assertFailsWith<AssertionError> { checkSnapshot("2.4.21", "changed") }
    assertFailsWith<IllegalStateException> { checkSnapshot("2.4.21", "specific", SnapshotRecording.ALL, versioned = true) }
    checkSnapshot("2.4.21", "specific")
    checkSnapshot("2.4.20", "shared")
    assertEquals("shared\n", Files.readString(directory.resolve("example.fir.txt")))
    assertEquals("specific\n", Files.readString(directory.resolve("example.2.4.21.fir.txt")))
  }

  @Test
  fun `recording missing snapshots does not overwrite existing files`(@TempDir directory: Path) {
    Files.writeString(directory.resolve("example.ir.txt"), "original\n")
    assertFailsWith<AssertionError> {
      assertCompilerSnapshotFiles("source", "2.4.21", directory, "example", setOf(CompilerSnapshotKind.IR), SnapshotRecording.MISSING) { _, _, _ ->
        mapOf(CompilerSnapshotKind.IR to "changed")
      }
    }
    assertEquals("original\n", Files.readString(directory.resolve("example.ir.txt")))
  }

  @Test
  fun `recording never writes an incomplete set of captures`(@TempDir directory: Path) {
    assertFailsWith<IllegalStateException> {
      assertCompilerSnapshotFiles(
        "source", "2.4.21", directory, "example", setOf(CompilerSnapshotKind.FIR, CompilerSnapshotKind.IR),
        SnapshotRecording.ALL,
      ) { _, _, _ -> mapOf(CompilerSnapshotKind.FIR to "only FIR") }
    }
    assertEquals(false, Files.exists(directory.resolve("example.fir.txt")))
  }

  @Test
  fun `exact overrides apply per phase and default applies to other versions`() {
    val expected = mapOf(
      CompilerSnapshotKind.FIR to SnapshotExpectation("shared FIR", mapOf("2.5.0-dev-1" to "preview FIR")),
      CompilerSnapshotKind.IR to SnapshotExpectation("shared IR", mapOf("2.4.20" to "older IR")),
    )
    for ((version, fir, ir) in listOf(
      Triple("2.4.20", "shared FIR", "older IR"),
      Triple("2.4.21", "shared FIR", "shared IR"),
      Triple("2.5.0-dev-1", "preview FIR", "shared IR"),
      Triple("2.5.0-dev-10", "shared FIR", "shared IR"),
    )) {
      assertCompilerSnapshots("  source", version, expected) { source, actualVersion, captures ->
        assertEquals("source", source)
        assertEquals(version, actualVersion)
        assertEquals(expected.keys, captures)
        mapOf(CompilerSnapshotKind.FIR to fir, CompilerSnapshotKind.IR to ir)
      }
    }
  }

  @Test
  fun `a missing capture fails instead of passing silently`() {
    val failure = assertFailsWith<IllegalStateException> {
      assertCompilerSnapshots("source", "2.4.21", mapOf(CompilerSnapshotKind.FIR to SnapshotExpectation("fir"))) { _, _, _ ->
        emptyMap()
      }
    }
    assertEquals("The compiler did not provide the requested FIR snapshot.", failure.message)
  }

  @Test
  fun `incorrect output does not pass an exact assertion`() {
    assertFailsWith<AssertionError> {
      assertCompilerSnapshots("source", "2.4.21", mapOf(CompilerSnapshotKind.IR to SnapshotExpectation("expected"))) { _, _, _ ->
        mapOf(CompilerSnapshotKind.IR to "different")
      }
    }
  }

  @Test
  fun `integrations can supply additional capture kinds`() {
    val custom = CompilerSnapshotKind("CUSTOM")
    assertCompilerSnapshots("source", "2.4.21", mapOf(custom to SnapshotExpectation("custom output"))) { _, _, captures ->
      assertEquals(setOf(custom), captures)
      mapOf(custom to "custom output\n")
    }
  }
}
