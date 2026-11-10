package dev.kpaths.ide

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder

class KPathsRuntimeHighlightingTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    PsiTestUtil.addLibrary(myFixture.module, checkNotNull(System.getProperty("kpaths.ideKotlinStdlib")))
    KotlinCommonCompilerArgumentsHolder.getInstance(project).update {
      pluginClasspaths = arrayOf(checkNotNull(System.getProperty("kpaths.buildCompilerPluginJar")))
    }
  }

  fun testGeneratedPathsResolveAgainstRealRuntime() {
    PsiTestUtil.addLibrary(myFixture.module, checkNotNull(System.getProperty("kpaths.coreJar")))
    PsiTestUtil.addLibrary(myFixture.module, checkNotNull(System.getProperty("kpaths.annotationsJar")))
    myFixture.configureByText("Consumer.kt", """
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.*
      @Pathable data class Person(var name: String)
      @Pathable sealed interface Event { data class Message(val text: String) : Event }
      val name: WritableKeyPath<Person, String> = Person.Paths.name
      val text: CaseKeyPath<Event, String> = Event.Paths.Message.text
    """.trimIndent())
    val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
      .map { it.description }.filterNot { it.contains("MISSING_DEPENDENCY") }
    assertEmpty(errors)
  }

}
