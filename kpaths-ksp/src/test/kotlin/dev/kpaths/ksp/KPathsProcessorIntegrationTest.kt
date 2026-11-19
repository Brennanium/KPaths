package dev.kpaths.ksp

import kotlin.test.Test
import dev.knapshots.testing.ksp.assertKsp
import dev.knapshots.testing.ksp.KspProcessorProject
import dev.knapshots.testing.ksp.KspSource
import dev.knapshots.testing.ksp.KspDependency

class KPathsProcessorIntegrationTest {
  @Test
  fun `copy writable values accept a constant annotation argument`() {
    assertKsp(fileName = "CopyWritableConstant.kt", source = """
      package fixture
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.WritableKeyPath
      const val ENABLE_COPY_WRITES = true
      @Pathable(copyWritableVals = ENABLE_COPY_WRITES) data class Note(val body: String)
      val body: WritableKeyPath<Note, String> = NotePaths.body
    """)
  }

  @Test
  fun `declarative adapter reports invalid factory from dependency metadata`() {
    assertKsp(fileName = "InvalidBinaryAdapter.kt", source = """
      package fixture
      import dev.kpaths.annotations.Pathable
      @Pathable data class Document(val title: InvalidBinaryCell<String>)
    """.trimIndent(), succeeds = false, dependencies = listOf(KspDependency("adapters", listOf(KspSource("Reader.kt", """
      package fixture
      import dev.kpaths.annotations.*
      @PathsAdapter(factory = InvalidBinaryFactory::class) class InvalidBinaryCell<T>(val value: T)
      object InvalidBinaryFactory : KPathsAdapterFactory
    """)))), diagnostics = listOf("Invalid @PathsAdapter on 'title': the adapter factory must declare exactly one make function."))
  }

  @Test
  fun `declarative adapter diagnoses a storage type alias`() {
    assertKsp(fileName = "AdapterAlias.kt", source = """
      package fixture
      import dev.kpaths.annotations.*
      import dev.kpaths.core.*
      @PathsAdapter(factory = Factory::class) class Cell<T>(val value: T)
      object Factory : KPathsAdapterFactory {
        fun <R, V> make(path: KeyPath<R, Cell<V>>): KeyPath<R, Cell<V>> = path
      }
      typealias AliasedCell<T> = Cell<T>
      typealias NestedAlias<T> = AliasedCell<T>
      @Pathable data class Document(val title: AliasedCell<String>, val nested: NestedAlias<String>)
    """, succeeds = false, diagnostics = listOf(
      "Invalid @PathsAdapter on 'title': type aliases of adapted storage types are not yet supported.",
      "Invalid @PathsAdapter on 'nested': type aliases of adapted storage types are not yet supported.",
    ))
  }

  @Test
  fun `declarative adapter reports precise factory shape errors`() {
    assertKsp(
      fileName = "AdapterShapes.kt",
      source = """
      package fixture
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
    """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "Invalid @PathsAdapter on 'first': make must return a KeyPath subtype.",
        "Invalid @PathsAdapter on 'second': make type parameters must not have explicit bounds.",
        "Invalid @PathsAdapter on 'third': storage type has unsupported projections or nullable type parameters.",
      ),
    )
  }

  @Test
  fun `declarative adapter diagnoses a nullable wrapper`() {
    assertKsp(
      fileName = "NullableWrapper.kt",
      source = """
      package fixture
      import dev.kpaths.annotations.*
      import dev.kpaths.core.*
      @PathsAdapter(factory = Factory::class) class Cell<T>(val value: T)
      object Factory : KPathsAdapterFactory {
        fun <R, V> make(path: KeyPath<R, Cell<V>>): KeyPath<R, Cell<V>> = path
      }
      @Pathable data class Document(val title: Cell<String>?)
    """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "make's KeyPath parameter does not match the generated root and storage types",
      ),
    )
  }

  @Test
  fun `declarative adapter diagnoses an overloaded factory`() {
    assertKsp(
      fileName = "InvalidAdapter.kt",
      source = """
      package fixture
      import dev.kpaths.annotations.*
      import dev.kpaths.core.*
      @PathsAdapter(factory = Factory::class) class Cell<T>(val value: T)
      object Factory : KPathsAdapterFactory {
        fun <R, V> make(path: KeyPath<R, Cell<V>>): KeyPath<R, Cell<V>> = path
        fun make(value: String): String = value
      }
      @Pathable data class Document(val title: Cell<String>)
    """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "the adapter factory must declare exactly one make function",
      ),
    )
  }

  @Test
  fun `declarative adapter exposes a specialized path through the companion facade`() {
    assertKsp(fileName = "DeclarativeAdapter.kt", source = """
      package fixture
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
      @PathsAdapter(factory = FlagFactory::class) class Flag(val enabled: Boolean)
      object FlagFactory : KPathsAdapterFactory {
        fun <Root> make(path: KeyPath<Root, Flag>): FlagPath<Root> = FlagPath(path)
      }
      class FlagPath<Root>(private val storage: KeyPath<Root, Flag>) : KeyPathBase<Root, Boolean>() {
        override val __kpaths_id get() = storage.__kpaths_id
        override val __kpaths_valueType get() = TypeId.of<Boolean>()
        override fun get(root: Root): Boolean = storage.get(root).enabled
      }
      @Pathable data class Document(val title: Cell<List<String?>>) { companion object }
      @Pathable data class Flags(val flag: Flag) { companion object }
      val title: CellPath<Document, List<String?>> = Document.Paths.title
      @Pathable data class Box<T>(val title: Cell<T>) { companion object Factory }
      val generic: CellPath<Box<String?>, String?> = Box.Paths.of<String?>().title
      @Pathable(copyWritableVals = true) data class Panel<T>(val reader: Reader<T>) { companion object Factory }
      val reader: ReaderPath<Panel<List<String?>>, List<String?>> = Panel.Paths.of<List<String?>>().reader
      @Pathable sealed interface Screen {
        data class Showing(val reader: Reader<String?>) : Screen
      }
      val caseReader: ReaderPath<Screen.Showing, String?> = ScreenPaths.ShowingPaths.reader
      fun main() {
        check(title.get(Document(Cell(listOf(null, "value")))) == listOf(null, "value"))
        check(generic.get(Box(Cell(null))) == null)
        check(generic.__kpaths_valueType == TypeId.of<String?>())
        check(generic.__kpaths_id == PathId.property(TypeId.of<Box<String?>>(), "title"))
        val flag: FlagPath<Flags> = Flags.Paths.flag
        check(flag.get(Flags(Flag(true))))
        check(reader.get(Panel(Readable(listOf("hello", null)))) == listOf("hello", null))
        check(reader.__kpaths_valueType == TypeId.of<List<String?>>())
        check(reader.then(keyPath(List<String?>::size)).get(Panel(Readable(listOf("hello", null)))) == 2)
        check(reader.storage is WritableKeyPath<*, *>)
        check(caseReader.get(Screen.Showing(Readable(null))) == null)
      }
    """.trimIndent(), mainClass = "fixture.DeclarativeAdapterKt", dependencies = listOf(KspDependency("adapters", listOf(KspSource("Reader.kt", """
      package fixture
      import dev.kpaths.annotations.*
      import dev.kpaths.core.*
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
    """)))))
  }

  @Test
  fun `warns when canonical case catalog cannot cover every case`() {
    assertKsp(
      fileName = "IncompleteCatalog.kt",
      source = """
        package fixture
        import dev.kpaths.annotations.Pathable
        @Pathable sealed interface Event {
          data object Idle : Event
          data class Many(val a: Int, val b: Int, val c: Int, val d: Int) : Event
          class Reference(val value: Int) : Event
        }
      """.trimIndent(),
      diagnostics = listOf(
        "[KPaths] The generated case catalog omits 'Many', 'Reference'",
        "allCasePaths is incomplete",
      ),
    )
  }

  @Test
  fun `external processor can replace an eligible generated property path implementation`() {
    assertKsp(
      source = """
        package fixture

        import dev.kpaths.annotations.PathsAdapter
        import dev.kpaths.annotations.KPathsAdapterFactory
        import dev.kpaths.core.PathId
        import dev.kpaths.core.TypeId
        import dev.kpaths.core.WritableKeyPath

        @Target(AnnotationTarget.CLASS)
        annotation class PathAdapted

        object CellPathFactory : KPathsAdapterFactory

        @PathsAdapter(factory = CellPathFactory::class)
        class Cell<Value>(var value: Value)

        interface WritableCellValueKeyPath<Root, Value> : WritableKeyPath<Root, Value>

        abstract class WritableCellValueKeyPathBase<Root, Value>(
          override val __kpaths_id: PathId,
          override val __kpaths_valueType: TypeId,
          private val resolveCell: (Root) -> Cell<Value>,
        ) : WritableCellValueKeyPath<Root, Value> {
          override fun get(root: Root): Value = resolveCell(root).value
          override fun set(root: Root, value: Value): Root {
            resolveCell(root).value = value
            return root
          }
        }

        data class Profile(val name: String)

        @PathAdapted
        data class State(val profile: Cell<Profile>) {
          companion object
        }

        @PathAdapted
        data class Box<Value>(val cell: Cell<Value>) {
          companion object Paths
        }

        @PathAdapted
        sealed interface Destination {
          data class Sheet(val profile: Cell<Profile>) : Destination

          companion object
        }

        fun verifyAdapter() {
          val path: WritableCellValueKeyPath<State, Profile> = State.Paths.profile
          val state = State(Cell(Profile("before")))
          check(path.get(state) == Profile("before"))
          check(path.set(state, Profile("after")) === state)
          check(state.profile.value == Profile("after"))

          val boxPath: WritableCellValueKeyPath<Box<Profile>, Profile> =
            Box.Paths.of<Profile>().cell

          val sheetPath: WritableCellValueKeyPath<Destination.Sheet, Profile> =
            Destination.Paths.SheetPaths.profile
          val sheet = Destination.Sheet(Cell(Profile("before")))
          check(sheetPath.set(sheet, Profile("after")) === sheet)
          check(sheet.profile.value == Profile("after"))
        }

        fun main() = verifyAdapter()
      """,
      processorProjects = listOf(KspProcessorProject(
        name = "adapterProcessor",
        providers = listOf("fixtureadapter.SharedProcessorProvider"),
        sources = listOf(KspSource("SharedProcessor.kt", """
        package fixtureadapter

        import com.google.devtools.ksp.processing.CodeGenerator
        import com.google.devtools.ksp.processing.KSPLogger
        import com.google.devtools.ksp.processing.Resolver
        import com.google.devtools.ksp.processing.SymbolProcessor
        import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
        import com.google.devtools.ksp.processing.SymbolProcessorProvider
        import com.google.devtools.ksp.symbol.KSAnnotated
        import com.google.devtools.ksp.symbol.KSClassDeclaration
        import com.squareup.kotlinpoet.ClassName
        import com.squareup.kotlinpoet.CodeBlock
        import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
        import com.squareup.kotlinpoet.ParameterizedTypeName
        import dev.kpaths.ksp.codegen.KPathsCatalogGenerator
        import dev.kpaths.ksp.codegen.KPathsCatalogRequest
        import dev.kpaths.ksp.codegen.KPathsPathCapability
        import dev.kpaths.ksp.codegen.KPathsPropertyPathAdapter
        import dev.kpaths.ksp.codegen.KPathsPropertyPathRecipe

        private const val marker = "fixture.PathAdapted"

        class SharedProcessorProvider : SymbolProcessorProvider {
          override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
            SharedProcessor(environment.codeGenerator, environment.logger)
        }

        private class SharedProcessor(
          codeGenerator: CodeGenerator,
          private val logger: KSPLogger,
        ) : SymbolProcessor {
          private val generator = KPathsCatalogGenerator(codeGenerator, logger)

          override fun process(resolver: Resolver): List<KSAnnotated> =
            generator.generate(
              resolver,
              resolver.getSymbolsWithAnnotation(marker)
                .filterIsInstance<KSClassDeclaration>()
                .map { declaration ->
                  KPathsCatalogRequest(
                    declaration = declaration,
                    generatedName = declaration.simpleName.asString() + "Paths",
                    annotationQualifiedName = marker,
                    annotationDisplayName = "@PathAdapted",
                    propertyPathAdapters = listOf(CellValuePathAdapter),
                  )
                }
                .toList(),
            )
        }

        private object CellValuePathAdapter : KPathsPropertyPathAdapter {
          override fun pathRecipe(context: dev.kpaths.ksp.codegen.KPathsPropertyPathContext): KPathsPropertyPathRecipe? {
            val sharedType = context.declaration.type.resolve()
            if (context.adapterFactoryQualifiedName != "fixture.CellPathFactory") return null
            if (sharedType.declaration.qualifiedName?.asString() != "fixture.Cell") return null
            val valueType = sharedType.arguments.singleOrNull()?.type?.resolve() ?: return null
            val value = (context.sourceValueType as? ParameterizedTypeName)
              ?.typeArguments
              ?.singleOrNull()
              ?: return null
            return KPathsPropertyPathRecipe(
              publicPathType = ClassName("fixture", "WritableCellValueKeyPath")
                .parameterizedBy(context.rootType, value),
              implementationBaseType = ClassName("fixture", "WritableCellValueKeyPathBase")
                .parameterizedBy(context.rootType, value),
              constructorArguments = listOf(
                context.pathId(value),
                context.typeId(valueType),
                CodeBlock.of("{ root -> %L }", context.propertyAccess(CodeBlock.of("root"))),
              ),
              capability = KPathsPathCapability.WRITABLE,
            )
          }
        }
      """)),
      )),
      mainClass = "fixture.MainKt",
    )
  }

  @Test
  fun `reports an unnamed bare companion before later class members`() {
    assertKsp(
      fileName = "AmbiguousBareCompanion.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        sealed interface Event {
          companion object

          data object Idle : Event
        }
      """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "AmbiguousBareCompanion.kt:",
        "[KPaths] An unnamed `companion object` must be the final member",
        "Move it to the end of the class body",
      ),
    )
  }

  @Test
  fun `reports companion Paths member collisions`() {
    assertKsp(
      fileName = "CompanionPathsCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        data class Person(val name: String) {
          companion object {
            val Paths = "existing"
          }
        }
      """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "CompanionPathsCollision.kt:",
        "[KPaths] Cannot generate Person.Companion.Paths",
        "already declares a member named 'Paths'",
      ),
    )
  }

  @Test
  fun `reports companion Paths extension collisions`() {
    assertKsp(
      fileName = "CompanionPathsExtensionCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        data class Person(val name: String) {
          companion object
        }

        val Person.Companion.Paths: String
          get() = "existing"
      """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "CompanionPathsExtensionCollision.kt:",
        "[KPaths] Cannot generate Person.Companion.Paths because the top-level extension",
        "already declares it",
      ),
    )
  }

  @Test
  fun `reports generic named Paths factory collisions`() {
    assertKsp(
      fileName = "GenericCompanionPathsFunctionCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        data class Box<Value>(val value: Value) {
          companion object Paths {
            fun <Value> of(): String = "existing"
          }
        }
      """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "GenericCompanionPathsFunctionCollision.kt:",
        "[KPaths] Cannot generate Box.Paths.of",
        "already declares a member named 'of'",
      ),
    )
  }

  @Test
  fun `reports invalid generated case and property catalog names`() {
    assertKsp(
      fileName = "InvalidCaseNames.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.CaseName
        import dev.kpaths.annotations.Pathable

        @Pathable
        sealed interface Event {
          @CaseName("not a name")
          data object Idle : Event
        }
      """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "InvalidCaseNames.kt:",
        "[KPaths] @CaseName value 'not a name'",
        "must be a single Kotlin identifier",
      ),
    )
  }

  @Test
  fun `reports invalid generated property names`() {
    assertKsp(
      fileName = "InvalidPathName.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable
        import dev.kpaths.annotations.PathName

        @Pathable
        data class Profile(
          @PathName("not a name") val legalName: String,
        )
      """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "InvalidPathName.kt:",
        "[KPaths] @PathName value 'not a name'",
      ),
    )
  }

  @Test
  fun `reports colliding generated property names`() {
    assertKsp(
      fileName = "PathNameCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable
        import dev.kpaths.annotations.PathName

        @Pathable
        data class Profile(
          @PathName("name") val legalName: String,
          val name: String,
        )
      """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "PathNameCollision.kt:",
        "'name' would name both",
        "property 'legalName'",
        "property 'name'",
      ),
    )
  }

  @Test
  fun `reports generated case member and property catalog collisions`() {
    assertKsp(
      fileName = "CaseNameCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.CaseName
        import dev.kpaths.annotations.Pathable
        import dev.kpaths.annotations.PathsName

        @Pathable
        sealed interface Event {
          @CaseName("MessagePaths")
          data object Idle : Event

          @PathsName("MessagePaths")
          data class Message(val text: String) : Event
        }
      """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "CaseNameCollision.kt:",
        "[KPaths] Cannot generate paths because 'MessagePaths' would name both",
        "the case path for 'Idle'",
        "the property-path namespace for case 'Message'",
      ),
    )
  }

  @Test
  fun `reports an actionable error at Pathable for unsupported generic sealed variants`() {
    assertKsp(
      fileName = "InvalidLoadable.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        sealed interface InvalidLoadable<Value> {
          data object Idle : InvalidLoadable<Nothing>
        }
      """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "InvalidLoadable.kt:",
        "[KPaths] Cannot generate 'Idle'",
        "must forward that type parameter, or refine it to Nothing through an `out` parameter",
      ),
    )
  }

  @Test
  fun `reports data class setter that cannot support copy mutation`() {
    assertKsp(
      fileName = "InaccessibleSetter.kt",
      source = """
      package fixture
      import dev.kpaths.annotations.Pathable
      @Pathable data class Item(val id: Int) {
        var count: Int = 0
          private set
      }
    """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "[KPaths] Data-class property 'count' must be declared in the primary constructor or have an accessible setter",
      ),
    )
  }

  @Test
  fun `reports use of KPaths reserved internal prefix at Pathable`() {
    assertKsp(
      fileName = "ReservedPrefix.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        data class ReservedPrefix(val __kpaths_rootType: String)
      """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "ReservedPrefix.kt:",
        "[KPaths] Cannot generate paths because property '__kpaths_rootType'",
        "uses the reserved '__kpaths_' prefix",
      ),
    )
  }

  @Test
  fun `reports property and case path collisions at Pathable`() {
    assertKsp(
      fileName = "PropertyCaseCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        sealed class Connection {
          abstract val Connected: String

          data class Connected(override val Connected: String) : Connection()
        }
      """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "PropertyCaseCollision.kt:",
        "[KPaths] Cannot generate paths because 'Connected' would name both",
        "the key path for property 'Connected' and the case path for 'Connected'",
      ),
    )
  }

  @Test
  fun `reports generated case catalog collisions at Pathable`() {
    assertKsp(
      fileName = "CaseCatalogCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        sealed interface Event {
          data object allCasePaths : Event
        }
      """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "CaseCatalogCollision.kt:",
        "[KPaths] Cannot generate paths because 'allCasePaths' would name both",
        "the case path for 'allCasePaths' and the generated catalog of all case paths",
      ),
    )
  }

  @Test
  fun `reports top level generated namespace collisions at Pathable`() {
    assertKsp(
      fileName = "TopLevelCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        data class Person(val name: String)

        object PersonPaths
      """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "TopLevelCollision.kt:",
        "[KPaths] Cannot generate 'PersonPaths' because it conflicts with existing top-level",
        "type 'fixture.PersonPaths'",
        "@Pathable(\"CustomPersonPaths\")",
      ),
    )
  }

  @Test
  fun `reports duplicate custom generated namespaces at Pathable`() {
    assertKsp(
      fileName = "DuplicateCustomNames.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable("SharedPaths")
        data class First(val value: String)

        @Pathable("SharedPaths")
        data class Second(val value: Int)
      """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "DuplicateCustomNames.kt:",
        "[KPaths] Cannot generate 'SharedPaths' because @Pathable also requests that name",
        "'fixture.First'",
        "'fixture.Second'",
      ),
    )
  }

  @Test
  fun `reports top level type alias collisions at Pathable`() {
    assertKsp(
      fileName = "TypeAliasCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        data class Person(val name: String)

        typealias PersonPaths = String
      """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "TypeAliasCollision.kt:",
        "[KPaths] Cannot generate 'PersonPaths' because it conflicts with existing top-level",
        "type alias 'fixture.PersonPaths'",
      ),
    )
  }

  @Test
  fun `reports reserved prefixes on sealed cases at Pathable`() {
    assertKsp(
      fileName = "ReservedCasePrefix.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        sealed interface ReservedCasePrefix {
          data object __kpaths_rootType : ReservedCasePrefix
        }
      """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "ReservedCasePrefix.kt:",
        "[KPaths] Cannot generate paths because case '__kpaths_rootType'",
        "uses the reserved '__kpaths_' prefix",
      ),
    )
  }

  @Test
  fun `reports generic companion collisions at Pathable`() {
    assertKsp(
      fileName = "CompanionCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        data class Box<Value>(val Companion: Value)
      """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "CompanionCollision.kt:",
        "[KPaths] Cannot generate paths because 'Companion' would name both",
        "the generated generic-path companion and the key path for property 'Companion'",
      ),
    )
  }

  @Test
  fun `reports generated source file collisions at Pathable`() {
    assertKsp(
      fileName = "PersonPaths.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        data class Person(val name: String)
      """.trimIndent(),
      succeeds = false,
      diagnostics = listOf(
        "PersonPaths.kt:",
        "[KPaths] Cannot generate 'PersonPaths.kt' because source file",
        "@Pathable(\"CustomPersonPaths\")",
      ),
    )
  }

}
