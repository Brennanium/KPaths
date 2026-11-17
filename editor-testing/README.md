# Editor assertions experiment

This unpublished Kotlin module evaluates observations supplied by an editor adapter. It does not
start an IDE, import a project, or configure compiler plugins. The Kotlin Plugin Matrix CLI handles
version selection and orchestration; an IDE test fixture or future IDE-side adapter captures the
editor observations. The two communicate through the test runner or a future result protocol.

An `EditorSourceHost` connects inline source to a prepared editor and exposes observations to the
assertion layer. In the KPaths lightweight IntelliJ fixture, a test base supplies that host:

```kotlin
assertEditor(source = """
  import dev.kpaths.annotations.Pathable
  @Pathable data class Person(val name: String)
  val name = Person.Paths.name
""") {
  reference("Person.Paths.name").resolves()
}
```

The fixture provides the compiler plugin and dependencies in its suite setup. It uses IntelliJ's
light test project; each assertion does not start a new IDE or import a Gradle project. The base
class and IntelliJ adapter currently live in the KPaths IDE fixture, not a published integration.
The lower-level `assertEditor(observations)` remains available to other adapters.

Unexpected error diagnostics fail even when no diagnostic block is provided. Expected diagnostics
are matched once each by severity and message substring, optionally with a location. Unrelated
warnings do not fail the test. `isUnresolved()` checks a negative reference; if a reference cannot
be inspected (for example, because its expression is missing or ambiguous), the test fails instead
of treating it as unresolved. The adapter is responsible for capturing a ready editor state and
reporting failures faithfully.

`diagnosticsSnapshot(expected)` compares all captured diagnostics as text, including warnings,
using the shared `snapshot-testing` comparison strategy. It cannot be combined with individual
diagnostic expectations in the same assertion. It does not record snapshots yet.

The KPaths Android Studio light fixture supplies the first adapter and exercises both real
highlighting and reference resolution. It explicitly filters the light fixture's existing
`MISSING_DEPENDENCY` stub-classpath diagnostic; the assertion module does not filter diagnostics.
The imported-project probe uses the same reference assertions with diagnostic checking explicitly
disabled: it does not yet capture editor diagnostics. Exact location support, completion, inlays,
and inline recording remain future work.

`assertCompiler` currently lives in the compiler fixture. Both assertion layers now share the
basic snapshot strategy and comparison module. Generic snapshot expectations, version selection,
diff rendering, and inline recording remain future work. Compiler capture, IDE capture, and CLI
orchestration remain separate.
