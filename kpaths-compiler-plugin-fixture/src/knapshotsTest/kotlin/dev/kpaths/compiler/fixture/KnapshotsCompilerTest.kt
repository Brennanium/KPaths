package dev.kpaths.compiler.fixture

import dev.knapshots.testing.compiler.SnapshotExpectation
import dev.knapshots.testing.compiler.assertCompiler
import kotlin.test.Test

class KnapshotsCompilerTest {
  @Test fun `generates a property path`() {
    assertCompiler("""
      import dev.kpaths.annotations.Pathable
      @Pathable data class Person(val name: String)
      val name = Person.Paths.name
    """) { succeeds() }
  }

  @Test fun `diagnoses an invalid property name`() {
    assertCompiler("""
      import dev.kpaths.annotations.PathName
      import dev.kpaths.annotations.Pathable
      @Pathable data class Person(@PathName("not a name") val name: String)
    """) {
      fir("""
        KPaths resolved FIR callables
      """)
      diagnostics { snapshot("""
        import dev.kpaths.annotations.PathName
        import dev.kpaths.annotations.Pathable
        @Pathable data class Person(@PathName("not a name") val name: String)
                                                                ^~~~
        error: [KPaths] @PathName value 'not a name' must be a single Kotlin identifier, without a package or dot.
      """) }
    }
  }

  @Test fun `compares generated FIR and IR`() {
    assertCompiler(
      source = """
        package fixture
        import dev.kpaths.annotations.Pathable
        @Pathable(copyWritableVals = true) data class Note(val body: String)
        val body = Note.Paths.body
      """,
      fir = SnapshotExpectation("""
        KPaths resolved FIR callables

        fixture/Note.Paths.body
        public final val body: R|dev/kpaths/core/WritableKeyPath<fixture/Note, kotlin/String>|
            public get(): R|dev/kpaths/core/WritableKeyPath<fixture/Note, kotlin/String>|
      """),
      ir = SnapshotExpectation("""
        KPaths post-generation IR

        class fixture.Note.Paths
          extends kotlin.Any
          val body: dev.kpaths.core.WritableKeyPath<fixture.Note, kotlin.String>

        class fixture.Note.Paths.__KPaths_body_Path
          extends dev.kpaths.core.WritableKeyPathBase<fixture.Note, kotlin.String>
          val __kpaths_id: dev.kpaths.core.PathId
          val __kpaths_valueType: dev.kpaths.core.TypeId
          fun get(root: fixture.Note): kotlin.String
          fun get(root: kotlin.Any): kotlin.Any
          fun set(root: fixture.Note, value: kotlin.String): fixture.Note
      """),
    )
  }

}
