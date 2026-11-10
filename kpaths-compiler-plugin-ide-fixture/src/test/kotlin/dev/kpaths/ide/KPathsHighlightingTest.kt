package dev.kpaths.ide

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder

class KPathsHighlightingTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    PsiTestUtil.addLibrary(myFixture.module, checkNotNull(System.getProperty("kpaths.ideKotlinStdlib")))
    KotlinCommonCompilerArgumentsHolder.getInstance(project).update {
      pluginClasspaths = arrayOf(checkNotNull(System.getProperty("kpaths.buildCompilerPluginJar")))
    }
  }

  private fun installKPathsStubs() {
    myFixture.addFileToProject("Pathable.kt", "package dev.kpaths.annotations\nannotation class Pathable")
    myFixture.addFileToProject("PathName.kt", "package dev.kpaths.annotations\n@Target(AnnotationTarget.PROPERTY) annotation class PathName(val value: String)")
    myFixture.addFileToProject("CaseName.kt", "package dev.kpaths.annotations\n@Target(AnnotationTarget.CLASS, AnnotationTarget.FIELD) annotation class CaseName(val value: String)")
    myFixture.addFileToProject("Core.kt", """
      package dev.kpaths.core
      interface KeyPath<R, V>
      interface WritableKeyPath<R, V> : KeyPath<R, V>
      class TypeId
      class PathId
      interface PartialCaseKeyPath<R>
      interface CasePathable<R> { val case: PartialCaseKeyPath<R> }
      abstract class CasePathCatalogBase<R> {
        abstract fun __kpaths_buildAllCasePaths(): List<PartialCaseKeyPath<R>>
        val allCasePaths: List<PartialCaseKeyPath<R>> get() = __kpaths_buildAllCasePaths()
      }
      interface CaseKeyPath<R, V> : PartialCaseKeyPath<R>
      abstract class CaseKeyPathBase<R, V> : CaseKeyPath<R, V>
    """.trimIndent())
  }

  fun testProviderIsUsedForGeneratedPaths() {
    val before = KPathsFixtureProvider.requests.get()
    testGeneratedPathsResolve()
    assertTrue("The IDE did not ask the provider for the build compiler plugin", KPathsFixtureProvider.requests.get() > before)
  }

  fun testGenericPathsResolve() {
    installKPathsStubs()
    myFixture.configureByText("GenericBox.kt", """
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.WritableKeyPath
      @Pathable data class Box<T : CharSequence>(var value: T)
      val value: WritableKeyPath<Box<String>, String> = Box.Paths<String>().value
      val classValue: WritableKeyPath<Box<String>, String> = Box.Paths.of(String::class).value
    """.trimIndent())
    val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
      .map { it.description }.filterNot { it.contains("MISSING_DEPENDENCY") }
    assertEmpty(errors)
  }

  fun testGenericSealedWholeCasePathsResolve() {
    installKPathsStubs()
    myFixture.configureByText("GenericEvent.kt", """
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.CaseKeyPath
      import dev.kpaths.core.KeyPath
      @Pathable sealed interface Event<out T> {
        data object Idle : Event<Nothing>
        data class Value<T>(val value: T) : Event<T>
      }
      val idle: CaseKeyPath<Event<String>, Event.Idle> = Event.Paths<String>().Idle
      val value: CaseKeyPath<Event<String>, Event.Value<String>> = Event.Paths<String>().Value
      val payload: CaseKeyPath<Event<String>, String> = Event.Paths<String>().Value.value
      val property: KeyPath<Event.Value<String>, String> = Event.Paths<String>().ValuePaths.value
      val unit: CaseKeyPath<Event<String>, Unit> = Event.Paths<String>().Idle.unit
      val all = Event.Paths<String>().allCasePaths
    """.trimIndent())
    val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
      .map { it.description }.filterNot { it.contains("MISSING_DEPENDENCY") }
    assertEmpty(errors)
  }

  fun testGeneratedPathsResolve() {
    installKPathsStubs()
    myFixture.configureByText("Person.kt", """
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.KeyPath
      import dev.kpaths.core.CasePathable
      import dev.kpaths.core.CaseKeyPath
      @Pathable data class Person(val name: String)
      val name: KeyPath<Person, String> = Person.Paths.name
      @Pathable enum class Status { Offline, Online }
      val status: CasePathable<Status> = Status.Online
      @Pathable sealed interface Request {
        data class Open(val id: String, val animated: Boolean) : Request
      }
      val open: CaseKeyPath<Request, Pair<String, Boolean>> = Request.Paths.Open.payload
    """.trimIndent())
    val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
      .map { it.description }.filterNot { it.contains("MISSING_DEPENDENCY") }
    assertEmpty(errors)
  }

  fun testRenamedPropertyPathResolves() {
    installKPathsStubs()
    myFixture.configureByText("Person.kt", """
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.annotations.PathName
      @Pathable data class Person(@PathName("display") val name: String)
      val name = Person.Paths.display
      @Pathable data class Named(@PathName(value = "alias") val name: String)
      val alias = Named.Paths.alias
      const val FIELD_NAME = "shown"
      @Pathable data class Constant(@PathName(FIELD_NAME) val name: String)
      val constant = Constant.Paths.shown
    """.trimIndent())
    val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
      .map { it.description }.filterNot { it.contains("MISSING_DEPENDENCY") }
    assertEmpty(errors)
  }

  fun testRenamedCasePathResolves() {
    installKPathsStubs()
    myFixture.configureByText("Event.kt", """
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.annotations.CaseName
      @Pathable sealed interface Event {
        @CaseName("MessageCase") data class Message(val text: String) : Event
      }
      val message = Event.Paths.MessageCase.text
    """.trimIndent())
    val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
      .map { it.description }.filterNot { it.contains("MISSING_DEPENDENCY") }
    assertEmpty(errors)
  }

  fun testCasePropertyCatalogAndBoundFactoryResolve() {
    installKPathsStubs()
    myFixture.configureByText("BoundEvent.kt", """
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.CaseKeyPath
      import dev.kpaths.core.KeyPath
      @Pathable sealed interface Event {
        data class Message(val id: Int, val text: String) : Event
      }
      val property: KeyPath<Event.Message, String> = Event.Paths.MessagePaths.text
      val bound: CaseKeyPath<Event, String> = Event.Paths.Message.textFor(id = 1)
    """.trimIndent())
    val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
      .map { it.description }.filterNot { it.contains("MISSING_DEPENDENCY") }
    assertEmpty(errors)
  }

  fun testIncompleteCatalogIsHighlightedAsWarning() {
    installKPathsStubs()
    myFixture.configureByText("Event.kt", """
      import dev.kpaths.annotations.Pathable
      @Pathable sealed interface Event {
        data object Idle : Event
        data class Many(val a: Int, val b: Int, val c: Int, val d: Int) : Event
      }
    """.trimIndent())
    val warnings = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.WARNING }
      .map { it.description }
    assertTrue(warnings.toString(), warnings.any { it.contains("generated case catalog omits 'Many'") })
  }

  fun testGeneratedMemberCollisionIsHighlightedAsError() {
    installKPathsStubs()
    myFixture.configureByText("Connection.kt", """
      import dev.kpaths.annotations.Pathable
      @Pathable sealed class Connection {
        abstract val Connected: String
        data class Connected(override val Connected: String) : Connection()
      }
    """.trimIndent())
    val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
      .map { it.description }
    assertTrue(errors.toString(), errors.any { it.contains("'Connected' would name both") })
  }

  fun testNestedCatalogCollisionIsHighlightedAsError() {
    installKPathsStubs()
    myFixture.configureByText("Person.kt", """
      import dev.kpaths.annotations.Pathable
      @Pathable data class Person(val name: String) {
        object Paths
      }
    """.trimIndent())
    val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
      .map { it.description }
    assertTrue(errors.toString(), errors.any { it.contains("Cannot generate 'Person.Paths'") })
  }

  fun testInaccessibleSetterIsHighlightedAsError() {
    installKPathsStubs()
    myFixture.configureByText("Item.kt", """
      import dev.kpaths.annotations.Pathable
      @Pathable data class Item(val id: Int) {
        var count: Int = 0
          private set
      }
    """.trimIndent())
    val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
      .map { it.description }
    assertTrue(errors.toString(), errors.any { it.contains("Data-class property 'count'") })
  }

  fun testOnlyNestedSealedCasesResolve() {
    installKPathsStubs()
    myFixture.configureByText("Feature.kt", """
      import dev.kpaths.annotations.Pathable
      class Feature {
        @Pathable sealed interface Event {
          data object Idle : Event
          data class Metadata(val title: String)
        }
      }
      val case = Feature.Event.Paths.Idle
    """.trimIndent())
    val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
      .map { it.description }.filterNot { it.contains("MISSING_DEPENDENCY") }
    assertEmpty(errors)
    myFixture.configureByText("InvalidFeature.kt", """
      import dev.kpaths.annotations.Pathable
      class OtherFeature {
        @Pathable sealed interface Event {
          data object Idle : Event
          data class Metadata(val title: String)
        }
      }
      val invalid = OtherFeature.Event.Paths.Metadata
    """.trimIndent())
    val invalidErrors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
      .map { it.description }
    assertTrue(invalidErrors.toString(), invalidErrors.any { it.contains("Metadata") })
  }

  fun testGenericAliasSealedCaseResolves() {
    installKPathsStubs()
    myFixture.configureByText("GenericAlias.kt", """
      import dev.kpaths.annotations.Pathable
      typealias Alias<T> = Event
      @Pathable sealed interface Event {
        data object Idle : Alias<String>
      }
      val case = Event.Paths.Idle
    """.trimIndent())
    val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
      .map { it.description }.filterNot { it.contains("MISSING_DEPENDENCY") }
    assertEmpty(errors)
  }

  fun testInvalidKotlinProducesErrors() {
    myFixture.configureByText("Broken.kt", "val broken: Int = \"not an integer\"")
    assertTrue(myFixture.doHighlighting().any { it.severity == HighlightSeverity.ERROR })
  }
}
