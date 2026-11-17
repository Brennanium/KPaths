package dev.kpaths.testing.editor.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.kpaths.testing.editor.EditorExpectations
import dev.kpaths.testing.editor.EditorSourceHost

/** Lightweight source host; the suite supplies its project dependencies and plugin setup. */
abstract class EditorFixtureTestCase : BasePlatformTestCase() {
  protected open val ignoreMissingDependency: Boolean = false

  protected fun assertEditor(
    source: String,
    fileName: String = "Test.kt",
    block: EditorExpectations.() -> Unit,
  ) {
    val host = EditorSourceHost { name, text ->
      myFixture.configureByText(name, text)
      IntellijEditorObservations(myFixture, ignoreMissingDependency)
    }
    dev.kpaths.testing.editor.assertEditor(source = source, host = host, fileName = fileName, block = block)
  }
}
