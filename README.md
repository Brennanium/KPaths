# KPaths

KPaths brings typed, composable, structurally identifiable KeyPaths and CaseKeyPaths to Kotlin. It is inspired by [Swift KeyPaths](https://docs.swift.org/latest/documentation/the-swift-programming-language/expressions/#Key-Path-Expression) and [Point-Free’s CasePaths](https://github.com/pointfreeco/swift-case-paths), while using Kotlin-native property references, sealed hierarchies, KSP, and reified type tokens.

## Modules

- `kpaths-core`: KeyPath and CaseKeyPath types, explicit factories, composition, identity, and standard-library KeyPaths.
- `kpaths-annotations`: `@Pathable`.
- `kpaths-ksp`: generated catalogs.
- `kpaths-reflection-jvm`: optional JVM-only constructor-discovery factories.

`kpaths-core` and `kpaths-annotations` support Kotlin/JVM (including Android) and Kotlin/Native
for iOS. `kpaths-ksp` is a JVM-based build-time processor; it can generate catalogs for supported
multiplatform source sets. The reflection-backed `casePathsOf(...).pathFor(...)` shorthand remains
JVM-only because it depends on Kotlin reflection.

## KeyPaths

Use property references for any Kotlin type—no annotation is required:

```kotlin
data class Person(val name: String, var city: String)

val name = keyPath(Person::name)
val city = writableKeyPath(
  get = Person::city,
  set = { person, value -> person.copy(city = value) },
)
```

`KeyPath` reads, `WritableKeyPath` returns a revised value for value-semantic models, and `ReferenceWritableKeyPath` is for intentional in-place class mutation. See the [KeyPaths guide](https://brennanium.github.io/KPaths/key-paths/) for factory selection, composition, nullable paths, and standard-library paths.

For a source-owned model with paths used repeatedly, annotate it to generate a canonical catalog:

```kotlin
@Pathable
data class Person(val name: String, var city: String)

PersonPaths.name
PersonPaths.city
```

That top-level catalog is always the fallback for a valid annotated model; a companion is optional.
For the most discoverable source-owned API, add a simple companion object:

```kotlin
@Pathable
data class Person(val name: String, var city: String) {
  companion object
}

Person.Paths.name == PersonPaths.name // the same canonical KeyPath
Person.Paths.city
```

`Person.Paths` keeps the vocabulary with its type in completion. It is a generated extension, so a
consumer in another package imports it normally; Kotlin IDEs generally offer that import
automatically. If the model has no companion, use `PersonPaths` instead—no generated behavior is
lost. See [Generated catalogs](https://brennanium.github.io/KPaths/generation/) for companion naming, imports, generic
construction forms, `@PathsName`, and generated-case naming options.

## CaseKeyPaths

A `CaseKeyPath<Root, Value>` matches a sealed case, extracts its associated data, and independently constructs a root from that data:

```kotlin
sealed interface Event {
  data object Started : Event
  data class Message(val text: String) : Event
}

val message = caseKeyPath<Event, Event.Message>()

Event.Message("Hello")[message] // Event.Message("Hello")
Event.Started[message]           // null
message.embed(Event.Message("Updated"))
```

There is one `CaseKeyPath` type. Unlike KeyPaths, CaseKeyPaths reconstruct complete cases, so there is no read-only/writable hierarchy. The [CaseKeyPaths guide](https://brennanium.github.io/KPaths/case-paths/) explains whole-case, payload, reflection-backed, and generated factories.

For a conventional unannotated data-class case on the JVM, add the optional reflection artifact and
use the reflection-backed shorthand:

```kotlin
implementation("io.github.brennanium:kpaths-reflection-jvm:0.1.0")
```

```kotlin
import example.Event.Message

val text = casePathsOf<Event>().pathFor(::Message)
text.embed("Hello") // Event.Message("Hello")
```

`casePathsOf` intentionally does not live in `kpaths-core`: it needs Kotlin/JVM's full runtime
shape discovery. Generated catalogs and explicit `caseKeyPath` factories do not need this optional
artifact.

For a non-reflective or custom `CaseKeyPath`, use the explicit factory:

```kotlin
val text = caseKeyPath<Event, Event.Message, String>(
  extract = Event.Message::text,
  embed = ::Message,
)
```

For source-owned roots, generated CaseKeyPaths are the preferred repeated-use form:

```kotlin
@Pathable
sealed interface Request {
  data class Navigate(val id: String, val destination: String) : Request
}

RequestPaths.Navigate.payload
RequestPaths.Navigate.destinationFor(id = "settings")
  .embed("privacy")
// Request.Navigate("settings", "privacy")
```

CaseKeyPaths compose and retain independent embedding:

```kotlin
val nested = AppEventPaths.Profile.action
  .then(ProfileActionPaths.Editor.action)
  .then(EditorActionPaths.Rename.text)

nested.embed("Jr.")
```

Use `match` rather than nullable extraction whenever a matched `null` must remain distinguishable from a non-match.

## Documentation

- [`main`](https://brennanium.github.io/KPaths/) — documentation for the current development branch.
- [`0.1.0`](https://brennanium.github.io/KPaths/0.1.0/) — documentation for the published `0.1.0` release.

The `main` documentation includes:

- [KeyPaths](https://brennanium.github.io/KPaths/key-paths/) covers the normal property-reference and writable-path APIs.
- [CaseKeyPaths](https://brennanium.github.io/KPaths/case-paths/) covers sealed-case matching, embedding, and composition.
- [Generated catalogs](https://brennanium.github.io/KPaths/generation/) explains what `@Pathable` adds.
- [Recipes](https://brennanium.github.io/KPaths/recipes/) and [API design](https://brennanium.github.io/KPaths/route-aware-apis/) show reusable patterns.
- [Advanced KeyPath APIs](https://brennanium.github.io/KPaths/advanced-paths/) and [identity and type metadata](https://brennanium.github.io/KPaths/identity/) cover library design and structural identity.
- The generated [API reference](https://brennanium.github.io/KPaths/api/) is the source of truth for overloads and KDoc.

## Acknowledgments

KPaths is strongly inspired by Swift’s KeyPath system and Point-Free’s CasePaths library. Their APIs demonstrate how first-class KeyPaths and CaseKeyPaths make generic algorithms and compositional application models substantially clearer.

## License

KPaths is released under the [MIT License](LICENSE).
