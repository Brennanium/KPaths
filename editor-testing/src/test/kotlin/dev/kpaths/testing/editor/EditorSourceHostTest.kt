package dev.kpaths.testing.editor

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EditorSourceHostTest {
  @Test fun `inline source is opened once and assertions use its observations`() {
    var opened: Pair<String, String>? = null
    val host = EditorSourceHost { name, source ->
      check(opened == null)
      opened = name to source
      object : EditorObservations {
        override fun diagnostics() = emptyList<EditorDiagnostic>()
        override fun reference(expression: String) =
          if (expression == "person.name") ReferenceObservation.Resolved()
          else ReferenceObservation.Unavailable("not in file")
      }
    }

    assertEditor(
      source = """
        val name = person.name
      """,
      host = host,
      fileName = "Example.kt",
    ) { reference("person.name").resolves() }

    assertEquals("Example.kt" to "val name = person.name", opened)
  }

  @Test fun `hosting does not suppress unexpected editor errors`() {
    val host = EditorSourceHost { _, _ ->
      object : EditorObservations {
        override fun diagnostics() = listOf(EditorDiagnostic(DiagnosticSeverity.ERROR, "Type mismatch"))
        override fun reference(expression: String) = ReferenceObservation.Unavailable("not inspected")
      }
    }
    assertFailsWith<AssertionError> { assertEditor("val value: Int = \"wrong\"", host) {} }
  }
}
