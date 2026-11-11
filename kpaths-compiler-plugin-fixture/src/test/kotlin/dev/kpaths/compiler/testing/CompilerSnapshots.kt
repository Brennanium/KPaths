package dev.kpaths.compiler.testing

import kotlin.test.assertEquals
import java.nio.file.Files
import java.nio.file.Path

/** Identifies a compiler output to be captured; integrations may define additional kinds. */
internal data class CompilerSnapshotKind(val name: String) {
  companion object {
    val FIR = CompilerSnapshotKind("FIR")
    val IR = CompilerSnapshotKind("IR")
  }
}

/** An inline default and compiler-version overrides, including explicit ranges. */
internal data class SnapshotExpectation(
  val default: String,
  val versions: Map<String, String> = emptyMap(),
  val missing: Boolean = false,
) {
  fun forVersion(compilerVersion: String): String =
    selectCompilerVersion(versions.keys, compilerVersion)?.let { versions.getValue(it) } ?: default
}

/**
 * Compare named compiler captures using expectations for the compiler actually used to compile.
 * The caller owns compiler setup and capture; this assertion layer has no plugin-specific logic.
 */
internal fun assertCompilerSnapshots(
  source: String,
  compilerVersion: String,
  expected: Map<CompilerSnapshotKind, SnapshotExpectation>,
  compile: (source: String, compilerVersion: String, captures: Set<CompilerSnapshotKind>) -> Map<CompilerSnapshotKind, String>,
) {
  require(expected.isNotEmpty()) { "At least one compiler snapshot must be requested." }
  val actual = compile(source.trimIndent(), compilerVersion, expected.keys)
  expected.forEach { (kind, expectation) ->
    val captured = actual[kind] ?: error("The compiler did not provide the requested ${kind.name} snapshot.")
    assertEquals(
      expectation.forVersion(compilerVersion).trimIndent().trimEnd('\n', '\r'),
      captured.trimEnd('\n', '\r'),
      "${kind.name} snapshot for Kotlin $compilerVersion",
    )
  }
}

/** Explicit recording is file-backed; normal test runs never change snapshots. */
internal enum class SnapshotRecording { NONE, MISSING, FAILED, ALL }

/** Requests a phase whose initial inline expectation will be recorded. */
internal fun snapshot(): SnapshotExpectation = SnapshotExpectation("", missing = true)

internal fun shouldRecordSnapshot(
  expectation: SnapshotExpectation,
  actual: String,
  compilerVersion: String,
  recording: SnapshotRecording,
  recordVersion: Boolean,
): Boolean {
  val differs = expectation.missing ||
    expectation.forVersion(compilerVersion).trimIndent().trimEnd('\n', '\r') != actual.trimEnd('\n', '\r')
  return when (recording) {
    SnapshotRecording.NONE -> false
    SnapshotRecording.MISSING -> expectation.missing || (recordVersion && compilerVersion !in expectation.versions)
    SnapshotRecording.FAILED -> differs
    SnapshotRecording.ALL -> true
  }
}

internal fun assertCompilerSnapshotFiles(
  source: String,
  compilerVersion: String,
  directory: Path,
  name: String,
  kinds: Set<CompilerSnapshotKind>,
  recording: SnapshotRecording = SnapshotRecording.NONE,
  recordVersion: Boolean = false,
  compile: (String, String, Set<CompilerSnapshotKind>) -> Map<CompilerSnapshotKind, String>,
) {
  require(kinds.isNotEmpty()) { "At least one compiler snapshot must be requested." }
  require(name.matches(Regex("[A-Za-z0-9_-]+"))) { "Snapshot name must be a simple file name." }
  require(compilerVersion.matches(Regex("[A-Za-z0-9_.-]+"))) { "Invalid compiler version." }
  val actual = compile(source.trimIndent(), compilerVersion, kinds)
  // Validate every capture before writing anything, so a broken compiler invocation cannot
  // leave a partially recorded set of expectations.
  val captures = kinds.associateWith { kind ->
    require(kind.name.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid snapshot kind." }
    actual[kind] ?: error("The compiler did not provide the requested ${kind.name} snapshot.")
  }
  val recorded = mutableListOf<Path>()
  captures.forEach { (kind, output) ->
    val extension = kind.name.lowercase()
    val shared = directory.resolve("$name.$extension.txt")
    val versioned = directory.resolve("$name.$compilerVersion.$extension.txt")
    val selected = if (Files.exists(versioned)) versioned else shared
    val destination = if (recordVersion) versioned else selected
    val shouldRecord = recording == SnapshotRecording.ALL ||
      (recording == SnapshotRecording.MISSING && !Files.exists(destination)) ||
      (recording == SnapshotRecording.FAILED &&
        (!Files.isRegularFile(destination) || Files.readString(destination).trimEnd('\n', '\r') != output.trimEnd('\n', '\r')))
    if (shouldRecord) {
      Files.createDirectories(directory)
      Files.writeString(destination, output.trimEnd('\n', '\r') + "\n")
      recorded.add(destination)
    } else {
      check(Files.isRegularFile(selected)) { "Missing ${kind.name} snapshot: $selected" }
      assertEquals(Files.readString(selected).trimEnd('\n', '\r'), output.trimEnd('\n', '\r'),
        "${kind.name} snapshot for Kotlin $compilerVersion ($selected)")
    }
  }
  check(recorded.isEmpty()) { "Recorded compiler snapshots; review them and rerun the tests: ${recorded.joinToString()}" }
}
