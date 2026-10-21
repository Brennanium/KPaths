# KPaths

KPaths is a Kotlin path library inspired by Swift key paths and case paths. It provides generated,
stable, typed property and case paths for ordinary Kotlin models; reducer-based architectures are
one future use case, not the library's framing. Apply one `@Pathable` annotation: data/class
properties generate key paths, while sealed variants generate case key paths.

## Modules

- `kpaths-annotations` — the opt-in `@Pathable` annotation for model types.
- `kpaths-core` — runtime path interfaces and stable path identities.
- `kpaths-ksp` — the Kotlin Symbol Processing generator.
- `kpaths-sample` — a small compile-time smoke test for the generator.

## First build

This project uses the Gradle wrapper and requires JDK 17 or newer to run Gradle.

```sh
export JAVA_HOME="$(brew --prefix openjdk@17)"
./gradlew check
```

## First vertical slice

For an annotated non-generic data class, KSP emits one stable immutable `WritableKeyPath`
singleton per accessible primary-constructor property:

```kotlin
@Pathable
data class Person(val name: String, val age: Int, val address: Address)

@Pathable
data class Address(val city: String)

Person("Blob", 100, Address("Paris"))[PersonPaths.name]
// "Blob"

Person("Blob", 100, Address("Paris")).set(
  keyPath = PersonPaths.name,
  value = "Blob Jr.",
)
// Person(name = "Blob Jr.", age = 100, address = Address(city = "Paris"))
```

Paths compose without losing their capabilities. Two writable paths produce another writable
path, so nested immutable updates stay concise:

```kotlin
import dev.kpaths.core.then

val city = PersonPaths.address.then(AddressPaths.city)
val updated = person.setting(city, "Toronto")
```

## Value-style and reference-style roots

Kotlin data classes are reference objects, but KPaths deliberately treats an annotated data class
as a value-style model: primary-constructor properties receive copy-based `WritableKeyPath`s.

```kotlin
@Pathable
data class Draft(var text: String)

val text = draft[DraftPaths.text]
val updated = draft.setting(DraftPaths.text, "Revised") // returns draft.copy(text = "Revised")
val uppercased = updated.modifying(DraftPaths.text) { it.uppercase() }
```

An annotated plain class instead exposes its accessible mutable properties as
`ReferenceWritableKeyPath`s and immutable properties as read-only `KeyPath`s:

```kotlin
@Pathable
class Session(var token: String, val identifier: String)

session[SessionPaths.token] = "new-token"
session.set(keyPath = SessionPaths.token, value = "another-token")
session[SessionPaths.identifier]
```

The generated path's equality and hash code are based on its `PathId`, rather than closure or
object identity. `PathId` contains structural `TypeId` tokens, preserving a classifier, generic
arguments, variance, and nullability without reflection:

```kotlin
@Pathable
data class Box<T>(val value: T)

val integerValue = BoxPaths.of<Int>(TypeId.of<Int>()).value
integerValue.id.rootType // TypeId("your.package.Box", [TypeArgument.invariant(TypeId.of<Int>())])
```

The preferred generic syntax uses an inline-reified factory, which captures the full type token:

```kotlin
val integerValue = BoxPaths<Int>().value
val stringListValue = BoxPaths<List<String>>().value
```

`BoxPaths.of(Int::class)` is available for simple, non-null types, while
`BoxPaths.of<List<String>>(TypeId.of<List<String>>())` remains the exact no-reflection escape
hatch. `TypeId.of<T>()` is the standard API for creating a complete type token when one is
needed.

## Sealed case paths

Sealed variants generate `CaseKeyPath`s that focus the concrete Kotlin case object, rather than a
tuple-like payload. A failed extraction is `null`; nullable fields remain safely inside the
non-null matched case object.

```kotlin
@Pathable
sealed interface Connection {
  data object Disconnected : Connection
  data class Connected(val host: String?) : Connection
}

val connection: Connection = Connection.Connected(host = null)

connection[ConnectionPaths.Connected]
// Connection.Connected(host = null)

Connection.Disconnected[ConnectionPaths.Connected]
// null

connection[ConnectionPaths.Connected]
  ?.get(keyPath = ConnectionPaths.ConnectedPaths.host)
// null, because the matched case's host is null
```

Case-path names retain their Kotlin declaration spelling: class-like sealed cases use `Connected`,
while enum entries use `NORTH`. The nested `ConnectedPaths` namespace is generated inside the
KSP-owned `ConnectionPaths` object; an eventual compiler-plugin façade may offer
`Connection.Paths` as an additional spelling.

Kotlin backticked identifiers remain valid in generated paths. KSP preserves their semantic name
and emits the required backticks for properties, enum entries, and sealed cases:

```kotlin
@Pathable
data class KeywordProperty(val `display name`: String)

KeywordProperty("Blob")[KeywordPropertyPaths.`display name`]
// "Blob"

@Pathable
enum class KeywordDirection { `when` }

KeywordDirection.`when`[KeywordDirectionPaths.`when`]
// Unit
```
## Enum case paths

Enum entries have no associated data, so their generated `CaseKeyPath` focus is Kotlin's `Unit`
(the equivalent of Swift's `Void`):

```kotlin
@Pathable
enum class Direction { NORTH, SOUTH }

Direction.NORTH[DirectionPaths.NORTH] // Unit
Direction.SOUTH[DirectionPaths.NORTH] // null
DirectionPaths.NORTH.embed(Unit)      // Direction.NORTH
```

The first case-path slice supports non-generic sealed classes, sealed interfaces, and enums.

## Generic sealed case paths

Generic sealed roots preserve their concrete type arguments in their generated path identities:

```kotlin
@Pathable
sealed interface Loadable<out Value> {
  data object Idle : Loadable<Nothing>
  data class Loaded<T>(val value: T) : Loadable<T>
}

val paths = LoadablePaths<String>()
val state: Loadable<String> = Loadable.Loaded("Blob")

state[paths.Loaded]?.get(keyPath = paths.LoadedPaths.value)
// "Blob"
```

For a generic root, every variant must either forward a root type parameter or safely refine a
covariant parameter to `Nothing`. KPaths reports an error at `@Pathable` when Kotlin cannot embed
a variant as every specialization of the root.

## Generated-name collisions

KPaths preserves declared property and case names rather than silently renaming public paths. If
two generated members would share a namespace—for example, a `Connected` property and a
`Connected` sealed case—KSP reports an error at `@Pathable` explaining both members. Names
beginning with `__kpaths_` are reserved for generator implementation details.

By default, `@Pathable` generates `TypeNamePaths`. Supply a name to use an explicit top-level
namespace instead:

```kotlin
@Pathable("CustomRecordPaths")
data class Record(val identifier: String)

CustomRecordPaths.identifier
```

KSP diagnoses a collision with an existing top-level type, property, or function of the generated
name and points to `@Pathable`; it also diagnoses two `@Pathable` types that request the same
name. Choose a different explicit name in either case.
