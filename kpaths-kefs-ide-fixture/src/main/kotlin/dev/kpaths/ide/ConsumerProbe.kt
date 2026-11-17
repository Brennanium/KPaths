package dev.kpaths.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
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
    require(expected in setOf("enabled", "disabled"))
    val source = Path.of(project.basePath!!, "src/main/kotlin/Example.kt")
    // A cold hosted runner must complete Gradle import and indexing before references resolve.
    val deadline = System.currentTimeMillis() + 600_000
    var last = "waiting for project"
    while (System.currentTimeMillis() < deadline && !project.isDisposed) {
      delay(2_000)
      if (DumbService.isDumb(project)) continue
      last = try {
        ReadAction.compute<String, Throwable> {
          val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source)
            ?: return@compute "missing source"
          val psi = PsiManager.getInstance(project).findFile(file) ?: return@compute "missing PSI"
          val code = psi.text
          val queries = linkedMapOf(
            "Person annotation" to ("@Pathable\ndata class Person" to "Pathable"),
            "Direct property" to ("Person::name" to "name"),
            "Person.Paths" to ("Person.Paths.name" to "Paths"),
            "Person.Paths.name" to ("Person.Paths.name" to "name"),
            "Event.Paths" to ("Event.Paths.Message.text" to "Paths"),
            "Event.Paths.Message" to ("Event.Paths.Message.text" to "Message"),
            "Event.Paths.Message.text" to ("Event.Paths.Message.text" to "text"),
          )
          val observations = object : EditorObservations {
            override fun diagnostics(): List<EditorDiagnostic> = error("Imported probe does not capture diagnostics")
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
