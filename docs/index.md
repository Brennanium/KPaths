# KPaths

KPaths provides typed, comparable routes through Kotlin values. It is inspired by [Swift’s
key-path expressions](https://docs.swift.org/latest/documentation/the-swift-programming-language/expressions/#Key-Path-Expression)
and [Point-Free’s CasePaths](https://github.com/pointfreeco/swift-case-paths).

Swift key paths use `\Type.property` expressions. KPaths uses Kotlin property references and
factories such as `keyPath(Person::name)` to provide the same role: describe a typed route as a
value. KPaths case paths extend that idea to sealed cases through `caseKeyPath` and
`writableCaseKeyPath`.

That distinction matters most at API boundaries. A path can act as a Kotlin getter closure, but
it does not lose its route identity, write capability, or composition behavior when it does.
Reusable APIs can therefore accept a `KeyPath`, `WritableKeyPath`, or case-path type when they
need more than one immediate read. See [Route-aware APIs](route-aware-apis.md) for the practical
patterns: reusable field selection, nested updates, case routing, and stable cache keys.

The library deliberately keeps Kotlin's own language model visible:

- Factories are the preferred construction API. `KProperty1` and `KMutableProperty1` make paths
  available for accessible properties, including types the application does not own.
- `@Pathable` uses KSP only to generate discoverable, canonical catalogs for source-owned types.
- Data-class updates are modeled as replacement values; mutable classes can opt into explicit
  reference mutation.
- Exact case matching uses `CaseMatch`, so a matching nullable payload is never confused with a
  different case.

Use the guides in the navigation to learn the model, then open the [API reference](api/index.md) for the
complete generated KDoc. The reference includes public declarations from `kpaths-core` and
`kpaths-annotations`, but deliberately excludes processor internals and the sample module.

For practical composition and collection patterns, start with [Path recipes](recipes.md). For
custom routes, indexed/keyed paths, and other escape hatches, see [Advanced paths](advanced-paths.md).
For designing an API that consumes paths rather than opaque closures, see [Route-aware APIs](route-aware-apis.md).

## Build locally

Install the pinned documentation dependency once, then build the complete static site:

```sh
python3 -m venv .venv-docs
. .venv-docs/bin/activate
python3 -m pip install -r docs/requirements.txt
./gradlew docsBuild
```

The result is `build/docs/site`. Run `./gradlew docsServe` to serve that exact output locally at
<http://localhost:8000>. The tasks automatically use `.venv-docs/bin/python`; use
`-PdocsPython=/absolute/path/to/python` to choose another interpreter in CI.
