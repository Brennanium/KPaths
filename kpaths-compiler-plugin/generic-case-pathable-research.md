# Generic `CasePathable` and runtime type identity

Research notes for the compiler-plugin implementation. This is a design investigation, not a
description of an implemented API or a decision to change the public contract. Examples marked
**proposed** illustrate possible designs and have not been verified unless stated otherwise.

## The goal

Provide the active case as a correctly typed, structurally identifiable `PartialCaseKeyPath`,
including for generic sealed roots, and make it usable through a `CasePathable` abstraction:

```kotlin
@Pathable
sealed interface Loadable<out Value> {
  data object Idle : Loadable<Nothing>
  data class Loaded<Value>(val value: Value) : Loadable<Value>
}

val event: Loadable<String> = Loadable.Loaded("Hello")
val path: PartialCaseKeyPath<Loadable<String>> = event.`case` // desired
```

The partial path should identify the **canonical payload path** for the active case. For example,
`Loaded`'s canonical payload is `String`, while `Idle`'s is `Unit`; it is not necessarily the
whole-case path. Its identity must match the corresponding generated catalog path, including the
root and payload type information and any nested generic arguments or nullability.

`case` is a Kotlin keyword, so source examples use backticks. The desired API should also work
in generic algorithms, not just at call sites that know the concrete sealed type.

## What exists today

- [`CasePathable<Root>`](../kpaths-core/src/main/kotlin/dev/kpaths/core/CaseKeyPath.kt) declares
  `val case: PartialCaseKeyPath<Root>`. Its interface getter has no type-token or catalog argument.
- `CasePathCatalog<Root>` exposes `allCasePaths` and `caseOf(root)`. A generated generic catalog
  such as `Loadable.Paths<String>()` retains the supplied type information. A non-inline generic
  function can already accept a `CasePathCatalog<Loadable<Value>>` from its caller and use
  `catalog.caseOf(event)`.
- The compiler plugin generates `CasePathable` conformance for supported non-generic roots. Generic
  sealed roots get catalogs but do **not** yet get automatic `CasePathable` conformance. See the
  [compiler-plugin README](README.md).
- [`TypeId`](../kpaths-core/src/main/kotlin/dev/kpaths/core/TypeId.kt) captures structural type
  identity, including arguments and nullability. `TypeId.of<T>()` uses `typeOf<T>()` at a reified
  call site; `TypeId.from(KClass)` alone cannot describe nested arguments or nullable types.
- [`CompilerSnippetTest`](../kpaths-compiler-plugin-fixture/src/test/kotlin/dev/kpaths/compiler/fixture/CompilerSnippetTest.kt)
  has a JVM snippet test demonstrating the following **manually declared extension**:

  ```kotlin
  inline val <reified T> Loadable<T>.`case`: PartialCaseKeyPath<Loadable<T>>
    get() = Loadable.Paths<T>().caseOf(this)
  ```

  The test checks that the same `Idle` singleton, viewed as `Loadable<String>` and
  `Loadable<Int>`, yields the corresponding distinct catalog paths. This is an experiment in a
  test, not a generated or supported public `case` extension API, and it is not an interface
  implementation. Native behavior and all generic call-site forms need separate verification.

## Why the existing getter cannot recover the caller's type

`out Value` declares covariance, not runtime metadata. In this example both variables can refer
to the very same singleton:

```kotlin
val strings: Loadable<String> = Loadable.Idle
val integers: Loadable<Int> = Loadable.Idle
```

The compile-time type of each expression differs, but a parameterless virtual getter on the
singleton receives the same object and no indication of which specialization the caller used.
A token stored on that object cannot represent both views. Even a non-singleton object can be
viewed through a widened covariant type, so construction-time type and requested view need not
agree. A design must explicitly decide whether identity follows the caller's static view; the
reified extension experiment does so.

Kotlin normally erases generic arguments on runtime instances. An ordinary class type parameter
cannot be declared `reified`; only type parameters on inline functions or properties can be
reified. `inline` on a getter does not reify its enclosing class parameter. Only extension
properties may declare their own type parameters, and an extension does not implement an ordinary
interface member. A member wins over a same-named extension, so the existing `case` member would
also hide such an extension in normal resolution.

Swift's generic metadata and protocol witness machinery provide a useful comparison, but an
extension property in Kotlin is not an implementation of an interface requirement. See the
[Kotlin generics guide](https://kotlinlang.org/docs/generics.html#type-erasure),
[extension resolution rules](https://kotlinlang.org/docs/extensions.html#extensions-are-resolved-statically),
[inline functions](https://kotlinlang.org/docs/inline-functions.html), and the
[specification's declaration and reification rules](https://kotlinlang.org/spec/declarations.html).

## Candidate designs

### 1. Keep the catalog explicit

This works with the existing catalog contract and needs no type recovery in the getter:

```kotlin
fun <Value> inspect(
  event: Loadable<Value>,
  paths: CasePathCatalog<Loadable<Value>>,
): PartialCaseKeyPath<Loadable<Value>> = paths.caseOf(event)

val result = inspect(Loadable.Loaded("Hello"), Loadable.Paths<String>())
```

The catalog must be created while the correct type information is available and then passed
through erased code. This is a useful fallback, though it doesn't by itself supply the existing
parameterless `CasePathable.case` requirement. Verify examples against the actual generated API
and Kotlin inference before publishing them as user documentation.

### 2. Reified extension property

The tested extension captures the receiver's static specialization at each eligible call site.
It avoids a catalog argument in concrete or reified contexts:

```kotlin
inline fun <reified Value> inspect(event: Loadable<Value>) = event.`case`
```

An ordinary `fun <Value>` cannot call this extension when it needs a reified `Value`. The
function can instead be inline/reified, or accept a previously constructed catalog or token.
Reified functions have their own restrictions, including on generic callers and public inline
access to non-public declarations.

An extension alone cannot fulfill `CasePathable` or make generic code resolve an arbitrary
root-specific extension. The existing interface member shadows a same-named extension; any
coexistence requires a different name, a contract change, or additional compiler behavior.
Check nested generics, nullable arguments, projections, inferred static types, and cross-module
call sites rather than assuming that all can be handled identically.

### 3. Explicit metadata in the contract plus a reified helper

One **proposed**, breaking alternative is a virtual method that receives the requested root
identity, with the plugin or a manual implementation doing the case selection:

```kotlin
interface CasePathable<Root> {
  fun case(rootType: TypeId): PartialCaseKeyPath<Root>
}

inline fun <reified Root : CasePathable<Root>> Root.casePath(): PartialCaseKeyPath<Root> =
  case(TypeId.of<Root>())
```

The differently named helper avoids the existing `case` member/extension collision. A plugin
could generate straightforward implementations for non-generic roots and use the supplied token
for generic ones; exact implementation, variance, and type-checking remain to be validated.
`Root : CasePathable<Root>` is illustrative and needs testing for covariant roots, subtypes, and
generic type inference. Manual implementers and KSP mode need an intentional, documented story.

Passing an untyped `TypeId` allows a caller to provide a token that does not match `Root`. A
typed `CasePathCatalog<Root>` parameter is another contract design that may reduce accidental
mismatches, though catalog construction still has to capture the right metadata. Consider a typed
metadata wrapper as another option. Neither option automatically generates correct payload
identities: generated code must substitute type arguments correctly for all supported cases.
Also consider the compatibility impact of changing a published interface and the cost/caching
of constructing catalogs.

### 4. Compiler-assisted `.case` with a diagnostic

The plugin could expose property syntax and lower supported accesses to the explicit contract.
The intended behavior is **proposed**, not implemented:

```kotlin
val event: Loadable<String> = Loadable.Loaded("Hello")
event.`case` // supported: String is known

fun <Value> inspect(event: Loadable<Value>) {
  event.`case` // diagnostic: Value is not available as runtime type information
}

inline fun <reified Value> inspectReified(event: Loadable<Value>) {
  event.`case` // intended to work
}
```

Conceptually, the first access might be lowered to
`event.case(TypeId.of<Loadable<String>>())`. Non-generic types could use a precomputed catalog or
token. The compiler does not need to enumerate all possible calls ahead of time: it can inspect
each relevant access as it compiles it. This is a model of the desired behavior, not a claim that
the appropriate FIR/IR hooks, IDE resolution, inline lowering, or cross-module behavior have
been established.

Crucially, a true virtual call through the **existing** parameterless interface property still
does not receive a token. A solution must handle generic interface-typed calls as well as concrete
calls, and either change the underlying contract or show precisely how every supported call is
rewritten. Java, reflection, function/property references, binaries compiled without the plugin,
and callers using the old ABI are potential boundaries. Unsupported cases must be diagnosed or
explicitly defined; they must not silently return a path with the wrong identity.

### 5. Propagate metadata through generic code

A broader plugin system could transform participating generic functions and callers to pass
hidden type tokens, approximating part of Swift's generic metadata machinery. This is much more
invasive than diagnosing an unreified call. It introduces questions around ABI, overrides,
separate compilation, Java interop, function references, Kotlin/Native, compiler versions, and
non-participating dependencies. It cannot assume that arbitrary existing binaries already carry
the required information. This is not needed for the restricted reified-or-explicit-catalog
approach and should not be assumed necessary for the first experiment.

## Comparison with structural typing

The [kotlin-structural-typing design](https://github.com/obabichev/kotlin-structural-typing/blob/main/docs/design.md)
adds actual interface supertypes and marks matching members as overrides during compilation,
then relies on Kotlin's normal interface implementation machinery. It demonstrates that
substantial FIR integration, diagnostics, IDE tests, and cross-module indexing are possible.
It doesn't establish that erased type arguments can be recovered or that the `.case` lowering
above works. Its design also documents limitations around generic interfaces and matching.

Targeted call-site lowering plus a clear diagnostic is a reasonable experiment of comparable
character; implementing a new metadata-carrying convention across generic code is a materially
larger undertaking. Neither comparison substitutes for a compiler experiment.

## Suggested experiments and acceptance criteria

1. Prototype an explicit-metadata interface and a reified generic helper. Verify generated and
   manual implementations, including generic algorithms and non-generic roots. Compare a typed
   catalog parameter with a raw `TypeId`; specify mismatch behavior and compatibility.
2. Determine whether the compiler plugin can expose and lower property syntax through both a
   concrete root and a generic `Root : CasePathable<Root>` without a shadowing or dispatch trap.
   Inspect FIR/IR and runtime results, not just generated API previews.
3. Assert success for concrete, inline/reified, nullable, nested-generic, and covariantly widened
   roots; assert an actionable error for unreified generic accesses. Decide and test star
   projections, type aliases, generic bounds, and multiple or reordered type parameters.
4. Compare returned paths with canonical catalog payload paths, including object `Unit`, nullable
   payloads, multiple payloads, equality/hash codes, and the same singleton at different static
   specializations. Check full `PathId` and focus `TypeId`, not only successful matching.
5. Test cross-module consumers, plugin-disabled consumers, Java/reflection and property references,
   separate compilation, and relevant compatibility scenarios. Define supported behavior rather
   than assuming a transformation reaches all callers.
6. Add snippet/diagnostic and IR assertions, IDE fixture checks, JVM runtime tests, and Native
   tests before claiming support. Compare KSP/manual implementations and measure any per-access
   catalog or token overhead if it influences the design.

No experiment above is a commitment to a public API. Preserve existing experiments and document
what is proved, what is unsupported, and which compiler version/backend was tested.
