# KPaths

KPaths provides typed, comparable KeyPaths and CaseKeyPaths for Kotlin values. It is inspired by [Swift’s
key-path expressions](https://docs.swift.org/latest/documentation/the-swift-programming-language/expressions/#Key-Path-Expression)
and [Point-Free’s CasePaths](https://github.com/pointfreeco/swift-case-paths).

Swift KeyPaths use `\Type.property` expressions. KPaths uses Kotlin property references and
factories such as `keyPath(Person::name)` to create a typed KeyPath value. KPaths CaseKeyPaths extend
that idea to sealed cases through `caseKeyPath` and
`casePathsOf`.

That distinction matters most at API boundaries. A KeyPath can act as a Kotlin getter closure, but
it does not lose its `PathId`, write capability, or composition behavior when it does.
Reusable APIs can therefore accept a KeyPath or CaseKeyPath when they need more than one immediate
read. See [KeyPath and CaseKeyPath API design](route-aware-apis.md) for reusable field selection, nested updates,
case construction, and stable cache keys.

The library deliberately keeps Kotlin's own language model visible:

- Factories are the preferred construction API. `KProperty1` and `KMutableProperty1` make KeyPaths
  available for accessible properties, including types the application does not own.
- `@Pathable` uses KSP only to generate discoverable, canonical catalogs for source-owned types.
- Data-class updates are modeled as replacement values; mutable classes can opt into explicit
  reference mutation.
- Exact case matching uses `CaseMatch`, so a matching nullable payload is never confused with a
  different case.

Use the guides in the navigation to learn the model, then open the [API reference](api/index.md) for the
complete generated KDoc. The reference includes public declarations from `kpaths-core` and
`kpaths-annotations`, but deliberately excludes processor internals and the sample module.

For practical composition and collection patterns, start with [KeyPath and CaseKeyPath recipes](recipes.md).
For custom KeyPaths, indexed/keyed KeyPaths, and other escape hatches, see [Advanced KeyPaths](advanced-paths.md).
For designing an API that consumes KeyPaths rather than opaque closures, see [KeyPath and CaseKeyPath API design](route-aware-apis.md).

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
