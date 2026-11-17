package dev.kpaths.ide

import dev.kpaths.testing.editor.EditorDiagnostic
import dev.kpaths.testing.editor.EditorObservations
import dev.kpaths.testing.editor.ReferenceObservation
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ImportedReferenceAssertionsTest {
  private fun observations(control: ReferenceObservation, generated: ReferenceObservation) = object : EditorObservations {
    override fun diagnostics(): List<EditorDiagnostic> = error("not captured")
    override fun reference(expression: String) = if (expression in importedControls) control else generated
  }

  @Test fun enabledRequiresGeneratedAndControlReferences() {
    assertImportedReferences(observations(ReferenceObservation.Resolved(), ReferenceObservation.Resolved()), "enabled")
    assertFailsWith<AssertionError> {
      assertImportedReferences(observations(ReferenceObservation.Resolved(), ReferenceObservation.Unresolved), "enabled")
    }
  }

  @Test fun disabledRequiresControlsButRejectsResolvedOrUnavailableGeneratedReferences() {
    assertImportedReferences(observations(ReferenceObservation.Resolved(), ReferenceObservation.Unresolved), "disabled")
    for (bad in listOf(ReferenceObservation.Resolved(), ReferenceObservation.Unavailable("no reference"))) {
      assertFailsWith<AssertionError> { assertImportedReferences(observations(ReferenceObservation.Resolved(), bad), "disabled") }
    }
    assertFailsWith<AssertionError> {
      assertImportedReferences(observations(ReferenceObservation.Unresolved, ReferenceObservation.Unresolved), "disabled")
    }
  }
}
