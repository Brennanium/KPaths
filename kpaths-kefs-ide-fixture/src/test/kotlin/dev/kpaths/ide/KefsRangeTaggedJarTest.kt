package dev.kpaths.ide

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.fir.extensions.KotlinBundledFirCompilerPluginProvider

/** Experimental check of KEFS's DevKit detection; this is not a supported publication format. */
class KefsRangeTaggedJarTest : BasePlatformTestCase() {
  fun testKefsLoadsRangeTaggedJarWithoutMavenDescriptor() {
    val requested = Path.of(checkNotNull(System.getProperty("kpaths.rangeTaggedCandidateJar")))
    val loader = checkNotNull(checkNotNull(PluginManagerCore.getPlugin(PluginId.getId("com.github.mr3zee.kotlinPlugins"))).pluginClassLoader)
    val providerClass = loader.loadClass("com.github.mr3zee.kefs.KefsProvider")
    val provider = providerClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as KotlinBundledFirCompilerPluginProvider
    System.getProperty("kpaths.untaggedCandidateJar")?.let {
      assertNull("Without the range marker or a Maven descriptor KEFS must not resolve the candidate", provider.provideBundledPluginJar(project, Path.of(it)))
    }
    var resolved: Path? = null
    val deadline = System.currentTimeMillis() + 30000
    while (resolved == null && System.currentTimeMillis() < deadline) {
      resolved = provider.provideBundledPluginJar(project, requested)
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
      if (resolved == null) Thread.sleep(1200)
    }
    val result = checkNotNull(resolved) { "KEFS did not resolve the range-tagged candidate" }
    assertEquals("KEFS must use the candidate binary", -1L, Files.mismatch(requested, result))
    PsiTestUtil.addLibrary(myFixture.module, checkNotNull(System.getProperty("kpaths.ideKotlinStdlib")))
    PsiTestUtil.addLibrary(myFixture.module, checkNotNull(System.getProperty("kpaths.coreJar")))
    PsiTestUtil.addLibrary(myFixture.module, checkNotNull(System.getProperty("kpaths.annotationsJar")))
    KotlinCommonCompilerArgumentsHolder.getInstance(project).update { pluginClasspaths = arrayOf(requested.toString()) }
    val scenario = System.getProperty("kpaths.kefsScenario", "full")
    val source = if (scenario == "properties") """
      package fixture
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.*
      @Pathable data class Person(var name: String)
      val name: WritableKeyPath<Person, String> = Person.Paths.name
    """ else {
      require(scenario == "full") { "Unknown scenario: $scenario" }
      """
      package fixture
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.*
      @Pathable data class Person(var name: String)
      @Pathable sealed interface Event { data class Message(val text: String) : Event }
      val name: WritableKeyPath<Person, String> = Person.Paths.name
      val text: CaseKeyPath<Event, String> = Event.Paths.Message.text
      """
    }
    myFixture.configureByText("Consumer.kt", source.trimIndent())
    val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }.map { it.description }
      .filterNot { it.contains("MISSING_DEPENDENCY") }
    assertEmpty(errors)
  }
}
