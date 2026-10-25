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

`KeyPath` reads, `WritableKeyPath` returns a revised value for value-semantic models, and `ReferenceWritableKeyPath` is for intentional in-place class mutation. See the [KeyPaths guide](docs/key-paths.md) for factory selection, composition, nullable paths, and standard-library paths.

Annotate frequent source-owned models to generate canonical catalogs:

```kotlin
@Pathable
data class Person(val name: String, var city: String)

PersonPaths.name
PersonPaths.city
```

If a source-owned model has a companion object, generated paths can also be accessed through
`Person.Paths.name`, `Event.Paths.Message`, or `Box.Paths<String>().value`; see
[Generated catalogs](docs/generation.md) for the required companion declaration, import, generic
interface detail, and generated-case naming options.

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

There is one `CaseKeyPath` type. Unlike KeyPaths, CaseKeyPaths reconstruct complete cases, so there is no read-only/writable hierarchy. The [CaseKeyPaths guide](docs/case-paths.md) explains whole-case, payload, reflection-backed, and generated factories.

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

Start with the [documentation overview](docs/index.md). From there:

- [KeyPaths](docs/key-paths.md) covers the normal property-reference and writable-path APIs.
- [CaseKeyPaths](docs/case-paths.md) covers sealed-case matching, embedding, and composition.
- [Generated catalogs](docs/generation.md) explains what `@Pathable` adds.
- [Recipes](docs/recipes.md) and [API design](docs/route-aware-apis.md) show reusable patterns.
- [Advanced KeyPaths](docs/advanced-paths.md) and [identity and type metadata](docs/identity.md) cover escape hatches.
- The generated [API reference](docs/api/index.md) is the source of truth for overloads and KDoc.

Build the project and documentation with:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew check docsBuild
```

## Acknowledgments

KPaths is strongly inspired by Swift’s KeyPath system and Point-Free’s CasePaths library. Their APIs demonstrate how first-class KeyPaths and CaseKeyPaths make generic algorithms and compositional application models substantially clearer.

## License

KPaths is released under the [MIT License](LICENSE).
