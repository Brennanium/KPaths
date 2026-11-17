package dev.kpaths.ide

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import dev.kpaths.testing.editor.DiagnosticSeverity
import dev.kpaths.testing.editor.EditorDiagnostic
import dev.kpaths.testing.editor.EditorObservations
import dev.kpaths.testing.editor.ReferenceObservation

/** Adapts IntelliJ's focused test fixture to the editor assertion layer. */
internal class FixtureEditorObservations(
  private val fixture: CodeInsightTestFixture,
  // Opt in only for the light fixture's known stub-classpath diagnostic.
  private val ignoreMissingDependency: Boolean = false,
) : EditorObservations {
  override fun diagnostics(): List<EditorDiagnostic> = fixture.doHighlighting()
    .filterNot { ignoreMissingDependency && it.description?.contains("MISSING_DEPENDENCY") == true }
    // Syntax-coloring highlights have no diagnostic message. Never hide errors or warnings.
    .filterNot {
      it.description == null && it.severity != HighlightSeverity.ERROR &&
        it.severity != HighlightSeverity.WARNING && it.severity != HighlightSeverity.WEAK_WARNING
    }
    .map {
      EditorDiagnostic(
        severity = when (it.severity) {
          HighlightSeverity.ERROR -> DiagnosticSeverity.ERROR
          HighlightSeverity.WARNING, HighlightSeverity.WEAK_WARNING -> DiagnosticSeverity.WARNING
          else -> DiagnosticSeverity.INFO
        },
        message = it.description ?: "<no description>",
      )
    }

  override fun reference(expression: String): ReferenceObservation {
    val file = fixture.file ?: return ReferenceObservation.Unavailable("no editor file")
    val content = file.text
    val start = content.indexOf(expression)
    if (start < 0) return ReferenceObservation.Unavailable("expression not found")
    if (content.indexOf(expression, start + 1) >= 0) return ReferenceObservation.Unavailable("expression is ambiguous")
    val lastPart = expression.substringAfterLast('.')
    if (lastPart.isEmpty()) return ReferenceObservation.Unavailable("expression has no final reference")
    val element = file.findElementAt(start + expression.length - lastPart.length)?.parent
      ?: return ReferenceObservation.Unavailable("reference element not found")
    val references = element.references
    if (references.isEmpty()) return ReferenceObservation.Unavailable("element has no reference")
    val target = references.firstNotNullOfOrNull { it.resolve() }
    return if (target == null) ReferenceObservation.Unresolved else ReferenceObservation.Resolved(target.text)
  }
}
