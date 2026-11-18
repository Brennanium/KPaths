package dev.kpaths.compiler.fixture

import dev.knapshots.testing.compiler.CompilerHost
import dev.knapshots.testing.compiler.CompilerRequest
import dev.knapshots.testing.compiler.CompilerSource
import dev.knapshots.testing.compiler.CompilerResult
import dev.knapshots.testing.compiler.CompilerSnapshotKind
import dev.knapshots.testing.compiler.InlineSnapshotLocation
import dev.knapshots.testing.compiler.SnapshotExpectation
import dev.knapshots.testing.compiler.SnapshotRecording
import dev.knapshots.testing.compiler.assertCompiler as assertKnapshotsCompiler
import dev.knapshots.testing.compiler.assertCompilerSnapshotFiles
import dev.knapshots.testing.compiler.snapshot
import dev.knapshots.testing.compiler.temporaryCompilerHost
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Exercises the existing call shape while preserving a real downstream Gradle compilation. */
@Tag("compiler-plugin-contract")
class KnapshotsGradleCompatibilityTest {
  @Test fun `Gradle assertion compares FIR and IR`() {
    assertCompiler(
      source = """
        package fixture
        import dev.kpaths.annotations.Pathable
        @Pathable(copyWritableVals = true) data class Note(val body: String)
        val body = Note.Paths.body
      """,
      fir = SnapshotExpectation("""
        KPaths resolved FIR callables

        fixture/Note.Paths.body
        public final val body: R|dev/kpaths/core/WritableKeyPath<fixture/Note, kotlin/String>|
            public get(): R|dev/kpaths/core/WritableKeyPath<fixture/Note, kotlin/String>|
      """),
      ir = SnapshotExpectation("""
        KPaths post-generation IR

        class fixture.Note.Paths
          extends kotlin.Any
          val body: dev.kpaths.core.WritableKeyPath<fixture.Note, kotlin.String>

        class fixture.Note.Paths.__KPaths_body_Path
          extends dev.kpaths.core.WritableKeyPathBase<fixture.Note, kotlin.String>
          val __kpaths_id: dev.kpaths.core.PathId
          val __kpaths_valueType: dev.kpaths.core.TypeId
          fun get(root: fixture.Note): kotlin.String
          fun get(root: kotlin.Any): kotlin.Any
          fun set(root: fixture.Note, value: kotlin.String): fixture.Note
      """),
    )
  }

  @Test fun `existing call records snapshots into the original test source`(@TempDir directory: Path) {
    val root = Path.of(checkNotNull(System.getProperty("kpaths.projectDir")))
    val relative = Path.of("dev/kpaths/compiler/fixture/KnapshotsGradleCompatibilityTest.kt")
    val sourceRoot = Files.createDirectories(directory.resolve("source-copy"))
    val copiedFile = sourceRoot.resolve(relative)
    Files.createDirectories(copiedFile.parent)
    Files.copy(root.resolve("kpaths-compiler-plugin-fixture/src/knapshotsTest/kotlin").resolve(relative), copiedFile)
    val oldSourceRoot = System.getProperty("kpaths.compatibilitySourceRoot")
    try {
      System.setProperty("kpaths.compatibilitySourceRoot", sourceRoot.toString())
      val failure = assertFailsWith<AssertionError> {
        assertCompiler(
          source = "import dev.kpaths.annotations.Pathable\n@Pathable data class Album(val title: String)\nval title = Album.Paths.title",
          fir = snapshot(), record = SnapshotRecording.MISSING,
        )
      }
      assertContains(failure.message.orEmpty(), "review and rerun")
      assertContains(Files.readString(copiedFile), "Album.Paths.title")
      assertContains(Files.readString(copiedFile), "fir = SnapshotExpectation(")
    } finally {
      if (oldSourceRoot == null) System.clearProperty("kpaths.compatibilitySourceRoot")
      else System.setProperty("kpaths.compatibilitySourceRoot", oldSourceRoot)
    }
  }

  @Test fun `file snapshot assertion uses Knapshots`() {
    assertRecordedCompiler("generic-writable", """
      package fixture
      import dev.kpaths.annotations.Pathable
      @Pathable data class Box<T>(var value: T)
      val text = Box.Paths<String>().value
      val number = Box.Paths.of(Int::class).value
    """)
  }

  private fun assertRecordedCompiler(name: String, source: String) {
    val project = Path.of(checkNotNull(System.getProperty("kpaths.projectDir")))
    val version = checkNotNull(System.getProperty("kpaths.testKotlinVersion"))
    val recording = SnapshotRecording.valueOf(System.getProperty("kpaths.snapshotRecording", "none").uppercase())
    assertCompilerSnapshotFiles(
      source, version, project.resolve("kpaths-compiler-plugin-fixture/src/test/snapshots"), name,
      setOf(CompilerSnapshotKind.FIR, CompilerSnapshotKind.IR), recording,
      System.getProperty("kpaths.snapshotRecordVersion", "false").toBooleanStrict(),
    ) { input, compilerVersion, captures ->
      gradleCompilerHost().compile(CompilerRequest(listOf(CompilerSource("Main.kt", input)), compilerVersion, captures)).captures
    }
  }

  private fun gradleCompilerHost(): CompilerHost = temporaryCompilerHost { directory ->
    CompilerHost { request ->
      val firPath = directory.resolve("kpaths.fir.txt").takeIf { CompilerSnapshotKind.FIR in request.captures }
      val irPath = directory.resolve("kpaths.ir.txt").takeIf { CompilerSnapshotKind.IR in request.captures }
      FutureCompilerPluginContractTest().compileSuccessfully(
        directory = directory, source = request.sources.single().text,
        irSnapshot = irPath, firSnapshot = firPath, compilerVersion = request.compilerVersion,
      )
      CompilerResult(true, captures = buildMap {
        firPath?.let { put(CompilerSnapshotKind.FIR, Files.readString(it)) }
        irPath?.let { put(CompilerSnapshotKind.IR, Files.readString(it)) }
      })
    }
  }

  // The temporary wrapper preserves the Gradle-consumer coverage and recording defaults.
  private fun assertCompiler(
    source: String,
    fir: SnapshotExpectation? = null,
    ir: SnapshotExpectation? = null,
    compilerVersion: String = checkNotNull(System.getProperty("kpaths.testKotlinVersion")),
    record: SnapshotRecording = SnapshotRecording.valueOf(System.getProperty("kpaths.snapshotRecording", "none").uppercase()),
    recordVersion: Boolean = System.getProperty("kpaths.snapshotRecordVersion", "false").toBooleanStrict(),
  ) {
    val location = if (record != SnapshotRecording.NONE) {
      val caller = Throwable().stackTrace.first { it.className == javaClass.name && !it.methodName.startsWith("assertCompiler") }
      val root = Path.of(checkNotNull(System.getProperty("kpaths.projectDir")))
      val sourceRoot = System.getProperty("kpaths.compatibilitySourceRoot")?.let(Path::of)
        ?: root.resolve("kpaths-compiler-plugin-fixture/src/knapshotsTest/kotlin")
      InlineSnapshotLocation(sourceRoot.resolve(caller.className.substringBeforeLast('.').replace('.', '/'))
        .resolve(caller.fileName), caller.lineNumber)
    } else null
    assertKnapshotsCompiler(source, compilerVersion, gradleCompilerHost(), fir, ir, record, recordVersion, location)
  }
}
