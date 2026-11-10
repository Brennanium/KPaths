package dev.kpaths.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import java.nio.file.Path

/** Test-only probe: analyze the real Gradle-imported project in the sandbox IDE. */
class ConsumerProbe : ProjectActivity {
  override suspend fun execute(project: Project) {
    val report = System.getProperty("kpaths.consumerProbe") ?: return
    if (project.name != "kpaths-kefs-consumer") return
    val expected = System.getProperty("kpaths.consumerExpected", "enabled")
    require(expected in setOf("enabled", "disabled"))
    val source = Path.of(project.basePath!!, "src/main/kotlin/Example.kt")
    val deadline = System.currentTimeMillis() + 120_000
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
          val queries = listOf("Person.Paths.name" to "Paths", "Person.Paths.name" to "name",
            "Event.Paths.Message.text" to "Paths", "Event.Paths.Message.text" to "Message",
            "Event.Paths.Message.text" to "text")
          val controls = listOf("@Pathable" to "Pathable", "Person::name" to "name")
          val results = (controls + queries).map { (expression, name) ->
            val start = code.indexOf(expression)
            check(start >= 0) { "Missing $expression" }
            val offset = start + expression.indexOf(name)
            val element = checkNotNull(psi.findElementAt(offset)).parent
            val target = element.references.firstNotNullOfOrNull { it.resolve() }
            "$expression:$name=element:${element.javaClass.simpleName}:${element.text}:target:${target?.javaClass?.simpleName ?: "UNRESOLVED"}:${target?.text?.take(80)?.replace("\n", " ")}"
          }
          results.joinToString("\n")
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        "${error.javaClass.name}: ${error.message}"
      }
      val results = last.lines()
      val controlsPass = results.size == 7 && results.take(2).none { it.contains("UNRESOLVED") }
      val generatedPass = if (expected == "enabled") {
        results.drop(2).none { it.contains("UNRESOLVED") }
      } else {
        results.drop(2).all { it.contains("UNRESOLVED") }
      }
      if (controlsPass && generatedPass) {
        Files.writeString(Path.of(report), "PASS ($expected)\n$last")
        return
      }
      Files.writeString(Path.of(report), "WAITING ($expected)\n$last")
    }
    Files.writeString(Path.of(report), "TIMEOUT\n$last")
  }
}
