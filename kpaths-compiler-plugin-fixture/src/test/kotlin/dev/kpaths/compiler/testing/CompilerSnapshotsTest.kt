package dev.kpaths.compiler.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompilerSnapshotsTest {
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
