---
hide:
  - navigation
---

# KPaths

Typed, comparable KeyPaths and CaseKeyPaths for Kotlin.

KPaths turns a property reference into a value you can pass, compose, and use to read a property:

```kotlin
import dev.kpaths.core.keyPath

data class Person(val name: String)

val name = keyPath(Person::name)
val people = listOf(Person("Blob"), Person("Blob Jr."))
val names = people.map(name) // ["Blob", "Blob Jr."]
```

A KeyPath carries its root and value types with it. An API can keep a KeyPath, compare it with
another, or compose it with other KeyPaths. CaseKeyPaths bring the same idea to sealed cases:
they match and extract a case's value, and can construct a case independently.

KPaths is inspired by [Swift's key-path expressions](https://docs.swift.org/latest/documentation/the-swift-programming-language/expressions/#Key-Path-Expression)
and [Point-Free's CasePaths](https://github.com/pointfreeco/swift-case-paths).

## Explore the guides

<div class="grid cards" markdown>

-   **KeyPaths**

    ---

    Read and write properties, compose paths, and work with nullable values.

    [Read the KeyPaths guide →](key-paths.md)

-   **CaseKeyPaths**

    ---

    Match cases, extract payloads, embed values, and compose nested cases.

    [Read the CaseKeyPaths guide →](case-paths.md)

-   **Generated catalogs**

    ---

    Generate discoverable paths for your own types with `@Pathable`.

    [Explore generated catalogs →](generation.md)

-   **Recipes**

    ---

    See practical patterns for composition, collections, and case classification.

    [Browse recipes →](recipes.md)

</div>

For library authors, [API design](route-aware-apis.md) and [advanced KeyPath APIs](advanced-paths.md)
show how APIs can use typed, composable paths. [Identity and type metadata](identity.md) explains
structural equality, `PathId`, and `TypeId`.

## API reference

The [API reference](api/index.md) documents the public types, factories, operations, and overloads.
Use the guides for examples and the reference when you need a declaration's precise signature.

## Platforms

`kpaths-jvm` is the JVM and Android umbrella: it publishes core, annotations, and the optional
reflection factories together. `kpaths-native` is the umbrella for the supported iOS Kotlin/Native
targets: it publishes core and annotations, but not JVM reflection. The normal property and
explicit case-path factories work on both. On Native, prefer the explicit factories or generated
`@Pathable` catalogs.

## Build the documentation locally

Install the pinned documentation dependency once, then build the complete static site:

```sh
python3 -m venv .venv-docs
. .venv-docs/bin/activate
python3 -m pip install -r docs/requirements.txt
./gradlew docsBuild
```

The result is `build/docs/site`. Run `./gradlew docsServe` to serve it locally at
<http://localhost:8000>. The tasks automatically use `.venv-docs/bin/python`; use
`-PdocsPython=/absolute/path/to/python` to choose another interpreter.
