package dev.kpaths.ide

import dev.knapshots.testing.editor.EditorObservations
import dev.knapshots.testing.editor.assertEditor

internal val importedControls = listOf("Person annotation", "Direct property", "Hosted support")
internal val importedGenerated = listOf(
  "Person.Paths", "Person.Paths.name", "Event.Paths", "Event.Paths.Message", "Event.Paths.Message.text",
)

/** The disabled scenario intentionally produces unresolved-reference diagnostics. */
internal fun assertImportedReferences(observations: EditorObservations, expected: String) {
  require(expected in setOf("enabled", "disabled", "diagnostic"))
  if (expected == "diagnostic") {
    val failure = try {
      assertEditor(observations) {}
      error("An unexpected error diagnostic did not fail the assertion")
    } catch (failure: AssertionError) {
      failure
    }
    check(failure.message?.contains("Unexpected ERROR") == true && failure.message?.contains("Int") == true) {
      "The expected type error was not reported as an unexpected error: ${failure.message}"
    }
  }
  assertEditor(observations, checkDiagnostics = expected != "disabled") {
    if (expected == "diagnostic") diagnostics { error("Int") }
    importedControls.forEach { reference(it).resolves() }
    importedGenerated.forEach {
      if (expected != "disabled") reference(it).resolves() else reference(it).isUnresolved()
    }
  }
}
