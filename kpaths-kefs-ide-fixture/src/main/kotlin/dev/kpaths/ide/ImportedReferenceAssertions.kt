package dev.kpaths.ide

import dev.kpaths.testing.editor.EditorObservations
import dev.kpaths.testing.editor.assertEditor

internal val importedControls = listOf("Person annotation", "Direct property")
internal val importedGenerated = listOf(
  "Person.Paths", "Person.Paths.name", "Event.Paths", "Event.Paths.Message", "Event.Paths.Message.text",
)

/** The imported probe currently captures reference resolution, not editor diagnostics. */
internal fun assertImportedReferences(observations: EditorObservations, expected: String) {
  require(expected == "enabled" || expected == "disabled")
  assertEditor(observations, checkDiagnostics = false) {
    importedControls.forEach { reference(it).resolves() }
    importedGenerated.forEach {
      if (expected == "enabled") reference(it).resolves() else reference(it).isUnresolved()
    }
  }
}
