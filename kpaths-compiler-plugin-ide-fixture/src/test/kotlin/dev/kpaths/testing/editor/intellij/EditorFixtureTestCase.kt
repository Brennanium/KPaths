package dev.kpaths.testing.editor.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.knapshots.testing.editor.EditorExpectations
import dev.knapshots.testing.editor.EditorSourceHost

/** Lightweight source host; the suite supplies its project dependencies and plugin setup. */
abstract class EditorFixtureTestCase : BasePlatformTestCase() {
  protected open val ignoreMissingDependency: Boolean = false

  protected fun assertEditor(
    source: String,
    fileName: String = "Test.kt",
    additionalSources: Map<String, String> = emptyMap(),
    block: EditorExpectations.() -> Unit,
  ) {
    val host = EditorSourceHost { request ->
      val main = request.sources.first()
      request.sources.drop(1).forEach { sourceFile ->
        myFixture.addFileToProject(sourceFile.fileName, sourceFile.text)
      }
      myFixture.configureByText(main.fileName, main.text)
      IntellijEditorObservations(myFixture, ignoreMissingDependency)
    }
    dev.knapshots.testing.editor.assertEditor(source = source, host = host, fileName = fileName,
      additionalSources = additionalSources.map { (name, text) -> dev.knapshots.testing.editor.EditorSource(name, text) }, block = block)
  }
}
