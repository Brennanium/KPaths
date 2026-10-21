# KPaths

KPaths is a Kotlin path library inspired by Swift key paths and case paths. It provides generated,
stable, typed property and case paths for ordinary Kotlin models; reducer-based architectures are
one future use case, not the library's framing. Apply one `@Pathable` annotation: data/class
properties generate key paths, while sealed and enum variants generate case key paths.

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

PersonPaths.name.get(Person("Blob", 100, Address("Paris")))
// "Blob"

PersonPaths.name.set(Person("Blob", 100, Address("Paris")), "Blob Jr.")
// Person(name = "Blob Jr.", age = 100, address = Address(city = "Paris"))
```

Paths compose without losing their capabilities. Two writable paths produce another writable
path, so nested immutable updates stay concise:

```kotlin
import dev.kpaths.core.then

val city = PersonPaths.address.then(AddressPaths.city)
val updated = city.set(person, "Toronto")
```

## Value-style and reference-style roots

Kotlin data classes are reference objects, but KPaths deliberately treats an annotated data class
as a value-style model: primary-constructor properties receive copy-based `WritableKeyPath`s.

```kotlin
@Pathable
data class Draft(var text: String)

val updated = DraftPaths.text.set(draft, "Revised") // returns draft.copy(text = "Revised")
```

An annotated plain class instead exposes its accessible mutable properties as
`ReferenceWritableKeyPath`s and immutable properties as read-only `KeyPath`s:

```kotlin
@Pathable
class Session(var token: String, val identifier: String)

SessionPaths.token.setInPlace(session, "new-token")
SessionPaths.identifier.get(session)
```

The generated path's equality and hash code are based on its `PathId`, rather than closure or
object identity. `PathId` contains structural `TypeId` tokens, preserving a classifier, generic
arguments, variance, and nullability without reflection:

```kotlin
@Pathable
data class Box<T>(val value: T)

val integerValue = BoxPaths.of<Int>(TypeId.int).value
integerValue.id.rootType // TypeId("your.package.Box", [TypeArgument.invariant(TypeId.int)])
```

The preferred generic syntax uses an inline-reified factory, which captures the full type token:

```kotlin
val integerValue = BoxPaths<Int>().value
val stringListValue = BoxPaths<List<String>>().value
```

`BoxPaths.of(Int::class)` is available for simple, non-null types, while
`BoxPaths.of<List<String>>(TypeId.list(TypeId.string))` remains the exact no-reflection escape
hatch. Sealed and enum types currently receive their generated namespace; case-path generation is
the next vertical slice.
