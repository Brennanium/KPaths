package dev.kpaths.ide

import com.intellij.testFramework.PsiTestUtil
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import dev.knapshots.testing.editor.intellij.EditorFixtureTestCase
import kotlin.test.assertFailsWith
import kotlin.test.assertContains

class KPathsRuntimeHighlightingTest : EditorFixtureTestCase() {
  override val ignoreMissingDependency: Boolean = true

  override fun setUp() {
    super.setUp()
    PsiTestUtil.addLibrary(myFixture.module, checkNotNull(System.getProperty("kpaths.ideKotlinStdlib")))
    KotlinCommonCompilerArgumentsHolder.getInstance(project).update {
      pluginClasspaths = arrayOf(checkNotNull(System.getProperty("kpaths.buildCompilerPluginJar")))
    }
    PsiTestUtil.addLibrary(myFixture.module, checkNotNull(System.getProperty("kpaths.coreJar")))
    PsiTestUtil.addLibrary(myFixture.module, checkNotNull(System.getProperty("kpaths.annotationsJar")))
  }

  fun testGeneratedPathsResolveAgainstRealRuntime() {
    assertEditor(fileName = "Consumer.kt", source = """
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.*
      @Pathable data class Person(var name: String)
      @Pathable sealed interface Event { data class Message(val text: String) : Event }
      val name: WritableKeyPath<Person, String> = Person.Paths.name
      val text: CaseKeyPath<Event, String> = Event.Paths.Message.text
    """) {
      diagnosticsSnapshot("")
      reference("Person.Paths.name").resolves()
      reference("Event.Paths.Message.text").resolves()
    }
  }

  fun testInlineSourceResolvesAdditionalProjectFiles() {
    assertEditor(
      fileName = "Consumer.kt",
      source = "val label: String = model.name",
      additionalSources = mapOf("Model.kt" to "data class Model(val name: String)\nval model = Model(\"hello\")"),
    ) {
      reference("model.name").resolves()
    }
  }

  fun testInlineSourceRejectsUnexpectedErrors() {
    val failure = assertFailsWith<AssertionError> {
      assertEditor(source = "val count: Int = \"not a number\"") {}
    }
    assertContains(failure.message.orEmpty(), "Unexpected ERROR")
  }

  fun testInlineSourceChecksDiagnosticLocation() {
    assertEditor(fileName = "Problem.kt", source = "val count: Int = \"not a number\"") {
      diagnostics { error("Int", location = "Problem.kt:1:16") }
    }
  }

  fun testInlineSourceChecksExpectedDiagnostics() {
    assertEditor(source = "val count: Int = \"not a number\"") {
      diagnostics { error("Int") }
    }
  }
}
