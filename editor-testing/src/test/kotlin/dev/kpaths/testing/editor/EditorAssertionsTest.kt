package dev.kpaths.testing.editor

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class EditorAssertionsTest {
  private fun observations(
    diagnostics: List<EditorDiagnostic> = emptyList(),
    references: Map<String, ReferenceObservation> = emptyMap(),
  ) = object : EditorObservations {
    override fun diagnostics() = diagnostics
    override fun reference(expression: String) =
      references[expression] ?: ReferenceObservation.Unavailable("expression not found")
  }

  @Test fun `accepts expected diagnostics and references while tolerating unrelated warnings`() {
    assertEditor(observations(
      diagnostics = listOf(
        EditorDiagnostic(DiagnosticSeverity.ERROR, "Cannot generate Person.Paths", "Person.kt:2"),
        EditorDiagnostic(DiagnosticSeverity.WARNING, "An unrelated inspection"),
      ),
      references = mapOf("Person.Paths.name" to ReferenceObservation.Resolved()),
    )) {
      diagnostics { error("Cannot generate", location = "Person.kt:2") }
      reference("Person.Paths.name").resolves()
    }
  }

  @Test fun `unexpected errors fail even without diagnostic expectations`() {
    val failure = assertFailsWith<AssertionError> {
      assertEditor(observations(listOf(EditorDiagnostic(DiagnosticSeverity.ERROR, "Unresolved reference")))) {}
    }
    assertContains(failure.message.orEmpty(), "Unexpected ERROR: Unresolved reference")
  }

  @Test fun `expected diagnostics match once and report unexpected errors`() {
    val failure = assertFailsWith<AssertionError> {
      assertEditor(observations(listOf(
        EditorDiagnostic(DiagnosticSeverity.ERROR, "Collision"),
        EditorDiagnostic(DiagnosticSeverity.ERROR, "Collision"),
      ))) {
        diagnostics { error("Collision") }
      }
    }
    assertContains(failure.message.orEmpty(), "Unexpected ERROR: Collision")
  }

  @Test fun `overlapping expected messages match without depending on ordering`() {
    assertEditor(observations(listOf(
      EditorDiagnostic(DiagnosticSeverity.ERROR, "Collision at Person.Paths"),
      EditorDiagnostic(DiagnosticSeverity.ERROR, "Collision at Event.Paths"),
    ))) {
      diagnostics {
        error("Collision")
        error("Collision at Person.Paths")
      }
    }
  }

  @Test fun `missing expected warnings and incorrect locations fail`() {
    val failure = assertFailsWith<AssertionError> {
      assertEditor(observations(listOf(EditorDiagnostic(DiagnosticSeverity.WARNING, "Missing case", "A.kt:3")))) {
        diagnostics { warning("Missing case", location = "B.kt:3") }
      }
    }
    assertContains(failure.message.orEmpty(), "Missing WARNING diagnostic containing 'Missing case' at B.kt:3")
  }

  @Test fun `missing observations never pass unresolved assertions`() {
    val failure = assertFailsWith<AssertionError> {
      assertEditor(observations()) { reference("Missing.member").isUnresolved() }
    }
    assertContains(failure.message.orEmpty(), "Cannot inspect reference 'Missing.member'")
    assertEditor(observations(references = mapOf("Missing.member" to ReferenceObservation.Unresolved))) {
      reference("Missing.member").isUnresolved()
    }
  }

  @Test fun `wrong reference state reports failure`() {
    val failure = assertFailsWith<AssertionError> {
      assertEditor(observations(references = mapOf("Person.Paths.name" to ReferenceObservation.Unresolved))) {
        reference("Person.Paths.name").resolves()
      }
    }
    assertContains(failure.message.orEmpty(), "did not resolve")
  }

  @Test fun `unfinished reference expectations fail instead of silently passing`() {
    val failure = assertFailsWith<AssertionError> {
      assertEditor(observations()) { reference("Person.Paths.name") }
    }
    assertContains(failure.message.orEmpty(), "No expected resolution specified")
  }

  @Test fun `diagnostics can be compared through a shared text snapshot strategy`() {
    val editor = observations(listOf(EditorDiagnostic(DiagnosticSeverity.ERROR, "Unknown name", "A.kt:1")))
    assertEditor(editor) { diagnosticsSnapshot("ERROR at A.kt:1: Unknown name") }
    val failure = assertFailsWith<AssertionError> {
      assertEditor(editor) { diagnosticsSnapshot("ERROR at A.kt:1: Different error") }
    }
    assertContains(failure.message.orEmpty(), "Diagnostic snapshot differs")
    assertContains(failure.message.orEmpty(), "Actual:\nERROR at A.kt:1: Unknown name")
  }

  @Test fun `snapshot and individual diagnostics cannot silently conflict`() {
    assertFailsWith<IllegalStateException> {
      assertEditor(observations()) {
        diagnostics { error("expected") }
        diagnosticsSnapshot("")
      }
    }
    assertFailsWith<IllegalStateException> {
      assertEditor(observations()) {
        diagnostics {}
        diagnosticsSnapshot("")
      }
    }
    assertFailsWith<IllegalStateException> {
      assertEditor(observations()) {
        diagnosticsSnapshot("")
        diagnostics { warning("expected") }
      }
    }
  }

  @Test fun `reference-only checks explicitly skip unavailable diagnostics`() {
    val source = object : EditorObservations {
      override fun diagnostics(): List<EditorDiagnostic> = error("diagnostics unavailable")
      override fun reference(expression: String) = ReferenceObservation.Resolved()
    }
    assertEditor(source, checkDiagnostics = false) { reference("Person.Paths.name").resolves() }
    assertFailsWith<IllegalStateException> {
      assertEditor(source, checkDiagnostics = false) { diagnostics { error("unexpected") } }
    }
    assertFailsWith<IllegalStateException> {
      assertEditor(source, checkDiagnostics = false) { diagnosticsSnapshot("") }
    }
    assertFailsWith<IllegalStateException> { assertEditor(source) {} }
  }
}
