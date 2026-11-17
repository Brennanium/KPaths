package dev.kpaths.ide

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import dev.kpaths.testing.editor.DiagnosticSeverity
import dev.kpaths.testing.editor.EditorDiagnostic
import dev.kpaths.testing.editor.EditorObservations
import dev.kpaths.testing.editor.ReferenceObservation
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import java.nio.file.Path

/** Test-only probe: analyze the real Gradle-imported project in the sandbox IDE. */
class ConsumerProbe : ProjectActivity {
  override suspend fun execute(project: Project) {
    val report = System.getProperty("kpaths.consumerProbe") ?: return
    val projectPath = project.basePath ?: return
    val expectedProject = System.getProperty("kpaths.consumerProject") ?: return
    if (Path.of(projectPath).toRealPath() != Path.of(expectedProject).toRealPath()) return
    val sandbox = Path.of(System.getProperty("kpaths.consumerSandbox") ?: return).toAbsolutePath().normalize()
    val actual = listOf(PathManager.getConfigPath(), PathManager.getSystemPath(), PathManager.getLogPath())
      .map { Path.of(it).toAbsolutePath().normalize() }
    val expectedPaths = listOf("config", "system", "log").map { sandbox.resolve(it) }
    if (actual != expectedPaths) {
      Files.writeString(Path.of(report), "ERROR: sandbox paths differ: expected=$expectedPaths actual=$actual")
      ApplicationManager.getApplication().invokeLater { ApplicationManager.getApplication().exit(true, true, false) }
      return
    }
    val expected = System.getProperty("kpaths.consumerExpected", "enabled")
    require(expected in setOf("enabled", "disabled", "diagnostic"))
    val source = Path.of(project.basePath!!, "src/main/kotlin/Example.kt")
    // Opening the file starts the IDE's normal highlighting passes. A reference resolving
    // alone does not imply that those passes have finished.
    val sourceFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source)
    ApplicationManager.getApplication().invokeLater {
      if (!project.isDisposed && sourceFile != null) {
        FileEditorManager.getInstance(project).openFile(sourceFile, true)
      }
    }
    // A cold hosted runner must complete Gradle import and indexing before references resolve.
    val deadline = System.currentTimeMillis() + 600_000
    var last = "waiting for project"
    var lastHighlightRestart = 0L
    while (System.currentTimeMillis() < deadline && !project.isDisposed) {
      delay(2_000)
      if (DumbService.isDumb(project)) continue
      if (expected != "disabled" && System.currentTimeMillis() - lastHighlightRestart > 30_000) {
        lastHighlightRestart = System.currentTimeMillis()
        ApplicationManager.getApplication().invokeLater {
          if (!project.isDisposed && sourceFile != null) {
            FileEditorManager.getInstance(project).openFile(sourceFile, true)
            PsiManager.getInstance(project).findFile(sourceFile)?.let {
              DaemonCodeAnalyzerEx.getInstanceEx(project).restart(it)
            }
          }
        }
      }
      last = try {
        ReadAction.compute<String, Throwable> {
          val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source)
            ?: return@compute "missing source"
          val psi = PsiManager.getInstance(project).findFile(file) ?: return@compute "missing PSI"
          val document = FileDocumentManager.getInstance().getDocument(file) ?: return@compute "missing document"
          if (expected != "disabled" && !DaemonCodeAnalyzerEx.getInstanceEx(project).isErrorAnalyzingFinished(psi)) {
            val analyzer = DaemonCodeAnalyzerEx.getInstanceEx(project)
            val editors = FileEditorManager.getInstance(project)
            val open = editors.isFileOpen(file)
            val selected = file in editors.selectedFiles
            return@compute "waiting for editor highlighting (open=$open selected=$selected available=${analyzer.isHighlightingAvailable(psi)})"
          }
          val diagnostics = mutableListOf<EditorDiagnostic>()
          if (expected != "disabled") {
            DaemonCodeAnalyzerEx.processHighlights(document, project, HighlightSeverity.WARNING, 0, document.textLength) { highlight ->
              val offset = highlight.actualStartOffset.coerceIn(0, document.textLength)
              val line = document.getLineNumber(offset)
              diagnostics += EditorDiagnostic(
                severity = if (highlight.severity == HighlightSeverity.ERROR) DiagnosticSeverity.ERROR else DiagnosticSeverity.WARNING,
                message = highlight.description ?: "<no description>",
                location = "${psi.name}:${line + 1}:${offset - document.getLineStartOffset(line) + 1}",
              )
              true
            }
          }
          val code = psi.text
          val queries = linkedMapOf(
            "Person annotation" to ("@Pathable\ndata class Person" to "Pathable"),
            "Direct property" to ("Person::name" to "name"),
            "Hosted support" to ("HostedSupport.value" to "value"),
            "Person.Paths" to ("Person.Paths.name" to "Paths"),
            "Person.Paths.name" to ("Person.Paths.name" to "name"),
            "Event.Paths" to ("Event.Paths.Message.text" to "Paths"),
            "Event.Paths.Message" to ("Event.Paths.Message.text" to "Message"),
            "Event.Paths.Message.text" to ("Event.Paths.Message.text" to "text"),
          )
          val observations = object : EditorObservations {
            override fun diagnostics(): List<EditorDiagnostic> = diagnostics
            override fun reference(expression: String): ReferenceObservation {
              val (anchor, name) = queries[expression] ?: return ReferenceObservation.Unavailable("unknown query")
              val start = code.indexOf(anchor)
              if (start < 0) return ReferenceObservation.Unavailable("source anchor not found: $anchor")
              if (code.indexOf(anchor, start + 1) >= 0) return ReferenceObservation.Unavailable("source anchor is ambiguous: $anchor")
              val element = psi.findElementAt(start + anchor.indexOf(name))?.parent
                ?: return ReferenceObservation.Unavailable("reference element not found")
              val refs = element.references
              if (refs.isEmpty()) return ReferenceObservation.Unavailable("element has no reference")
              val target = refs.firstNotNullOfOrNull { it.resolve() }
              return if (target == null) ReferenceObservation.Unresolved else ReferenceObservation.Resolved()
            }
          }
          try {
            assertImportedReferences(observations, expected)
            "PASS ($expected)"
          } catch (failure: AssertionError) {
            failure.message ?: "Editor assertions failed"
          }
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        "${error.javaClass.name}: ${error.message}"
      }
      if (last == "PASS ($expected)") {
        Files.writeString(Path.of(report), "PASS ($expected)\n$last")
        // This activity runs only in the isolated runIde probe. Exit that IDE so Gradle can
        // finish before the runner changes the project configuration for the next launch.
        ApplicationManager.getApplication().invokeLater { ApplicationManager.getApplication().exit(true, true, false) }
        return
      }
      Files.writeString(Path.of(report), "WAITING ($expected)\n$last")
    }
    Files.writeString(Path.of(report), "TIMEOUT\n$last")
    ApplicationManager.getApplication().invokeLater { ApplicationManager.getApplication().exit(true, true, false) }
  }
}
