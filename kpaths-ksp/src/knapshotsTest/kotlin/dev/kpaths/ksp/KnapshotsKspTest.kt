package dev.kpaths.ksp

import dev.knapshots.testing.ksp.assertKsp
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
      succeeds = false,
      diagnostics = listOf("@PathName value 'not a name'", "must be a single Kotlin identifier"),
    )
  }
}
