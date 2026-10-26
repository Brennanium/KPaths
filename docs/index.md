# KPaths

KPaths provides typed, comparable KeyPaths and CaseKeyPaths for Kotlin values. It is inspired by [Swift’s
key-path expressions](https://docs.swift.org/latest/documentation/the-swift-programming-language/expressions/#Key-Path-Expression)
and [Point-Free’s CasePaths](https://github.com/pointfreeco/swift-case-paths).

Swift KeyPaths use `\Type.property` expressions. KPaths uses Kotlin property references and
factories such as `keyPath(Person::name)` to create the equivalent typed value. CaseKeyPaths extend
the same idea to sealed cases through `caseKeyPath`; the JVM-only `casePathsOf` convenience adds
constructor discovery for conventional data-class cases.

## Learn KPaths in order

1. Read [KeyPaths](key-paths.md) for property references, value-style writes, composition, and
   nullable paths.
2. Read [CaseKeyPaths](case-paths.md) for matching, independent embedding, payload factories, and
   sealed-case composition.
3. Read [Generated catalogs](generation.md) to choose between the always-generated `TypePaths`
   catalog and the more discoverable `Type.Paths` companion facade for source-owned models.

Then choose the guide that matches the problem at hand:

- [Recipes](recipes.md) collects composition, collection, and case-classification patterns.
- [API design](route-aware-apis.md) explains when an API should retain a KeyPath or CaseKeyPath
  instead of accepting an opaque closure.
- [Advanced KeyPath APIs](advanced-paths.md) shows how libraries can build schemas, reversible
  edits, state projection, and selective invalidation from paths.
- [Identity and type metadata](identity.md) explains structural equality, `PathId`, and `TypeId`.

The [API reference](api/index.md) is the source of truth for every public declaration and overload.
It includes the public KeyPath, annotation, and JVM-only convenience APIs, but deliberately excludes
processor internals and the sample module.

## Platforms

`kpaths-jvm` is the JVM and Android umbrella: it publishes core, annotations, and the optional
reflection factories together. `kpaths-native` is the umbrella for the supported iOS Kotlin/Native
targets: it publishes core and annotations, but not JVM reflection. The normal property and
explicit case-path factories work on both. On Native, prefer the explicit factories or generated
`@Pathable` catalogs.

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
