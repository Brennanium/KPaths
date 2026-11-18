package dev.kpaths.compiler.fixture

import dev.knapshots.testing.compiler.CompilerHost
import dev.knapshots.testing.compiler.CompilerRequest
import dev.knapshots.testing.compiler.CompilerResult
import dev.knapshots.testing.compiler.CompilerSnapshotKind
import dev.knapshots.testing.compiler.InlineSnapshotLocation
import dev.knapshots.testing.compiler.assertCompiler as assertKnapshotsCompiler
import dev.knapshots.testing.compiler.assertCompilerSnapshotFiles
import java.nio.file.Files
import java.nio.file.Path

internal typealias SnapshotExpectation = dev.knapshots.testing.compiler.SnapshotExpectation
internal typealias SnapshotRecording = dev.knapshots.testing.compiler.SnapshotRecording

private fun FutureCompilerPluginContractTest.gradleHost(directory: Path): CompilerHost = CompilerHost { request ->
  val firPath = directory.resolve("kpaths.fir.txt").takeIf { CompilerSnapshotKind.FIR in request.captures }
  val irPath = directory.resolve("kpaths.ir.txt").takeIf { CompilerSnapshotKind.IR in request.captures }
  compileSuccessfully(directory, request.sources.single().text, firSnapshot = firPath, irSnapshot = irPath, compilerVersion = request.compilerVersion)
  CompilerResult(true, captures = buildMap {
    firPath?.let { put(CompilerSnapshotKind.FIR, Files.readString(it)) }
    irPath?.let { put(CompilerSnapshotKind.IR, Files.readString(it)) }
  })
}

internal fun FutureCompilerPluginContractTest.assertRecordedCompiler(directory: Path, name: String, source: String) {
  val project = Path.of(checkNotNull(System.getProperty("kpaths.projectDir")))
  val version = checkNotNull(System.getProperty("kpaths.testKotlinVersion"))
  val recording = SnapshotRecording.valueOf(System.getProperty("kpaths.snapshotRecording", "none").uppercase())
  assertCompilerSnapshotFiles(
    source, version, project.resolve("kpaths-compiler-plugin-fixture/src/test/snapshots"), name,
    setOf(CompilerSnapshotKind.FIR, CompilerSnapshotKind.IR), recording,
    System.getProperty("kpaths.snapshotRecordVersion", "false").toBooleanStrict(),
  ) { input, compilerVersion, captures ->
    gradleHost(directory).compile(CompilerRequest(listOf(dev.knapshots.testing.compiler.CompilerSource("Main.kt", input)), compilerVersion, captures)).captures
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
  val location = if (record != SnapshotRecording.NONE) {
    val caller = Throwable().stackTrace.first {
      it.className == javaClass.name && it.lineNumber > 0
    }
    val project = Path.of(checkNotNull(System.getProperty("kpaths.projectDir")))
    InlineSnapshotLocation(project.resolve("kpaths-compiler-plugin-fixture/src/test/kotlin")
      .resolve(caller.className.substringBeforeLast('.').replace('.', '/')).resolve(caller.fileName), caller.lineNumber)
  } else null
  assertKnapshotsCompiler(source, compilerVersion, gradleHost(directory), fir, ir, record, recordVersion, location)
}
