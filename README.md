# KPaths

KPaths is a Kotlin path library for immutable state and sealed hierarchies. It will provide
generated, stable, typed property and case paths designed for reducer-based architectures. Apply
one `@Pathable` annotation: data/class properties generate key paths, while sealed and enum
variants generate case key paths.

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

For an annotated data class, KSP currently emits one stable immutable `WritableKeyPath` singleton
per accessible primary-constructor property:

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

The generated path's equality and hash code are based on its `PathId`, rather than closure or
object identity. Sealed and enum types currently receive their generated namespace; case-path
generation is the next vertical slice.
