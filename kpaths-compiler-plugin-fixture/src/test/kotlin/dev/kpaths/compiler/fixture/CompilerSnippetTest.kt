@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package dev.kpaths.compiler.fixture

import com.tschuchort.compiletesting.KotlinCompilation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class CompilerSnippetTest {
  @Test
  fun `invalid lower level adapter public type receives a diagnostic`() {
    val result = compileSnippetWithContributor("InvalidRecipe.kt" to """
      package dev.kpaths.compiler.fixture
      @Target(AnnotationTarget.CLASS) annotation class ContributedCatalog
      @ContributedCatalog data class InvalidRecipeDocument(val value: String)
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "Invalid KPaths adapter recipe for 'value': the integration recipe must expose a KeyPath subtype.")
  }

  @Test
  fun `declarative adapter reports invalid factory from dependency metadata`() {
    val result = compileSnippet("InvalidBinaryAdapter.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.compiler.integration.InvalidBinaryCell
      @Pathable data class Document(val title: InvalidBinaryCell<String>)
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "Invalid @PathsAdapter on 'title': the adapter factory must declare exactly one make function.")
  }

  @Test
  fun `matching lower level adapter takes precedence over an invalid declarative factory`() {
    val result = compileSnippetWithContributor("AdapterOverride.kt" to """
      package dev.kpaths.compiler.fixture
      import dev.kpaths.annotations.*
      @Target(AnnotationTarget.CLASS) annotation class ContributedCatalog
      @PathsAdapter(factory = Broken::class) class Cell<T>(val value: T)
      object Broken : KPathsAdapterFactory
      @ContributedCatalog data class OverrideDocument(val title: Cell<String>)
      fun run(): Boolean = OverrideDocument.GeneratedPaths.title.get(OverrideDocument(Cell("ok"))).value == "ok"
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("dev.kpaths.compiler.fixture.AdapterOverrideKt").getMethod("run").invoke(null))
  }

  @Test
  fun `unmatched lower level adapters do not suppress declarative diagnostics`() {
    val result = compileSnippetWithContributor(
      "ContributedCatalog.kt" to """
        package dev.kpaths.compiler.fixture
        @Target(AnnotationTarget.CLASS) annotation class ContributedCatalog
      """,
      "UnmatchedAdapter.kt" to """
        package example
        import dev.kpaths.annotations.*
        import dev.kpaths.compiler.fixture.ContributedCatalog
        @PathsAdapter(factory = Broken::class) class Cell<T>(val value: T)
        object Broken : KPathsAdapterFactory
        @ContributedCatalog data class Document(val title: Cell<String>)
      """,
    )
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages,
      "[KPaths] Invalid @PathsAdapter on 'title': the adapter factory must declare exactly one make function.")
  }

  @Test
  fun `declarative adapter reports precise factory shape errors`() {
    val result = compileSnippet("DeclarativeFactoryShapes.kt" to """
      package example
      import dev.kpaths.annotations.*
      import dev.kpaths.core.*
      @PathsAdapter(factory = WrongReturn::class) class Cell<T>(val value: T)
      object WrongReturn : KPathsAdapterFactory {
        fun <R, V> make(path: KeyPath<R, Cell<V>>): String = "invalid"
      }
      @PathsAdapter(factory = Bounded::class) class TextCell<T>(val value: T)
      object Bounded : KPathsAdapterFactory {
        fun <R, V : CharSequence> make(path: KeyPath<R, TextCell<V>>): KeyPath<R, TextCell<V>> = path
      }
      @PathsAdapter(factory = Projected::class) class ProjectedCell<T>(val value: T)
      object Projected : KPathsAdapterFactory {
        fun <R, V> make(path: KeyPath<R, ProjectedCell<V>>): KeyPath<R, ProjectedCell<V>> = path
      }
      @Pathable data class Document(val first: Cell<String>, val second: TextCell<String>, val third: ProjectedCell<out String>)
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "Invalid @PathsAdapter on 'first': make must return a KeyPath subtype.")
    assertContains(result.messages, "Invalid @PathsAdapter on 'second': make type parameters must not have explicit bounds.")
    assertContains(result.messages, "Invalid @PathsAdapter on 'third': storage type has unsupported projections or nullable type parameters.")
  }

  @Test
  fun `declarative adapter diagnoses a nullable wrapper`() {
    val result = compileSnippet("NullableAdapterWrapper.kt" to """
      package example
      import dev.kpaths.annotations.*
      import dev.kpaths.core.*
      @PathsAdapter(factory = Factory::class) class Cell<T>(val value: T)
      object Factory : KPathsAdapterFactory {
        fun <R, V> make(path: KeyPath<R, Cell<V>>): KeyPath<R, Cell<V>> = path
      }
      @Pathable data class Document(val title: Cell<String>?)
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] Invalid @PathsAdapter on 'title': make's KeyPath parameter does not match the generated root and storage types.")
  }

  @Test
  fun `declarative adapter diagnoses missing factory method`() {
    val result = compileSnippet("InvalidDeclarativeFactory.kt" to """
      package example
      import dev.kpaths.annotations.*
      @PathsAdapter(factory = Factory::class) class Cell<T>(val value: T)
      object Factory : KPathsAdapterFactory
      @Pathable data class Document(val title: Cell<String>)
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] Invalid @PathsAdapter on 'title': the adapter factory must declare exactly one make function.")
  }

  @Test
  fun `declarative adapter diagnoses non key path return and overloaded methods`() {
    val result = compileSnippet("InvalidDeclarativeFactoryShapes.kt" to """
      package example
      import dev.kpaths.annotations.*
      import dev.kpaths.core.*
      @PathsAdapter(factory = WrongReturn::class) class Cell<T>(val value: T)
      object WrongReturn : KPathsAdapterFactory {
        fun <R, V> make(path: KeyPath<R, Cell<V>>): String = "invalid"
      }
      @PathsAdapter(factory = Overloaded::class) class Other<T>(val value: T)
      object Overloaded : KPathsAdapterFactory {
        fun <R, V> make(path: KeyPath<R, Other<V>>): KeyPath<R, Other<V>> = path
        fun make(path: String): String = path
      }
      @Pathable data class Document(val first: Cell<String>, val second: Other<String>)
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "Invalid @PathsAdapter on 'first': make must return a KeyPath subtype.")
    assertContains(result.messages, "Invalid @PathsAdapter on 'second': the adapter factory must declare exactly one make function.")
  }

  @Test
  fun `declarative adapter factory generates specialized paths without a contributor`() {
    val result = compileSnippet("DeclarativeFactory.kt" to """
      package example
      import dev.kpaths.annotations.*
      import dev.kpaths.core.*
      @PathsAdapter(factory = CellFactory::class)
      class Cell<T>(val value: T)
      object CellFactory : KPathsAdapterFactory {
        fun <Root, Value> make(storage: KeyPath<Root, Cell<Value>>): CellPath<Root, Value> = CellPath(storage)
      }
      class CellPath<Root, Value>(private val storage: KeyPath<Root, Cell<Value>>) : KeyPathBase<Root, Value>() {
        override val __kpaths_id get() = storage.__kpaths_id
        override val __kpaths_valueType get() = storage.__kpaths_valueType.requireTypeArgument(0)
        override fun get(root: Root): Value = storage.get(root).value
      }
      @PathsAdapter(factory = ReaderFactory::class) interface Reader<out T> { val value: T }
      class Readable<T>(override val value: T) : Reader<T>
      object ReaderFactory : KPathsAdapterFactory {
        fun <Root, Value> make(storage: KeyPath<Root, Reader<Value>>): ReaderPath<Root, Value> = ReaderPath(storage)
      }
      class ReaderPath<Root, Value>(val storage: KeyPath<Root, Reader<Value>>) : KeyPathBase<Root, Value>() {
        override val __kpaths_id get() = storage.__kpaths_id
        override val __kpaths_valueType get() = storage.__kpaths_valueType.requireTypeArgument(0)
        override fun get(root: Root): Value = storage.get(root).value
      }
      @PathsAdapter(factory = FlagFactory::class) class Flag(val enabled: Boolean)
      object FlagFactory : KPathsAdapterFactory {
        fun <Root> make(path: KeyPath<Root, Flag>): FlagPath<Root> = FlagPath(path)
      }
      class FlagPath<Root>(private val storage: KeyPath<Root, Flag>) : KeyPathBase<Root, Boolean>() {
        override val __kpaths_id get() = storage.__kpaths_id
        override val __kpaths_valueType get() = TypeId.of<Boolean>()
        override fun get(root: Root): Boolean = storage.get(root).enabled
      }
      @Pathable data class Document(val title: Cell<List<String?>>)
      typealias Alias<T> = Cell<T>
      @Pathable data class AliasDocument(val title: Alias<String?>)
      @Pathable data class Flags(val flag: Flag)
      @Pathable data class Box<T>(val title: Cell<T>)
      @Pathable(copyWritableVals = true) data class Panel<T>(val reader: Reader<T>)
      @Pathable sealed interface Event<out T> {
        data class Message<T>(val title: Cell<T>) : Event<T>
      }
      @Pathable sealed interface ReadScreen {
        data class Showing(val reader: Reader<String?>) : ReadScreen
      }
      fun run(): Boolean {
        val title: CellPath<Document, List<String?>> = Document.Paths.title
        val aliased: CellPath<AliasDocument, String?> = AliasDocument.Paths.title
        val generic: CellPath<Box<String?>, String?> = Box.Paths<String?>().title
        val caseProperty: CellPath<Event.Message<String?>, String?> = Event.Paths<String?>().MessagePaths.title
        val flag: FlagPath<Flags> = Flags.Paths.flag
        val reader: ReaderPath<Panel<List<String?>>, List<String?>> = Panel.Paths<List<String?>>().reader
        val caseReader: ReaderPath<ReadScreen.Showing, String?> = ReadScreen.Paths.ShowingPaths.reader
        return title.get(Document(Cell(listOf(null)))) == listOf(null) &&
          aliased.get(AliasDocument(Cell(null))) == null && aliased.__kpaths_valueType == TypeId.of<String?>() &&
          generic.get(Box(Cell(null))) == null && generic.__kpaths_valueType == TypeId.of<String?>() &&
          generic.__kpaths_id == PathId.property(TypeId.of<Box<String?>>(), "title") &&
          caseProperty.get(Event.Message(Cell(null))) == null &&
          caseProperty.__kpaths_id == PathId.property(TypeId.of<Event.Message<String?>>(), "title") &&
          flag.get(Flags(Flag(true))) &&
          reader.get(Panel(Readable(listOf("value", null)))) == listOf("value", null) &&
          reader.__kpaths_valueType == TypeId.of<List<String?>>() &&
          reader.then(keyPath(List<String?>::size)).get(Panel(Readable(listOf("value", null)))) == 2 &&
          reader.storage is WritableKeyPath<*, *> &&
          caseReader.get(ReadScreen.Showing(Readable(null))) == null
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.DeclarativeFactoryKt").getMethod("run").invoke(null))
  }

  @Test
  fun `generic case catalogs preserve case type parameter bounds`() {
    val result = compileSnippet("BoundedGenericCase.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.CaseKeyPath
      @Pathable sealed interface Loadable<out T : CharSequence> {
        data object Idle : Loadable<Nothing>
        data class Loaded<T : CharSequence>(val value: T) : Loadable<T>
      }
      fun run(): Boolean {
        val path: CaseKeyPath<Loadable<String>, String> = Loadable.Paths<String>().Loaded.value
        return path.embed("hi") == Loadable.Loaded("hi") && path.extract(Loadable.Loaded("hi")) == "hi"
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.BoundedGenericCaseKt").getMethod("run").invoke(null))
  }

  @Test
  fun `generic catalogs preserve renamed property path identity`() {
    val result = compileSnippet("GenericRenamedProperties.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.annotations.PathName
      import dev.kpaths.core.keyPath
      @Pathable data class Box<T>(@PathName("content") val value: T)
      fun run(): Boolean = Box.Paths<String>().content == keyPath(Box<String>::value) &&
        Box.Paths<String>().content.get(Box("hello")) == "hello"
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.GenericRenamedPropertiesKt").getMethod("run").invoke(null))
  }

  @Test
  fun `computed mutable data properties update a copy for concrete and generic roots`() {
    val result = compileSnippet("ComputedDataProperties.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.WritableKeyPath

      @Pathable data class Person(var first: String, var last: String) {
        var fullName: String
          get() = "${'$'}first ${'$'}last"
          set(value) { first = value.substringBefore(' '); last = value.substringAfter(' ') }
      }

      @Pathable data class Box<T>(var value: T) {
        var wrapped: T
          get() = value
          set(newValue) { value = newValue }
      }

      fun run(): Boolean {
        val original = Person("Ada", "Lovelace")
        val name: WritableKeyPath<Person, String> = Person.Paths.fullName
        val updated = name.set(original, "Grace Hopper")
        val box = Box("before")
        val wrapped: WritableKeyPath<Box<String>, String> = Box.Paths<String>().wrapped
        val newBox = wrapped.set(box, "after")
        return name.get(original) == "Ada Lovelace" && original == Person("Ada", "Lovelace") &&
          updated == Person("Grace", "Hopper") && box.value == "before" && newBox.value == "after"
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.ComputedDataPropertiesKt").getMethod("run").invoke(null))
  }

  @Test
  fun `reified extension property can recover generic case path identity`() {
    val result = compileSnippet("TemporaryReifiedProperty.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.PartialCaseKeyPath
      @Pathable sealed interface Loadable<out T> {
        data object Idle : Loadable<Nothing>
        data class Loaded<T>(val value: T) : Loadable<T>
      }
      inline val <reified T> Loadable<T>.`case`: PartialCaseKeyPath<Loadable<T>>
        get() = Loadable.Paths<T>().caseOf(this)
      fun run(): Boolean {
        val s: Loadable<String> = Loadable.Idle
        val i: Loadable<Int> = Loadable.Idle
        return s.`case` == Loadable.Paths<String>().Idle.unit &&
          i.`case` == Loadable.Paths<Int>().Idle.unit && s.`case` != i.`case`
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.TemporaryReifiedPropertyKt").getMethod("run").invoke(null))
  }

  @Test
  fun `generic case property catalog names diagnose collisions`() {
    val result = compileSnippet("GenericPropertyCatalogCollision.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.annotations.PathsName
      @Pathable sealed interface Event<out T> {
        @PathsName("Fields") data class First<T>(val value: T) : Event<T>
        @PathsName("Fields") data class Second<T>(val value: T) : Event<T>
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "Cannot generate paths because 'Fields' would name both")
  }

  @Test
  fun `generic case property catalogs support concrete cases and reference classes`() {
    val result = compileSnippet("GenericCasePropertyKinds.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.ReferenceWritableKeyPath
      import dev.kpaths.core.WritableKeyPath
      @Pathable(copyWritableVals = true) sealed interface Result<out T> {
        data class Failure(val message: String) : Result<Nothing>
        class Reference<T>(var value: T) : Result<T>
        data class Wrapped<T>(val value: T) : Result<T>
      }
      fun run(): Boolean {
        val failure: WritableKeyPath<Result.Failure, String> = Result.Paths<String>().FailurePaths.message
        val reference: ReferenceWritableKeyPath<Result.Reference<String?>, String?> =
          Result.Paths<String?>().ReferencePaths.value
        val original = Result.Failure("old")
        val holder = Result.Reference<String?>(null)
        val wrapped: WritableKeyPath<Result.Wrapped<String>, String> = Result.Paths<String>().WrappedPaths.value
        reference.setInPlace(holder, "updated")
        return failure.set(original, "new") == Result.Failure("new") && original.message == "old" &&
          holder.value == "updated" && wrapped.set(Result.Wrapped("old"), "new") == Result.Wrapped("new")
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.GenericCasePropertyKindsKt").getMethod("run").invoke(null))
  }

  @Test
  fun `generic sealed case property catalogs preserve case types and copy semantics`() {
    val result = compileSnippet("GenericCaseProperties.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.annotations.CaseName
      import dev.kpaths.annotations.PathName
      import dev.kpaths.annotations.PathsName
      import dev.kpaths.core.WritableKeyPath
      import dev.kpaths.core.keyPath
      @Pathable sealed interface Result<out A, out B> {
        @CaseName("Entry") @PathsName("Fields")
        data class Flipped<X, Y>(@PathName("content") var first: X, val second: Y) : Result<Y, X>
      }
      fun run(): Boolean {
        val path: WritableKeyPath<Result.Flipped<Int, String?>, Int> =
          Result.Paths<String?, Int>().Fields.content
        val original = Result.Flipped<Int, String?>(1, null)
        return path(original) == 1 && path == keyPath(Result.Flipped<Int, String?>::first) &&
          path.set(original, 2) == Result.Flipped<Int, String?>(2, null) && original.first == 1
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.GenericCasePropertiesKt").getMethod("run").invoke(null))
  }

  @Test
  fun `generic case path implementation names diagnose collisions`() {
    val result = compileSnippet("GenericCaseNameCollision.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable sealed interface Event<out T> {
        data class Value<T>(val value: T) : Event<T>
        data object ValueCasePath : Event<Nothing>
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "Cannot generate paths because 'ValueCasePath' would name both")
  }

  @Test
  fun `generic case catalogs warn when payloads cannot be represented`() {
    val result = compileSnippet("IncompleteGenericCatalog.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable sealed interface Event<out T> {
        data object Idle : Event<Nothing>
        data class Many<T>(val a: T, val b: Int, val c: Int, val d: Int) : Event<T>
      }
      fun run(): Boolean = Event.Paths<String>().allCasePaths == listOf(Event.Paths<String>().Idle.unit)
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertContains(result.messages, "The generated case catalog omits 'Many'")
    assertEquals(true, result.classLoader.loadClass("example.IncompleteGenericCatalogKt").getMethod("run").invoke(null))
  }

  @Test
  fun `generic sealed cases expose directly embeddable nullable payload paths`() {
    val result = compileSnippet("GenericCasePayload.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.CaseKeyPath
      import dev.kpaths.core.CaseMatch
      import dev.kpaths.core.TypeId
      import dev.kpaths.core.PartialCaseKeyPath
      import dev.kpaths.core.caseKeyPath
      import dev.kpaths.core.PathId
      import dev.kpaths.core.PathSegment
      @Pathable sealed interface Event<out T> {
        data object Idle : Event<Nothing>
        data class Value<T>(val value: T) : Event<T>
        data class PairValue<T>(val id: Int, val value: T) : Event<T>
        data class TripleValue<T>(val id: Int, val value: T, val active: Boolean) : Event<T>
      }
      fun run(): Boolean {
        val path: CaseKeyPath<Event<String?>, String?> = Event.Paths<String?>().Value.value
        val idle: CaseKeyPath<Event<String?>, Unit> = Event.Paths<String?>().Idle.unit
        val pair: CaseKeyPath<Event<String?>, Pair<Int, String?>> = Event.Paths<String?>().PairValue.payload
        val triple: CaseKeyPath<Event<String?>, Triple<Int, String?, Boolean>> = Event.Paths<String?>().TripleValue.payload
        val bound = Event.Paths<String?>().PairValue.valueFor(id = 1)
        val boundTriple = Event.Paths<String?>().TripleValue.valueFor(id = 1, active = true)
        val catalog = Event.Paths<String?>()
        val all: List<PartialCaseKeyPath<Event<String?>>> = catalog.allCasePaths
        val manual = caseKeyPath<Event<String?>, Event.Value<String?>, String?>(
          extract = Event.Value<String?>::value,
          embed = { Event.Value(it) },
        )
        val boundId = PathId.case(TypeId.of<Event<String?>>(), "PairValue")
          .appending(PathId.property(TypeId.of<Event.PairValue<String?>>(), "value"))
          .appending(PathSegment.Key(1))
        return all == listOf(idle, path, pair, triple) && catalog.caseOf(Event.Value(null)) == path && path == manual &&
          path.match(Event.Value(null)) == CaseMatch.Matched(null) &&
          path.match(Event.Idle) == CaseMatch.NoMatch && path.embed("hello") == Event.Value("hello") &&
          path.__kpaths_valueType == TypeId.of<String?>() &&
          idle.match(Event.Idle) == CaseMatch.Matched(Unit) && idle.embed(Unit) == Event.Idle &&
          pair.match(Event.PairValue(1, null)) == CaseMatch.Matched(1 to null) &&
          pair.__kpaths_valueType == TypeId.of<Pair<Int, String?>>() &&
          pair.embed(2 to "hi") == Event.PairValue(2, "hi") &&
          bound.match(Event.PairValue(1, null)) == CaseMatch.Matched(null) &&
          bound.__kpaths_id == boundId && bound.__kpaths_valueType == TypeId.of<String?>() &&
          bound.match(Event.PairValue(2, "no")) == CaseMatch.NoMatch &&
          bound.embed("bound") == Event.PairValue(1, "bound") &&
          triple.match(Event.TripleValue(1, null, true)) == CaseMatch.Matched(Triple(1, null, true)) &&
          triple.__kpaths_valueType == TypeId.of<Triple<Int, String?, Boolean>>() &&
          boundTriple.match(Event.TripleValue(1, null, true)) == CaseMatch.Matched(null) &&
          boundTriple.match(Event.TripleValue(1, null, false)) == CaseMatch.NoMatch &&
          boundTriple.embed("bound") == Event.TripleValue(1, "bound", true) &&
          triple.embed(Triple(2, "hi", false)) == Event.TripleValue(2, "hi", false)
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.GenericCasePayloadKt").getMethod("run").invoke(null))
  }

  @Test
  fun `generic whole case paths map reordered parameters and nullable arguments`() {
    val result = compileSnippet("ReorderedGenericCase.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.CaseKeyPath
      import dev.kpaths.core.CaseMatch
      import dev.kpaths.core.TypeId
      import dev.kpaths.core.caseKeyPath
      @Pathable sealed interface Result<out A, out B> {
        data class Flipped<X, Y>(val first: X, val second: Y) : Result<Y, X>
      }
      fun run(): Boolean {
        val path: CaseKeyPath<Result<String?, Int>, Result.Flipped<Int, String?>> =
          Result.Paths<String?, Int>().Flipped
        val value = Result.Flipped<Int, String?>(1, null)
        val payload: CaseKeyPath<Result<String?, Int>, Pair<Int, String?>> =
          Result.Paths<String?, Int>().Flipped.payload
        return path.match(value) == CaseMatch.Matched(value) && path.embed(value) == value &&
          path.__kpaths_valueType == TypeId.of<Result.Flipped<Int, String?>>() &&
          path == caseKeyPath<Result<String?, Int>, Result.Flipped<Int, String?>>() &&
          payload.match(value) == CaseMatch.Matched(1 to null) &&
          payload.__kpaths_valueType == TypeId.of<Pair<Int, String?>>() &&
          payload.embed(2 to "hello") == Result.Flipped(2, "hello")
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.ReorderedGenericCaseKt").getMethod("run").invoke(null))
  }

  @Test
  fun `generic sealed object cases expose whole case paths`() {
    val result = compileSnippet("GenericObjectCase.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.annotations.CaseName
      import dev.kpaths.core.CaseKeyPath
      import dev.kpaths.core.CaseMatch
      import dev.kpaths.core.TypeId
      import dev.kpaths.core.caseKeyPath
      typealias EventAlias<T> = Event<T>
      @Pathable sealed interface Event<out T> {
        @CaseName("Waiting") data object Idle : Event<Nothing>
        data class Failure(val message: String) : Event<Nothing>
        data class Value<T>(val value: T) : EventAlias<T>
      }
      fun run(): Boolean {
        val path: CaseKeyPath<Event<String>, Event.Idle> = Event.Paths<String>().Waiting
        val failure: CaseKeyPath<Event<String>, Event.Failure> = Event.Paths<String>().Failure
        val value: CaseKeyPath<Event<String>, Event.Value<String>> = Event.Paths<String>().Value
        return path.match(Event.Idle) == CaseMatch.Matched(Event.Idle) &&
          path.match(Event.Value("a")) == CaseMatch.NoMatch && path.embed(Event.Idle) == Event.Idle &&
          path == caseKeyPath<Event<String>, Event.Idle>() &&
          failure.embed(Event.Failure("oops")) == Event.Failure("oops") &&
          failure == caseKeyPath<Event<String>, Event.Failure>() &&
          value.match(Event.Value("a")) == CaseMatch.Matched(Event.Value("a")) &&
          value.embed(Event.Value("b")) == Event.Value("b") &&
          value == caseKeyPath<Event<String>, Event.Value<String>>() &&
          path.__kpaths_valueType == TypeId.of<Event.Idle>() &&
          path.__kpaths_id != Event.Paths<Int>().Waiting.__kpaths_id
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.GenericObjectCaseKt").getMethod("run").invoke(null))
  }

  @Test
  fun `generic sealed case policy accepts forwarded and covariant Nothing cases`() {
    val result = compileSnippet("ValidGenericCases.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable sealed interface Event<out T> {
        data object Idle : Event<Nothing>
        data class Value<T>(val value: T) : Event<T>
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `generic sealed variants diagnose unforwarded and specialized parameters`() {
    val result = compileSnippet("InvalidGenericCases.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable sealed interface Event<T> {
        data class Fixed(val value: String) : Event<String>
        data class Nested(val value: String) : Event<List<String>>
        data class Extra<T, U>(val value: U) : Event<T>
      }
      val attempted = Event.Paths<String>().Fixed
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "where the generic root requires 'T'")
    assertContains(result.messages, "example.Event<kotlin.collections.List<kotlin.String>>")
    assertContains(result.messages, "type parameter(s) U cannot be determined from the sealed root")
  }

  @Test
  fun `named annotation arguments generate renamed property and case paths`() {
    val result = compileSnippet("NamedPathAnnotations.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.annotations.PathName as FieldName
      import dev.kpaths.annotations.CaseName
      import dev.kpaths.annotations.PathsName
      @Pathable data class Person(@FieldName(value = "display") val name: String)
      @Pathable sealed interface Event {
        @CaseName(value = "MessageCase")
        @PathsName(value = "MessageFields")
        data class Message(@FieldName(value = "body") val text: String) : Event
      }
      fun run(): Boolean = Person.Paths.display(Person("hello")) == "hello" &&
        Event.Paths.MessageCase.text.embed("world") == Event.Message("world") &&
        Event.Paths.MessageFields.body(Event.Message("world")) == "world"
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.NamedPathAnnotationsKt").getMethod("run").invoke(null))
  }

  @Test
  fun `KClass catalog factories reject nullable and out of bounds arguments`() {
    val result = compileSnippet("InvalidClassTokens.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable data class Bounded<T : CharSequence>(val value: T)
      @Pathable data class Box<T>(val value: T)
      val wrongBound = Bounded.Paths.of(Int::class).value
      val nullableType = Box.Paths.of<String?>(String::class).value
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "Argument type mismatch")
    assertContains(result.messages, "T : Any")
  }

  @Test
  fun `generic catalog factory rejects type arguments that violate root bounds`() {
    val result = compileSnippet("InvalidGenericBound.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable data class Box<T : CharSequence>(val value: T)
      val invalid = Box.Paths<Int>().value
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
  }

  @Test
  fun `generic catalogs preserve constraints nested types and reference writes`() {
    val result = compileSnippet("BoundedCatalog.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.ReferenceWritableKeyPath
      import dev.kpaths.core.KeyPath
      import dev.kpaths.core.TypeId
      import dev.kpaths.core.WritableKeyPath
      @Pathable data class Record<K : Any, V : CharSequence>(var entries: Map<K, V?>)
      @Pathable class Holder<T>(var value: T)
      @Pathable data class Ordered<T : Comparable<T>>(var value: T)
      fun <T : Any> holderPath(type: kotlin.reflect.KClass<T>): KeyPath<Holder<T>, T> =
        Holder.Paths.of(TType = type).value
      fun run(): Boolean {
        val entries: WritableKeyPath<Record<Int, String>, Map<Int, String?>> =
          Record.Paths<Int, String>().entries
        val old = Record(mapOf(1 to "a"))
        val next = entries.set(old, mapOf(2 to null))
        val value: ReferenceWritableKeyPath<Holder<String?>, String?> = Holder.Paths<String?>().value
        val holder = Holder<String?>(null)
        value.setInPlace(holder, "updated")
        val ordered = Ordered.Paths<Int>().value
        return entries == Record.Paths.of(Int::class, String::class).entries &&
          entries == Record.Paths.of<Int, String>(KTypeId = TypeId.of<Int>(), VTypeId = TypeId.of<String>()).entries &&
          holderPath(String::class) == Holder.Paths<String>().value &&
          ordered == Ordered.Paths.of(Int::class).value &&
          old.entries == mapOf(1 to "a") && next.entries == mapOf(2 to null) &&
          entries.__kpaths_valueType == TypeId.of<Map<Int, String?>>() &&
          value.__kpaths_valueType == TypeId.of<String?>() && holder.value == "updated" &&
          ordered.set(Ordered(1), 2) == Ordered(2)
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.BoundedCatalogKt").getMethod("run").invoke(null))
  }

  @Test
  fun `generic catalogs preserve type arguments nullability and copy writes`() {
    val result = compileSnippet("GenericCatalog.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.PathId
      import dev.kpaths.core.TypeId
      import dev.kpaths.core.WritableKeyPath
      import dev.kpaths.core.keyPath
      @Pathable data class Box<T>(var value: T)
      fun <T> explicit(token: TypeId): WritableKeyPath<Box<T>, T> = Box.Paths.of<T>(token).value
      fun run(): Boolean {
        val catalog = Box.Paths<String>()
        val text: WritableKeyPath<Box<String>, String> = catalog.value
        val nullable = Box.Paths<String?>().value
        val number = Box.Paths<Int>().value
        val original = Box("before")
        val nested = explicit<List<String?>>(TypeId.of<List<String?>>())
        return nested == Box.Paths<List<String?>>().value &&
          nested.__kpaths_valueType == TypeId.of<List<String?>>() &&
          text === catalog.value && text == Box.Paths.of<String>().value &&
          text == Box.Paths.of(String::class).value &&
          text == keyPath(Box<String>::value) &&
          text(original) == "before" && text.set(original, "after") == Box("after") &&
          original.value == "before" && text.__kpaths_valueType == TypeId.of<String>() &&
          nullable.__kpaths_valueType == TypeId.of<String?>() &&
          text.__kpaths_id == PathId.property(TypeId.of<Box<String>>(), "value") &&
          nullable.__kpaths_id == PathId.property(TypeId.of<Box<String?>>(), "value") &&
          text.__kpaths_id != nullable.__kpaths_id && text.__kpaths_id != number.__kpaths_id
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.GenericCatalogKt").getMethod("run").invoke(null))
  }

  @Test
  fun `generic variants of concrete roots receive the shared diagnostic`() {
    val result = compileSnippet("GenericVariant.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable sealed interface Event {
        data class Item<T>(val value: T) : Event
        data object Idle : Event
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] Cannot generate 'Item' because this non-generic sealed root has a generic variant.")
  }

  @Test
  fun `bare companion before a case is diagnosed instead of silently dropping it`() {
    val result = compileSnippet("BareCompanion.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable sealed interface Event {
        companion object
        // The next keyword must not be mistaken for the companion's name.
        data object Idle : Event
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] An unnamed `companion object` must be the final member")
  }

  @Test
  fun `named braced and final bare companions remain valid`() {
    val result = compileSnippet("ValidCompanions.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable sealed interface Named {
        companion object Factory
        data object Idle : Named
      }
      @Pathable sealed interface Braced {
        companion object {}
        data object Idle : Braced
      }
      @Pathable sealed interface Final {
        data object Idle : Final
        companion object
      }
      fun run(): Boolean = Named.Paths.Idle.unit.embed(Unit) == Named.Idle &&
        Braced.Paths.Idle.unit.embed(Unit) == Braced.Idle &&
        Final.Paths.Idle.unit.embed(Unit) == Final.Idle
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.ValidCompanionsKt").getMethod("run").invoke(null))
  }

  @Test
  fun `direct sealed interface variants receive the shared unsupported case diagnostic`() {
    val result = compileSnippet("UnsupportedVariant.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      typealias EventAlias = Event
      @Pathable sealed interface Event {
        sealed interface Child : EventAlias
        interface Unrelated
        data object Idle : Event
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] Sealed variant 'Child' must be a class or object.")
    assertTrue(!result.messages.contains("Sealed variant 'Unrelated'"), result.messages)
  }

  @Test
  fun `sealed cases expose property paths with copy semantics`() {
    val result = compileSnippet("CaseProperties.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.annotations.PathName
      import dev.kpaths.annotations.PathsName
      import dev.kpaths.core.keyPath
      import dev.kpaths.core.setting
      @Pathable sealed interface Event {
        @PathsName("MessageFields") data class Message(@PathName("body") var text: String, val id: Int) : Event
      }
      fun run(): Boolean {
        val original = Event.Message("old", 1)
        return Event.Paths.MessageFields.body == keyPath(Event.Message::text) &&
          original.setting(Event.Paths.MessageFields.body, "new") == Event.Message("new", 1) &&
          original.text == "old" && Event.Paths.MessageFields.id(original) == 1
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.CasePropertiesKt").getMethod("run").invoke(null))
  }

  @Test
  fun `non-data sealed case property paths use reference semantics`() {
    val result = compileSnippet("ReferenceCase.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.ReferenceWritableKeyPath
      @Pathable sealed interface Event {
        class Message(var text: String) : Event
      }
      fun run(): Boolean {
        val original = Event.Message("old")
        val path: ReferenceWritableKeyPath<Event.Message, String> = Event.Paths.MessagePaths.text
        path.setInPlace(original, "new")
        return original.text == "new"
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.ReferenceCaseKt").getMethod("run").invoke(null))
  }

  @Test
  fun `multi property cases expose named bound payload paths`() {
    val result = compileSnippet("BoundCase.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.CaseMatch
      @Pathable sealed interface Event {
        data class Message(val id: Int, val text: String) : Event
      }
      fun run(): Boolean {
        val text = Event.Paths.Message.textFor(id = 1)
        return text.match(Event.Message(1, "hi")) == CaseMatch.Matched("hi") &&
          text.match(Event.Message(2, "hi")) == CaseMatch.NoMatch &&
          text.embed("new") == Event.Message(1, "new") &&
          text == Event.Paths.Message.textFor(id = 1) && text != Event.Paths.Message.textFor(id = 2)
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.BoundCaseKt").getMethod("run").invoke(null))
  }

  @Test
  fun `triple payload factories preserve nullable matches and bound order`() {
    val result = compileSnippet("BoundTriple.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.CaseMatch
      import dev.kpaths.core.PathId
      import dev.kpaths.core.PathSegment
      import dev.kpaths.core.TypeId
      @Pathable sealed interface Event {
        data class Item(val id: Int, val text: String?, val selected: Boolean) : Event
        data object Other : Event
      }
      fun run(): Boolean {
        val text = Event.Paths.Item.textFor(id = 7, selected = true)
        val id = Event.Paths.Item.idFor(text = null, selected = true)
        val selected = Event.Paths.Item.selectedFor(id = 7, text = null)
        val expected = PathId.case(TypeId.of<Event>(), "Item")
          .appending(PathId.property(TypeId.of<Event.Item>(), "text"))
          .appending(PathSegment.Key(7)).appending(PathSegment.Key(true))
        return text.match(Event.Item(7, null, true)) == CaseMatch.Matched(null) &&
          text.match(Event.Item(7, null, false)) == CaseMatch.NoMatch &&
          text.match(Event.Other) == CaseMatch.NoMatch &&
          text.embed("new") == Event.Item(7, "new", true) &&
          id.embed(3) == Event.Item(3, null, true) &&
          selected.embed(false) == Event.Item(7, null, false) &&
          text.__kpaths_id == expected && text.__kpaths_valueType == TypeId.of<String?>()
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.BoundTripleKt").getMethod("run").invoke(null))
  }

  @Test
  fun `case property catalog names are validated and collisions diagnosed`() {
    val result = compileSnippet("CaseCatalogCollision.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.annotations.PathsName
      @Pathable sealed interface Event {
        @PathsName("Shared") data class A(val value: String) : Event
        @PathsName("Shared") data class B(val value: String) : Event
        @PathsName("bad name") data class C(val value: String) : Event
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] Cannot generate paths because 'Shared' would name both")
    assertContains(result.messages, "[KPaths] @PathsName value 'bad name'")
  }

  @Test
  fun `CaseName preserves sealed case identity and payload catalog`() {
    val result = compileSnippet("RenamedCase.kt" to """
      package example
      import dev.kpaths.annotations.CaseName
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.caseKeyPath
      @Pathable sealed interface Event {
        @CaseName("MessageCase") data class Message(val text: String) : Event
        @CaseName("RefreshCase") data object Refresh : Event
      }
      fun run(): Boolean = Event.Paths.MessageCase == caseKeyPath<Event, Event.Message>() &&
        Event.Paths.MessageCase.text.embed("hello") == Event.Message("hello") &&
        Event.Paths.allCasePaths == listOf(Event.Paths.MessageCase.text, Event.Paths.RefreshCase.unit) &&
        Event.Message("hello").case == Event.Paths.MessageCase.text &&
        Event.Refresh.case == Event.Paths.RefreshCase.unit
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.RenamedCaseKt").getMethod("run").invoke(null))
  }

  @Test
  fun `CaseName renames enum cases while preserving identity`() {
    val result = compileSnippet("RenamedEnum.kt" to """
      package example
      import dev.kpaths.annotations.CaseName
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.caseKeyPath
      @Pathable enum class Status { @CaseName("Available") Online, Offline }
      fun run(): Boolean = Status.Paths.Available == caseKeyPath(Status.Online) &&
        Status.Paths.Available.embed(Unit) == Status.Online &&
        Status.Paths.allCasePaths == listOf(Status.Paths.Available, Status.Paths.Offline) &&
        Status.Online.case == Status.Paths.Available
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.RenamedEnumKt").getMethod("run").invoke(null))
  }

  @Test
  fun `invalid and colliding CaseName values are diagnosed`() {
    val result = compileSnippet("InvalidCaseNames.kt" to """
      package example
      import dev.kpaths.annotations.CaseName
      import dev.kpaths.annotations.Pathable
      @Pathable sealed interface Event {
        @CaseName("bad name") data object Bad : Event
        @CaseName("Same") data object First : Event
        @CaseName("Same") data object Second : Event
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] @CaseName value 'bad name' must be a single Kotlin identifier")
    assertContains(result.messages, "[KPaths] Cannot generate paths because 'Same' would name both")
  }

  @Test
  fun `CaseName supports escaped Kotlin keywords`() {
    val result = compileSnippet("KeywordCase.kt" to """
      package example
      import dev.kpaths.annotations.CaseName
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.caseKeyPath
      @Pathable sealed interface Event {
        @CaseName("when") data object Refresh : Event
      }
      fun run(): Boolean = Event.Paths.`when` == caseKeyPath<Event, Event.Refresh>() &&
        Event.Refresh.case == Event.Paths.`when`.unit
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.KeywordCaseKt").getMethod("run").invoke(null))
  }

  @Test
  fun `PathName changes generated property spelling without changing identity`() {
    val result = compileSnippet("RenamedProperty.kt" to """
      package example
      import dev.kpaths.annotations.PathName
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.keyPath
      import dev.kpaths.core.setting
      @Pathable data class Person(@PathName("display") var name: String)
      fun run(): Boolean = Person.Paths.display == keyPath(Person::name) &&
        Person("before").setting(Person.Paths.display, "after") == Person("after")
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.RenamedPropertyKt").getMethod("run").invoke(null))
    val bytes = checkNotNull(result.classLoader.getResourceAsStream("example/Person\$Paths\$__KPaths_name_Path.class"))
      .use { it.readBytes() }
    val calls = mutableListOf<String>()
    ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
      override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?) =
        object : MethodVisitor(Opcodes.ASM9) {
          override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
            calls += owner
          }
        }
    }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    assertTrue(calls.none { it.startsWith("kotlin/reflect/") }, calls.toString())
  }

  @Test
  fun `invalid and colliding PathName values are diagnosed`() {
    val result = compileSnippet("InvalidPathNames.kt" to """
      package example
      import dev.kpaths.annotations.PathName
      import dev.kpaths.annotations.Pathable
      @Pathable data class Person(
        @PathName("not a name") val invalid: String,
        @PathName("name") val display: String,
        val name: String,
      )
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] @PathName value 'not a name' must be a single Kotlin identifier")
    assertContains(result.messages, "[KPaths] Cannot generate paths because 'name' would name both")
  }

  @Test
  fun `PathName permits escaped Kotlin keywords`() {
    val result = compileSnippet("KeywordPathName.kt" to """
      package example
      import dev.kpaths.annotations.PathName
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.keyPath
      @Pathable data class Person(@PathName("when") val name: String)
      fun run(): Boolean = Person.Paths.`when` == keyPath(Person::name)
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.KeywordPathNameKt").getMethod("run").invoke(null))
  }

  @Test
  fun `existing nested Paths receives a KPaths diagnostic`() {
    val result = compileSnippet("CatalogCollision.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable data class Person(val name: String) {
        object Paths
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] Cannot generate 'Person.Paths'")
    assertContains(result.messages, "already declares a nested type named 'Paths'")
  }

  @Test
  fun `named companion Paths receives a KPaths diagnostic`() {
    val result = compileSnippet("CompanionCollision.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable data class Person(val name: String) {
        companion object Paths
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] Cannot generate 'Person.Paths'")
  }

  @Test
  fun `a regular companion and an unrelated nested type do not collide`() {
    val result = compileSnippet("CatalogNoCollision.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable data class Person(val name: String) {
        object Helpers
        companion object
      }
      val name = Person.Paths.name
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `unrelated nested classes are not treated as sealed cases`() {
    val result = compileSnippet("Unrelated.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable sealed interface Event {
        data object Idle : Event
        data class Metadata(val title: String)
      }
      fun run(): Boolean = Event.Paths.allCasePaths == listOf(Event.Paths.Idle.unit)
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.UnrelatedKt").getMethod("run").invoke(null))
  }

  @Test
  fun `nested sealed roots resolve case supertypes`() {
    val result = compileSnippet("Nested.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      class Feature {
        @Pathable sealed interface Event {
          data object Idle : Event
          data class Metadata(val title: String)
        }
      }
      fun run(): Boolean = Feature.Event.Paths.allCasePaths == listOf(Feature.Event.Paths.Idle.unit)
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.NestedKt").getMethod("run").invoke(null))
  }

  @Test
  fun `aliased and qualified supertypes identify cases`() {
    val result = compileSnippet("Aliased.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      typealias Alias = Event
      @Pathable sealed interface Event {
        data object Aliased : Alias
        data object Qualified : example.Event
      }
      fun run(): Boolean = Event.Paths.allCasePaths == listOf(Event.Paths.Aliased.unit, Event.Paths.Qualified.unit)
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.AliasedKt").getMethod("run").invoke(null))
  }

  @Test
  fun `unrelated reserved nested names do not trigger case diagnostics`() {
    val result = compileSnippet("UnrelatedReserved.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable sealed interface Event {
        data object Idle : Event
        data class __kpaths_Metadata(val __kpaths_name: String)
      }
      fun run(): Boolean = Event.Paths.allCasePaths == listOf(Event.Paths.Idle.unit)
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `nested case names do not resolve against an unrelated same named type`() {
    val result = compileSnippet(
      "Other.kt" to "package other\ninterface Event",
      "Shadow.kt" to """
        package example
        import dev.kpaths.annotations.Pathable
        import other.Event as OtherEvent
        class Feature {
          @Pathable sealed interface Event {
            data object Idle : Event
            data class Unrelated(val value: Int) : OtherEvent
          }
        }
        fun run(): Boolean = Feature.Event.Paths.allCasePaths == listOf(Feature.Event.Paths.Idle.unit)
      """,
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `case discovery resolves aliases declared in another source file`() {
    val result = compileSnippet(
      "Event.kt" to """
        package example
        import dev.kpaths.annotations.Pathable
        import example.aliases.EventAlias
        @Pathable sealed interface Event {
          data object Idle : EventAlias
        }
        fun run(): Boolean = Event.Paths.allCasePaths == listOf(Event.Paths.Idle.unit)
      """,
      "Alias.kt" to "package example.aliases\ntypealias EventAlias = example.Event",
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `case discovery resolves a generic alias`() {
    val result = compileSnippet("GenericAlias.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      typealias Alias<T> = Event
      @Pathable sealed interface Event {
        data object Idle : Alias<String>
      }
      fun run(): Boolean = Event.Paths.allCasePaths == listOf(Event.Paths.Idle.unit)
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `generic alias declared in another file resolves in its own import scope`() {
    val result = compileSnippet(
      "GenericAlias.kt" to """
        package example
        import dev.kpaths.annotations.Pathable
        import aliases.EventAlias
        @Pathable sealed interface Event {
          data object Idle : EventAlias<String>
        }
        fun run(): Boolean = Event.Paths.allCasePaths == listOf(Event.Paths.Idle.unit)
      """,
      "Alias.kt" to """
        package aliases
        import example.Event
        typealias EventAlias<T> = Event
      """,
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `inaccessible setters and reserved names use shared diagnostics`() {
    val result = compileSnippet("Policy.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable data class Item(val id: Int) {
        var count: Int = 0
          private set
      }
      @Pathable data class Reserved(val __kpaths_rootType: String)
      @Pathable sealed interface Event {
        data object __kpaths_Changed : Event
        data class Changed(val id: Int) : Event {
          var version = 0
            private set
        }
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] Data-class property 'count' must be declared in the primary constructor or have an accessible setter")
    assertContains(result.messages, "[KPaths] Cannot generate paths because property '__kpaths_rootType' uses the reserved '__kpaths_' prefix")
    assertContains(result.messages, "[KPaths] Cannot generate paths because case '__kpaths_Changed' uses the reserved '__kpaths_' prefix")
    assertContains(result.messages, "[KPaths] Data-class property 'version' must be declared in the primary constructor or have an accessible setter")
  }

  @Test
  fun `private class setter generates read only path`() {
    val result = compileSnippet("PrivateSetter.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.KeyPath
      import dev.kpaths.core.ReferenceWritableKeyPath
      @Pathable class Item {
        var count: Int = 0
          private set
      }
      fun run(): Boolean {
        val path: KeyPath<Item, Int> = Item.Paths.count
        return path !is ReferenceWritableKeyPath<*, *>
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.PrivateSetterKt").getMethod("run").invoke(null))
  }

  @Test
  fun `property and case collision uses shared diagnostic`() {
    val result = compileSnippet("Collision.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable sealed class Connection {
        abstract val Connected: String
        data class Connected(override val Connected: String) : Connection()
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] Cannot generate paths because 'Connected' would name both")
    assertContains(result.messages, "the key path for property 'Connected' and the case path for 'Connected'")
  }

  @Test
  fun `case catalog baseline omits unsupported payloads without substituting whole case paths`() {
    val result = compileSnippet(
      "CatalogPolicy.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable sealed interface Event {
        data object Idle : Event
        data class Many(val a: Int, val b: Int, val c: Int, val d: Int) : Event
        class Reference(val value: Int) : Event
      }
      @Pathable sealed interface UnsupportedEvent {
        data class Many(val a: Int, val b: Int, val c: Int, val d: Int) : UnsupportedEvent
      }
      fun run(): Boolean {
        val many = Event.Many(1, 2, 3, 4)
        return Event.Paths.allCasePaths == listOf(Event.Paths.Idle.unit) &&
          runCatching { Event.Paths.caseOf(many) }.exceptionOrNull() is IllegalStateException &&
          runCatching { Event.Paths.caseOf(Event.Reference(1)) }.exceptionOrNull() is IllegalStateException &&
          Event.Paths.Many.embed(many) == many &&
          UnsupportedEvent.Paths.allCasePaths.isEmpty() &&
          runCatching { UnsupportedEvent.Paths.caseOf(UnsupportedEvent.Many(1, 2, 3, 4)) }.exceptionOrNull() is IllegalStateException
      }
      """,
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] The generated case catalog omits 'Many', 'Reference'")
    assertContains(result.messages, "allCasePaths is incomplete")
    assertEquals(true, result.classLoader.loadClass("example.CatalogPolicyKt").getMethod("run").invoke(null))
  }

  @Test
  fun `multi-value case payloads embed match and appear in catalogs`() {
    val result = compileSnippet(
      "Payloads.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.CaseKeyPath
      import dev.kpaths.core.CaseMatch
      import dev.kpaths.core.caseKeyPath
      @Pathable sealed interface Request {
        data object Cancel : Request
        data class Open(val id: String, val animated: Boolean) : Request
        data class Edit(val id: String, val count: Int?, val selected: Boolean) : Request
      }
      val open: CaseKeyPath<Request, Pair<String, Boolean>> = Request.Paths.Open.payload
      val edit: CaseKeyPath<Request, Triple<String, Int?, Boolean>> = Request.Paths.Edit.payload
      val manual = caseKeyPath<Request, Request.Open, _, _>(
        extract1 = Request.Open::id, extract2 = Request.Open::animated, embed = Request::Open,
      )
      fun run(): Boolean =
        open.embed("screen" to true) == Request.Open("screen", true) &&
        open.match(Request.Open("screen", false)) == CaseMatch.Matched("screen" to false) &&
        open.match(Request.Cancel) == CaseMatch.NoMatch &&
        edit.embed(Triple("edit", null, true)) == Request.Edit("edit", null, true) &&
        edit.match(Request.Edit("edit", null, true)) == CaseMatch.Matched(Triple("edit", null, true)) &&
        open == manual && open.hashCode() == manual.hashCode() &&
        Request.Open("screen", false).case == open && Request.Edit("edit", null, true).case == edit &&
        Request.Paths.allCasePaths == listOf(Request.Paths.Cancel.unit, open, edit)
      """,
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.PayloadsKt").getMethod("run").invoke(null))
    val bytes = checkNotNull(
      result.classLoader.getResourceAsStream("example/Request\$Paths\$Open\$__KPaths_payload_CasePath.class"),
    ).use { it.readBytes() }
    val calls = mutableListOf<String>()
    ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
      override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?) =
        object : MethodVisitor(Opcodes.ASM9) {
          override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
            calls += owner
          }
        }
    }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    assertTrue(calls.none { it.startsWith("kotlin/reflect/") || it == "dev/kpaths/core/CaseKeyPathKt" }, calls.toString())
  }

  @Test
  fun `enum conformance survives compilation and dispatches case`() {
    val result = compileSnippet(
      "Snippet.kt" to
      """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.CasePathable

      @Pathable enum class Signal { Red, Green }
      fun run(): Boolean {
        val value: CasePathable<Signal> = Signal.Green
        return value.case == Signal.Paths.Green
      }
      """,
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.SnippetKt").getMethod("run").invoke(null))
    assertTrue(result.classLoader.loadClass("example.Signal").interfaces.any { it.name == "dev.kpaths.core.CasePathable" })
  }

  @Test
  fun `snippet helper reports compiler errors`() {
    val result = compileSnippet("Broken.kt" to "val number: Int = \"wrong\"")
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertTrue(result.messages.contains("type mismatch", ignoreCase = true), result.messages)
  }
}
