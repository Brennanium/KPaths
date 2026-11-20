package dev.kpaths.ksp

import dev.knapshots.testing.ksp.assertKsp
import dev.knapshots.testing.ksp.KspDependency
import dev.knapshots.testing.ksp.KspSource
import dev.knapshots.testing.ksp.KspProcessorProject
import dev.knapshots.testing.compiler.SnapshotExpectation
import kotlin.test.Test

class KnapshotsKspTest {
  @Test fun `generates paths for a data class`() {
    assertKsp(
      source = """
        package fixture
        import dev.kpaths.annotations.Pathable
        @Pathable data class Person(val name: String)
        val name = PersonPaths.name
      """,
      generated = SnapshotExpectation("""
        // FILE: kotlin/fixture/PersonPaths.kt
        package fixture

        /** Generated paths for fixture.Person. */
        public object PersonPaths {
          private val __kpaths_rootType: dev.kpaths.core.TypeId = dev.kpaths.core.TypeId("fixture.Person")

          public object name : dev.kpaths.core.KeyPathBase<fixture.Person, kotlin.String>() {
            override val __kpaths_id: dev.kpaths.core.PathId = dev.kpaths.core.PathId.property(__kpaths_rootType, "name")
            override val __kpaths_valueType: dev.kpaths.core.TypeId = dev.kpaths.core.TypeId("kotlin.String")
            override fun get(root: fixture.Person): kotlin.String = root.name
          }
        }
        """),
    )
  }

  @Test fun `diagnoses invalid generated names`() {
    assertKsp(
      source = """
        package fixture
        import dev.kpaths.annotations.Pathable
        import dev.kpaths.annotations.PathName
        @Pathable data class Person(@PathName("not a name") val name: String)
      """,
    ) {
      generated("""
        // FILE: kotlin/fixture/PersonPaths.kt
        package fixture

        /** Generated paths for fixture.Person. */
        public object PersonPaths {
          private val __kpaths_rootType: dev.kpaths.core.TypeId = dev.kpaths.core.TypeId("fixture.Person")
        }
      """)
      diagnostics { snapshot("""
        package fixture
        import dev.kpaths.annotations.Pathable
        import dev.kpaths.annotations.PathName
        @Pathable data class Person(@PathName("not a name") val name: String)
        error: [KPaths] @PathName value 'not a name' must be a single Kotlin identifier, without a package or dot.
      """) }
    }
  }

  @Test fun `reports invalid adapters in a compiled dependency`() {
    assertKsp(
      source = """
        package fixture
        import dev.kpaths.annotations.Pathable
        @Pathable data class Document(val title: InvalidBinaryCell<String>)
      """,
      dependencies = listOf(KspDependency("adapters", listOf(KspSource("Adapter.kt", """
        package fixture
        import dev.kpaths.annotations.*
        @PathsAdapter(factory = InvalidBinaryFactory::class) class InvalidBinaryCell<T>(val value: T)
        object InvalidBinaryFactory : KPathsAdapterFactory
      """)))),
      succeeds = false,
      diagnostics = listOf("Invalid @PathsAdapter on 'title': the adapter factory must declare exactly one make function."),
    )
  }

  @Test fun `runs generated paths against a type from a compiled dependency`() {
    assertKsp(
      source = """
        package fixture
        import dev.kpaths.annotations.Pathable
        @Pathable data class Document(val name: LibraryName)
        fun main() {
          check(DocumentPaths.name.get(Document(LibraryName("Ada"))).value == "Ada")
        }
      """,
      dependencies = listOf(KspDependency("model", listOf(KspSource("LibraryName.kt", """
        package fixture
        data class LibraryName(val value: String)
      """)))),
      mainClass = "fixture.MainKt",
    )
  }

  @Test fun `reports runtime failure separately from compilation`() {
    assertKsp(
      source = """
        package fixture
        import dev.kpaths.annotations.Pathable
        @Pathable data class Person(val name: String)
        fun main() { check(PersonPaths.name.get(Person("Ada")) == "Grace") }
      """,
      mainClass = "fixture.MainKt",
      executionSucceeds = false,
    )
  }

  @Test fun `builds and runs an additional processor from source`() {
    assertKsp(
      source = """
        package fixture
        fun main() { check(Generated.answer == 42) }
      """,
      processorProjects = listOf(KspProcessorProject(
        name = "extraProcessor",
        providers = listOf("fixtureproc.ExampleProvider"),
        sources = listOf(KspSource("ExampleProcessor.kt", """
          package fixtureproc
          import com.google.devtools.ksp.processing.*
          import com.google.devtools.ksp.symbol.KSAnnotated
          class ExampleProvider : SymbolProcessorProvider {
            override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor = ExampleProcessor(environment.codeGenerator)
          }
          class ExampleProcessor(private val code: CodeGenerator) : SymbolProcessor {
            private var generated = false
            override fun process(resolver: Resolver): List<KSAnnotated> {
              if (!generated) {
                generated = true
                code.createNewFile(Dependencies(false), "fixture", "Generated").bufferedWriter().use {
                  it.write("package fixture\nobject Generated { val answer = 42 }\n")
                }
              }
              return emptyList()
            }
          }
        """)),
      )),
      mainClass = "fixture.MainKt",
    )
  }
}
