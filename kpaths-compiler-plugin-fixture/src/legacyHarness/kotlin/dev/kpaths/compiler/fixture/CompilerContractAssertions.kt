package dev.kpaths.compiler.fixture

import dev.kpaths.compiler.testing.CompilerSnapshotKind
import dev.kpaths.compiler.testing.assertCompilerSnapshots
import dev.kpaths.compiler.testing.assertCompilerSnapshotFiles
import dev.kpaths.compiler.testing.InlineCompilerSnapshots
import dev.kpaths.compiler.testing.snapshot
import dev.kpaths.compiler.testing.shouldRecordSnapshot
import java.nio.file.Files
import java.nio.file.Path

internal typealias SnapshotExpectation = dev.kpaths.compiler.testing.SnapshotExpectation
internal typealias SnapshotRecording = dev.kpaths.compiler.testing.SnapshotRecording

/** KPaths fixture adapter; snapshot selection and comparison remain compiler-independent. */
internal fun FutureCompilerPluginContractTest.assertRecordedCompiler(directory: Path, name: String, source: String) {
  val project = Path.of(checkNotNull(System.getProperty("kpaths.projectDir")))
  val version = checkNotNull(System.getProperty("kpaths.testKotlinVersion"))
  val recording = SnapshotRecording.valueOf(System.getProperty("kpaths.snapshotRecording", "none").uppercase())
  assertCompilerSnapshotFiles(
    source, version,
    project.resolve("kpaths-compiler-plugin-fixture/src/test/snapshots"), name,
    setOf(CompilerSnapshotKind.FIR, CompilerSnapshotKind.IR), recording,
    System.getProperty("kpaths.snapshotRecordVersion", "false").toBooleanStrict(),
  ) { input, compilerVersion, _ ->
    val firPath = directory.resolve("kpaths.fir.txt")
    val irPath = directory.resolve("kpaths.ir.txt")
    compileSuccessfully(directory, input, firSnapshot = firPath, irSnapshot = irPath, compilerVersion = compilerVersion)
    mapOf(CompilerSnapshotKind.FIR to Files.readString(firPath), CompilerSnapshotKind.IR to Files.readString(irPath))
  }
}

internal fun FutureCompilerPluginContractTest.assertCompiler(
  directory: Path,
  source: String,
  fir: SnapshotExpectation? = null,
  ir: SnapshotExpectation? = null,
  compilerVersion: String = checkNotNull(System.getProperty("kpaths.testKotlinVersion")),
  record: SnapshotRecording = SnapshotRecording.valueOf(System.getProperty("kpaths.snapshotRecording", "none").uppercase()),
  recordVersion: Boolean = System.getProperty("kpaths.snapshotRecordVersion", "false").toBooleanStrict(),
) {
  val sourceLocation = if (record != SnapshotRecording.NONE) {
    val caller = Throwable().stackTrace.firstOrNull {
      it.className == this.javaClass.name && !it.methodName.startsWith("assertCompiler") && it.lineNumber > 0
    } ?: error("Cannot identify the test source location for inline recording.")
    val project = Path.of(checkNotNull(System.getProperty("kpaths.projectDir")))
    val sourceFile = project.resolve("kpaths-compiler-plugin-fixture/src/test/kotlin")
      .resolve(caller.className.substringBeforeLast('.').replace('.', '/')).resolve(caller.fileName)
    Triple(sourceFile, caller.lineNumber, Files.readString(sourceFile))
  } else null
  val expected = buildMap {
    fir?.let { put(CompilerSnapshotKind.FIR, it) }
    ir?.let { put(CompilerSnapshotKind.IR, it) }
    if (isEmpty()) {
      put(CompilerSnapshotKind.FIR, snapshot())
      put(CompilerSnapshotKind.IR, snapshot())
    }
  }
  val captures = run {
    val input = source.trimIndent()
    val version = compilerVersion
    val requested = expected.keys
    val firPath = directory.resolve("kpaths.fir.txt").takeIf { CompilerSnapshotKind.FIR in requested }
    val irPath = directory.resolve("kpaths.ir.txt").takeIf { CompilerSnapshotKind.IR in requested }
    compileSuccessfully(directory, input, firSnapshot = firPath, irSnapshot = irPath, compilerVersion = version)
    buildMap {
      firPath?.let { put(CompilerSnapshotKind.FIR, Files.readString(it)) }
      irPath?.let { put(CompilerSnapshotKind.IR, Files.readString(it)) }
    }
  }
  val toRecord = expected.filter { (kind, expectation) ->
    val actual = checkNotNull(captures[kind]) { "Missing ${kind.name} capture." }
    shouldRecordSnapshot(expectation, actual, compilerVersion, record, recordVersion)
  }
  if (toRecord.isNotEmpty()) {
    val (sourceFile, line, original) = checkNotNull(sourceLocation)
    try {
      InlineCompilerSnapshots.write(
        sourceFile, line, captures.filterKeys { it in toRecord }, compilerVersion, recordVersion, original,
        compiledSourceLine = true,
      )
    } catch (error: Exception) {
      throw AssertionError("Could not record inline snapshots at $sourceFile:$line: ${error.message}", error)
    }
    throw AssertionError("Recorded inline snapshots at $sourceFile:$line; review and rerun the test.")
  }
  expected.forEach { (kind, expectation) ->
    check(!expectation.missing) { "Missing ${kind.name} snapshot; enable recording to insert it." }
  }
  assertCompilerSnapshots(source, compilerVersion, expected) { _, _, _ -> captures }
}
