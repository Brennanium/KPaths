# Compiler-plugin and KSP parity audit

This is a working audit, not a claim that every Kotlin model shape has been exhaustively tested.
KSP remains the reference for the existing generated API. The compiler plugin deliberately exposes
a nested catalog, so parity means equivalent useful path behavior and diagnostics where the two
frontends support the same shape, rather than identical generated declarations.

## Covered behavior

| Capability | Evidence and current scope |
| --- | --- |
| Property paths | Both frontends use the shared property capability policy for read-only, reference-writable, copy-writable, and copy-then-mutate paths. Both consider declared accessible properties; inherited-property discovery is not claimed. Compiler tests cover stored and computed properties, setter visibility, and generic roots. |
| `copyWritableVals` | Both support literal opt-in on data-class constructor properties. Compiler tests cover inferred and explicitly typed paths, generic catalogs, case property catalogs, and meta-annotation requests; KSP has a consumer compilation test. See the constant-expression limitation below. |
| Sealed and enum cases | Both cover nested case classes/objects, enum entries, whole cases, supported direct payloads and bound multi-payload factories. Compiler runtime and FIR/IR snapshot tests cover matching, embedding, nullable payloads and metadata. |
| Generic sealed roots | Shared generic case policy diagnoses unsupported specialization and remapping. The compiler fixture exercises forwarded/reordered parameters, `Nothing`, bounds, payload paths and property catalogs; this is not an exhaustive test of every Kotlin type expression. |
| Names and collisions | Shared model policies provide name validation, reserved names, generated-member collision checks and case-shape diagnostics. Both frontends test renamed members, case names and inaccessible setters. Catalog-placement collisions differ because KSP emits source files and the plugin synthesizes nested declarations. |
| Adapters | Both support declarative `@PathsAdapter` factories using shared mapping policy. Compiler tests cover source and binary factory metadata and contributor-supplied adapters. KSP currently diagnoses aliases of adapted storage types; the compiler fixture supports tested alias forms. |

The principal compiler evidence is in
[`CompilerSnippetTest`](../kpaths-compiler-plugin-fixture/src/test/kotlin/dev/kpaths/compiler/fixture/CompilerSnippetTest.kt),
[`GeneratedPathsRuntimeTest`](../kpaths-compiler-plugin-fixture/src/test/kotlin/dev/kpaths/compiler/fixture/GeneratedPathsRuntimeTest.kt),
and the [FIR/IR and downstream contracts](../kpaths-compiler-plugin-fixture/src/test/kotlin/dev/kpaths/compiler/fixture/FutureCompilerPluginContractTest.kt).
KSP diagnostics and consumer compilation are covered by its
[integration tests](../kpaths-ksp/src/test/kotlin/dev/kpaths/ksp/KPathsProcessorIntegrationTest.kt).

## Known differences and release decisions

1. **Case discovery:** KSP discovers direct sealed subclasses declared elsewhere in the same
   package and module; the compiler plugin currently discovers nested cases only. An acceptance
   contract for package-level cases is disabled. More importantly, early type-resolution edge
   cases can omit a nested case without a diagnostic. Before release, improve detection or provide
   a reliable diagnostic; do not treat an incomplete generated catalog as complete.
2. **`copyWritableVals` expressions:** KSP accepts Kotlin constant references. The compiler plugin
   needs the option before FIR has resolved such references and currently diagnoses nonliteral
   source expressions; use literal `true` or `false`. Previously, an inferred path could silently
   become read-only even for literal `true`; the regression tests and FIR/IR snapshot now cover
   this. Full constant-expression support remains a parity gap.
3. **Catalog placement:** KSP emits a top-level catalog, optionally renamed with `@Pathable(name)`;
   the compiler plugin synthesizes `Type.Paths` and does not emit or rename that top-level catalog.
   The plugin needs no companion object. This is an intentional frontend API difference, but needs
   clear migration and setup documentation.
4. **`CasePathable`:** Supported non-generic sealed and enum roots receive compiler-generated
   conformance. Generic roots have usable typed catalogs but no automatic `CasePathable`
   conformance because recovering generic type identity from an instance requires a different
   contract. KSP cannot add conformance to a source type. See
   [generic case-path research](generic-case-pathable-research.md).
5. **Incomplete canonical case catalogs:** Unsupported case payloads, including cases outside the
   current canonical payload shapes, are omitted from `allCasePaths`; both frontends warn. A
   `case`/`caseOf` lookup for an omitted case can fail. Whole-case paths remain available. Decide
   whether that runtime boundary is acceptable for the first compiler-plugin release.
6. **External compiler contributors:** Generated FIR declarations cannot always receive normal
   declaration-site diagnostics. Invalid generated-property adapters are diagnosed on path access,
   so merely obtaining a catalog does not validate every synthesized property. The compiler API is
   experimental and tied to compiler internals; integration fixtures are not a stable ABI promise.

## Suggested next checks

Prioritize reliable diagnostics for case discovery and explicit acceptance tests for incomplete
catalog behavior. Then verify the real consumer experience: the Gradle plugin and IDE artifacts
must be published, KEFS configured, and the combined Pages repository deployed before this can be
called a released integration. Kotlin 2.4.20 and 2.4.21 have JVM consumer coverage; the iOS
simulator checks and single Android Studio/KEFS combination are narrower than the full API surface.
VS Code source-project resolution is a separate documented limitation.
