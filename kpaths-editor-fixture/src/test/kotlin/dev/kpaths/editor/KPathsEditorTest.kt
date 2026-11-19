package dev.kpaths.editor

import dev.knapshots.testing.editor.assertEditor
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Exercises an imported consumer with KEFS enabled, disabled, and an intentional error. */
@Tag("ide-integration")
class KPathsEditorTest {
  @Test fun `generated paths resolve in an imported project`() {
    val mode = System.getProperty("kpaths.editorMode", "enabled")
    assertEditor(
      source = """
        import dev.kpaths.annotations.Pathable
        @Pathable data class Person(var name: String)
        @Pathable sealed interface Event { data class Message(val text: String) : Event }
        val namePath = Person.Paths.name
        val messagePath = Event.Paths.Message.text
      """ + if (mode == "diagnostic") "\nval invalid: Int = \"wrong\"\n" else "",
      awaitReferences = if (mode == "disabled") emptySet() else setOf("Person.Paths.name", "Event.Paths.Message.text"),
    ) {
      if (mode == "disabled") {
        diagnostics { error("Unresolved reference"); error("Unresolved reference") }
        reference("Person.Paths.name").isUnresolved()
        reference("Event.Paths.Message.text").isUnresolved()
      } else {
        if (mode == "diagnostic") diagnostics { error("Int") }
        reference("Person.Paths.name").resolves()
        reference("Event.Paths.Message.text").resolves()
      }
    }
  }
}
