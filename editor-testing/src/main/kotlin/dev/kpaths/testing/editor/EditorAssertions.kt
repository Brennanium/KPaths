package dev.kpaths.testing.editor

/** A diagnostic captured from the editor after the project and analysis are ready. */
public data class EditorDiagnostic(
  val severity: DiagnosticSeverity,
  val message: String,
  val location: String? = null,
)

public enum class DiagnosticSeverity { ERROR, WARNING, INFO }

/** An unavailable observation is different from an IDE successfully reporting no target. */
public sealed interface ReferenceObservation {
  public data class Resolved(val target: String? = null) : ReferenceObservation
  public data object Unresolved : ReferenceObservation
  public data class Unavailable(val reason: String) : ReferenceObservation
}

/** Supplied by an IDE adapter; failures must not be reported as unresolved references. */
public interface EditorObservations {
  public fun diagnostics(): List<EditorDiagnostic>
  public fun reference(expression: String): ReferenceObservation
}

/**
 * Check observations from an already prepared editor. Unexpected errors fail by default;
 * warnings are checked only when explicitly expected. This function does not launch an IDE.
 */
public fun assertEditor(observations: EditorObservations, block: EditorExpectations.() -> Unit) {
  EditorExpectations(observations).apply(block).verify()
}

public class EditorExpectations internal constructor(private val observations: EditorObservations) {
  private val expectedDiagnostics = mutableListOf<ExpectedDiagnostic>()
  private val references = mutableListOf<ReferenceExpectation>()

  public fun diagnostics(block: DiagnosticExpectations.() -> Unit) {
    DiagnosticExpectations(expectedDiagnostics).apply(block)
  }

  public fun reference(expression: String): ReferenceExpectation {
    require(expression.isNotBlank()) { "A reference expression must not be blank." }
    return ReferenceExpectation(expression).also(references::add)
  }

  internal fun verify() {
    val failures = mutableListOf<String>()
    val actual = observations.diagnostics()
    // Find a one-to-one match even when a broad expected message overlaps a more specific one.
    val owner = IntArray(actual.size) { -1 }
    fun assign(expectedIndex: Int, visited: BooleanArray): Boolean {
      val expected = expectedDiagnostics[expectedIndex]
      for (index in actual.indices) {
        val diagnostic = actual[index]
        if (visited[index] || diagnostic.severity != expected.severity ||
          !diagnostic.message.contains(expected.messageContains) ||
          (expected.location != null && expected.location != diagnostic.location)) continue
        visited[index] = true
        if (owner[index] == -1 || assign(owner[index], visited)) {
          owner[index] = expectedIndex
          return true
        }
      }
      return false
    }
    for ((index, expected) in expectedDiagnostics.withIndex()) {
      if (!assign(index, BooleanArray(actual.size))) failures += "Missing ${expected.severity} diagnostic containing '${expected.messageContains}'" +
        (expected.location?.let { " at $it" } ?: "")
    }
    actual.filterIndexed { index, it -> owner[index] == -1 && it.severity == DiagnosticSeverity.ERROR }.forEach {
      failures += "Unexpected ERROR${it.location?.let { location -> " at $location" } ?: ""}: ${it.message}"
    }
    for (expected in references) {
      val shouldResolve = expected.shouldResolve
      if (shouldResolve == null) {
        failures += "No expected resolution specified for reference '${expected.expression}'"
        continue
      }
      when (val result = observations.reference(expected.expression)) {
        is ReferenceObservation.Resolved -> if (!shouldResolve) failures += "Reference '${expected.expression}' unexpectedly resolved"
        ReferenceObservation.Unresolved -> if (shouldResolve) failures += "Reference '${expected.expression}' did not resolve"
        is ReferenceObservation.Unavailable -> failures += "Cannot inspect reference '${expected.expression}': ${result.reason}"
      }
    }
    if (failures.isNotEmpty()) throw AssertionError(failures.joinToString("\n", prefix = "Editor assertions failed:\n"))
  }
}

internal data class ExpectedDiagnostic(val severity: DiagnosticSeverity, val messageContains: String, val location: String?)

public class DiagnosticExpectations internal constructor(private val expected: MutableList<ExpectedDiagnostic>) {
  public fun error(messageContains: String, location: String? = null) = expect(DiagnosticSeverity.ERROR, messageContains, location)
  public fun warning(messageContains: String, location: String? = null) = expect(DiagnosticSeverity.WARNING, messageContains, location)

  private fun expect(severity: DiagnosticSeverity, message: String, location: String?) {
    require(message.isNotBlank()) { "Expected diagnostic message must not be blank." }
    expected += ExpectedDiagnostic(severity, message, location)
  }
}

public class ReferenceExpectation internal constructor(internal val expression: String) {
  internal var shouldResolve: Boolean? = null
    private set

  public fun resolves() { setExpectation(true) }
  public fun isUnresolved() { setExpectation(false) }

  private fun setExpectation(value: Boolean) {
    check(shouldResolve == null) { "A reference expectation can only be specified once." }
    shouldResolve = value
  }
}
