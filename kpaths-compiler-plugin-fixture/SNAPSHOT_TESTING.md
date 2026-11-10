# Compiler snapshot assertions

`assertCompilerSnapshots` compares inline expectations with named outputs from a compilation. Its
assertion API is independent of KPaths and the compiler driver; the KPaths fixture currently supplies
compilation and capture. `CompilerSnapshotKind.FIR` and `CompilerSnapshotKind.IR` are predefined,
and a caller can use another `CompilerSnapshotKind` for a different kind of output.

Within the KPaths contract tests, `assertCompiler` supplies the compiler setup and snapshot files:

```kotlin
assertCompiler(
  directory = directory,
  source = """/* Kotlin source */""",
  fir = SnapshotExpectation(
    default = """/* shared FIR */""",
    versions = mapOf("2.5.0-dev-1234" to """/* preview FIR */"""),
  ),
  ir = SnapshotExpectation("""/* shared IR */"""),
)
```

The `assertCompiler` adapter is currently private to that fixture; it isn't a published test API.

Use one expectation when all tested compiler versions produce the same output. When a difference
is intentional, supply exact-version overrides; an unlisted compiler version uses the default.
No version ordering or prefix matching is inferred:

```kotlin
assertCompilerSnapshots(
  source = """
    // Kotlin source to compile
  """,
  compilerVersion = compilerVersionUnderTest,
  expected = mapOf(
    CompilerSnapshotKind.IR to SnapshotExpectation(
      default = """shared IR output""",
      versions = mapOf("2.5.0-dev-1234" to """preview IR output"""),
    ),
  ),
) { source, version, requested ->
  // Compile `source` with exactly `version` and return each requested capture.
  compilerCapture(source, version, requested)
}
```

`compilerCapture` in this illustration is supplied by the integrating test; it is not a built-in
API. Assertions fail with an expected/actual diff if output differs and fail explicitly when a
requested capture is missing. These snapshots are inline assertions, not automatically recorded or
rewritten. The [contract tests](src/test/kotlin/dev/kpaths/compiler/fixture/FutureCompilerPluginContractTest.kt)
show the current KPaths-backed compiler invocation and snapshots.

The captures have different scopes:

* **FIR** is an opt-in `firSnapshot` compiler option. During FIR expression checking, it renders
  KPaths-generated properties and functions actually referenced and resolved by the source. Entries are sorted
  and deduplicated. FIR generation is lazy: unreferenced properties and functions, nested classes,
  and the entire compiler frontend are not included. Add source references for callables whose
  declarations need to be inspected. A generated function may show a FIR stub body that is
  replaced during IR generation; it is not the final runtime implementation. FIR diagnostics are
  separately verified by compilation tests.
* **IR** is an opt-in `irSnapshot` option. It reports a normalized post-generation view of selected
  KPaths-owned classes, supertypes, properties, and functions. It is not a complete IR dump and does
  not show method bodies. Runtime and bytecode tests verify behavior and implementation details.

A snapshot can be requested on its own or alongside another phase in the same compilation. Both
options are intended for compiler tests, not a stable product API. The supported compiler matrix
and local commands are described in the [compiler compatibility guide](../kpaths-compiler-plugin/COMPATIBILITY.md).
