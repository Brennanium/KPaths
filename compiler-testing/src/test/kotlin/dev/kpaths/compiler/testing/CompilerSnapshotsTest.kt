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
  fun `version selectors support wildcards comparisons and bounded ranges`() {
    val cases = listOf(
      Triple("2.5.*", "2.5.0-dev-42", true),
      Triple("2.5.*", "2.5.4", true),
      Triple("2.5.*", "2.6.0", false),
      Triple(">=2.5.0", "2.5.0-dev-42", false),
      Triple(">=2.5.0", "2.5.0", true),
      Triple("<2.5.0", "2.5.0-dev-42", true),
      Triple(">=2.4.20 <2.5.0", "2.4.21", true),
      Triple(">=2.4.20 <2.5.0", "2.5.0", false),
      Triple(">=2.5.0-dev-99", "2.5.0-dev-100", true),
      Triple("<=2.4.21", "2.4.21", true),
      Triple(">2.4.21", "2.4.21", false),
    )
    cases.forEach { (selector, version, matches) ->
      assertEquals(if (matches) selector else null, selectCompilerVersion(setOf(selector), version), "$selector for $version")
    }
  }

  @Test
  fun `exact overrides win over ranges and overlapping ranges are rejected`() {
    val versions = mapOf("2.5.*" to "wildcard", ">=2.5.0-dev-1" to "range", "2.5.0" to "exact")
    assertEquals("exact", SnapshotExpectation("default", versions).forVersion("2.5.0"))
    assertFailsWith<IllegalArgumentException> { SnapshotExpectation("default", versions).forVersion("2.5.0-dev-43") }
    assertFailsWith<IllegalArgumentException> { selectCompilerVersion(setOf("2.5.x"), "2.5.0") }
  }

  @Test
  fun `compiler assertions use the selected version range independently for each phase`() {
    val expected = mapOf(
      CompilerSnapshotKind.FIR to SnapshotExpectation("default fir", mapOf("2.5.*" to "preview fir")),
      CompilerSnapshotKind.IR to SnapshotExpectation("default ir", mapOf(">=2.5.0" to "released ir")),
    )
    assertCompilerSnapshots("source", "2.5.0-dev-42", expected) { _, _, _ ->
      mapOf(CompilerSnapshotKind.FIR to "preview fir", CompilerSnapshotKind.IR to "default ir")
    }
    assertCompilerSnapshots("source", "2.5.0", expected) { _, _, _ ->
      mapOf(CompilerSnapshotKind.FIR to "preview fir", CompilerSnapshotKind.IR to "released ir")
    }
  }

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
  fun `version-family overrides apply per phase and default applies to other versions`() {
    val expected = mapOf(
      CompilerSnapshotKind.FIR to SnapshotExpectation("shared FIR", mapOf("2.5.*" to "2.5 FIR")),
      CompilerSnapshotKind.IR to SnapshotExpectation("shared IR", mapOf("2.4.20" to "older IR")),
    )
    for ((version, fir, ir) in listOf(
      Triple("2.4.20", "shared FIR", "older IR"),
      Triple("2.4.21", "shared FIR", "shared IR"),
      Triple("2.5.0-dev-1", "2.5 FIR", "shared IR"),
      Triple("2.5.0-dev-10", "2.5 FIR", "shared IR"),
      Triple("2.5.0", "2.5 FIR", "shared IR"),
      Triple("2.6.0", "shared FIR", "shared IR"),
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
