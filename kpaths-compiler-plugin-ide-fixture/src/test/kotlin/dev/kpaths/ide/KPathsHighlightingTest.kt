package dev.kpaths.ide

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import dev.kpaths.testing.editor.assertEditor
import dev.kpaths.testing.editor.ReferenceObservation

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
    assertEditor(FixtureEditorObservations(myFixture, ignoreMissingDependency = true)) {
      reference("Box.Paths<String>().value").resolves()
      reference("Box.Paths.of(String::class).value").resolves()
    }
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
    assertEditor(FixtureEditorObservations(myFixture, ignoreMissingDependency = true)) {
      reference("Event.Paths<String>().Value.value").resolves()
      reference("Event.Paths<String>().Idle.unit").resolves()
    }
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
    assertEditor(FixtureEditorObservations(myFixture, ignoreMissingDependency = true)) {
      reference("Person.Paths.name").resolves()
      reference("Request.Paths.Open.payload").resolves()
    }
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
    assertEditor(FixtureEditorObservations(myFixture, ignoreMissingDependency = true)) {
      reference("Person.Paths.display").resolves()
      reference("Named.Paths.alias").resolves()
      reference("Constant.Paths.shown").resolves()
    }
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
    assertEditor(FixtureEditorObservations(myFixture, ignoreMissingDependency = true)) {
      reference("Event.Paths.MessageCase.text").resolves()
    }
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
    assertEditor(FixtureEditorObservations(myFixture, ignoreMissingDependency = true)) {
      reference("Event.Paths.MessagePaths.text").resolves()
      reference("Event.Paths.Message.textFor(id = 1)").resolves()
    }
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
    assertEditor(FixtureEditorObservations(myFixture, ignoreMissingDependency = true)) {
      diagnostics { warning("generated case catalog omits 'Many'") }
    }
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
    assertEditor(FixtureEditorObservations(myFixture, ignoreMissingDependency = true)) {
      diagnostics {
        error("'Connected' would name both")
        // Kotlin also diagnoses the colliding class and property declarations.
        error("[REDECLARATION]")
        error("[REDECLARATION]")
      }
    }
  }

  fun testNestedCatalogCollisionIsHighlightedAsError() {
    installKPathsStubs()
    myFixture.configureByText("Person.kt", """
      import dev.kpaths.annotations.Pathable
      @Pathable data class Person(val name: String) {
        object Paths
      }
    """.trimIndent())
    assertEditor(FixtureEditorObservations(myFixture, ignoreMissingDependency = true)) {
      diagnostics { error("Cannot generate 'Person.Paths'") }
    }
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
    assertEditor(FixtureEditorObservations(myFixture, ignoreMissingDependency = true)) {
      diagnostics { error("Data-class property 'count'") }
    }
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
    assertEditor(FixtureEditorObservations(myFixture, ignoreMissingDependency = true)) {
      reference("Feature.Event.Paths.Idle").resolves()
    }
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
    assertEditor(FixtureEditorObservations(myFixture, ignoreMissingDependency = true)) {
      diagnostics { error("Metadata") }
      reference("OtherFeature.Event.Paths.Metadata").isUnresolved()
    }
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
    assertEditor(FixtureEditorObservations(myFixture, ignoreMissingDependency = true)) {
      reference("Event.Paths.Idle").resolves()
    }
  }

  fun testInvalidKotlinProducesErrors() {
    myFixture.configureByText("Broken.kt", "val broken: Int = \"not an integer\"")
    assertEditor(FixtureEditorObservations(myFixture, ignoreMissingDependency = true)) {
      diagnostics { error("Int") }
    }
  }

  fun testReferenceObservationRejectsMissingAndAmbiguousExpressions() {
    myFixture.configureByText("References.kt", "val first = 1\nval second = 1")
    val observations = FixtureEditorObservations(myFixture)
    assertTrue(observations.reference("missing") is ReferenceObservation.Unavailable)
    val duplicate = observations.reference("1") as ReferenceObservation.Unavailable
    assertEquals("expression is ambiguous", duplicate.reason)
  }
}
