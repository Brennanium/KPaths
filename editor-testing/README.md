# Editor assertions experiment

This unpublished Kotlin module evaluates observations supplied by an editor adapter. It does not
start an IDE, import a project, or configure compiler plugins. The Kotlin Plugin Matrix CLI handles
version selection and orchestration; an IDE test fixture or future IDE-side adapter captures the
editor observations. The two communicate through the test runner or a future result protocol.

```kotlin
assertEditor(observations) {
  reference("Person.Paths.name").resolves()
  diagnostics { warning("catalog omits") }
}
```

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
The full imported-project probe is not yet wired to these assertions. Exact location support,
completion, inlays, and inline recording remain future work.

`assertCompiler` currently lives in the compiler fixture. Both assertion layers now share the
basic snapshot strategy and comparison module. Generic snapshot expectations, version selection,
diff rendering, and inline recording remain future work. Compiler capture, IDE capture, and CLI
orchestration remain separate.
