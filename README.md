# KPaths

KPaths brings typed, composable, structurally identifiable KeyPaths and CasePaths to Kotlin. It is inspired by [Swift KeyPaths](https://docs.swift.org/latest/documentation/the-swift-programming-language/expressions/#Key-Path-Expression) and [Point-Free’s CasePaths](https://github.com/pointfreeco/swift-case-paths), while using Kotlin-native property references, sealed hierarchies, KSP, and reified type tokens.

## Modules

- `kpaths-core`: KeyPath and CaseKeyPath types, factories, composition, identity, and standard-library KeyPaths.
- `kpaths-annotations`: `@Pathable`.
- `kpaths-ksp`: generated catalogs.

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

`KeyPath` reads, `WritableKeyPath` returns a revised value for value-semantic models, and `ReferenceWritableKeyPath` is for intentional in-place class mutation. KeyPaths have structural `PathId` equality and hashability, so they can safely be used as stable API values and cache keys.

Annotate frequent source-owned models to generate canonical catalogs:

```kotlin
@Pathable
data class Person(val name: String, var city: String)

PersonPaths.name
PersonPaths.city
```

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

There is one `CaseKeyPath` type. Unlike KeyPaths, CaseKeyPaths reconstruct complete cases, so there is no read-only/writable hierarchy.

For a conventional unannotated data-class case, use the reflection-backed shorthand:

```kotlin
import example.Event.Message

val text = casePathsOf<Event>().pathFor(::Message)
text.embed("Hello") // Event.Message("Hello")
```

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

The full guides cover [KeyPaths](docs/key-paths.md), [CaseKeyPaths](docs/case-paths.md), [generated catalogs](docs/generation.md), [KeyPath and CaseKeyPath recipes](docs/recipes.md), [advanced KeyPaths](docs/advanced-paths.md), and [KeyPath and CaseKeyPath API design](docs/route-aware-apis.md).

Build the project and documentation with:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew check docsBuild
```

## Acknowledgments

KPaths is strongly inspired by Swift’s KeyPath system and Point-Free’s CasePaths library. Their APIs demonstrate how first-class KeyPaths and CaseKeyPaths make generic algorithms and compositional application models substantially clearer.
