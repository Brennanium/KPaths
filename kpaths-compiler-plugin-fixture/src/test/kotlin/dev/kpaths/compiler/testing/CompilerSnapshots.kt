package dev.kpaths.compiler.testing

import kotlin.test.assertEquals

/** Identifies a compiler output to be captured; integrations may define additional kinds. */
internal data class CompilerSnapshotKind(val name: String) {
  companion object {
    val FIR = CompilerSnapshotKind("FIR")
    val IR = CompilerSnapshotKind("IR")
  }
}

/** An inline default and exact compiler-version overrides, with no implicit version ordering. */
internal data class SnapshotExpectation(
  val default: String,
  val versions: Map<String, String> = emptyMap(),
) {
  fun forVersion(compilerVersion: String): String = versions[compilerVersion] ?: default
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
