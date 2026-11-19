package dev.kpaths.compiler.fixture

import dev.knapshots.testing.compiler.CompilerHost
import dev.knapshots.testing.compiler.CompilerRequest
import dev.knapshots.testing.compiler.CompilerResult
import dev.knapshots.testing.compiler.CompilerSnapshotKind
import dev.knapshots.testing.compiler.discoverInlineSnapshotLocation
import dev.knapshots.testing.compiler.assertCompiler as assertKnapshotsCompiler
import dev.knapshots.testing.compiler.assertCompilerSnapshotFiles
import dev.knapshots.testing.compiler.EmbeddedCompilerHost
import java.nio.file.Path

internal typealias SnapshotExpectation = dev.knapshots.testing.compiler.SnapshotExpectation
internal typealias SnapshotRecording = dev.knapshots.testing.compiler.SnapshotRecording

private fun FutureCompilerPluginContractTest.embeddedHost(): CompilerHost {
  val project = Path.of(checkNotNull(System.getProperty("kpaths.projectDir")))
  val version = java.util.Properties().apply { java.nio.file.Files.newInputStream(project.resolve("gradle.properties")).use(::load) }
    .getProperty("VERSION_NAME")
  fun artifact(module: String) = project.resolve("$module/build/libs/$module-jvm-$version.jar")
  return EmbeddedCompilerHost(
    classpath = listOf(artifact("kpaths-core"), artifact("kpaths-annotations")),
    compilerArguments = listOf("-Xplugin=${artifact("kpaths-compiler-plugin")}"),
    captureArguments = { captures -> captures.flatMap { (kind, path) ->
      val option = when (kind) {
        CompilerSnapshotKind.FIR -> "firSnapshot"
        CompilerSnapshotKind.IR -> "irSnapshot"
        else -> error("Unsupported KPaths capture: ${kind.name}")
      }
      listOf("-P", "plugin:dev.kpaths.compiler-plugin:$option=$path")
    } },
  )
}

internal fun FutureCompilerPluginContractTest.assertRecordedCompiler(name: String, source: String) {
  val project = Path.of(checkNotNull(System.getProperty("kpaths.projectDir")))
  val version = checkNotNull(System.getProperty("kpaths.testKotlinVersion"))
  val recording = SnapshotRecording.valueOf(System.getProperty("kpaths.snapshotRecording", "none").uppercase())
  assertCompilerSnapshotFiles(
    source, version, project.resolve("kpaths-compiler-plugin-fixture/src/test/snapshots"), name,
    setOf(CompilerSnapshotKind.FIR, CompilerSnapshotKind.IR), recording,
    System.getProperty("kpaths.snapshotRecordVersion", "false").toBooleanStrict(),
  ) { input, compilerVersion, captures ->
    embeddedHost().compile(CompilerRequest(listOf(dev.knapshots.testing.compiler.CompilerSource("Main.kt", input)), compilerVersion, captures)).captures
  }
}

internal fun FutureCompilerPluginContractTest.assertCompiler(
  source: String,
  fir: SnapshotExpectation? = null,
  ir: SnapshotExpectation? = null,
  compilerVersion: String = checkNotNull(System.getProperty("kpaths.testKotlinVersion")),
  record: SnapshotRecording = SnapshotRecording.valueOf(System.getProperty("kpaths.snapshotRecording", "none").uppercase()),
  recordVersion: Boolean = System.getProperty("kpaths.snapshotRecordVersion", "false").toBooleanStrict(),
) {
  val location = if (record != SnapshotRecording.NONE) {
    val project = Path.of(checkNotNull(System.getProperty("kpaths.projectDir")))
    discoverInlineSnapshotLocation(listOf(project.resolve("kpaths-compiler-plugin-fixture/src/test/kotlin")))
  } else null
  assertKnapshotsCompiler(source, compilerVersion, embeddedHost(), fir, ir, record, recordVersion, location)
}
