# Generated catalogs

`@Pathable` generates a `TypeNamePaths` catalog for data classes, ordinary classes, sealed roots, and enums. Generated property KeyPaths follow Kotlin mutability: `val` is a read-only `KeyPath`; data-class `var` is copy-based `WritableKeyPath`; class `var` is `ReferenceWritableKeyPath`.

Catalogs are a discoverability and repeated-use feature, not a prerequisite for paths. Use the
[KeyPaths guide](key-paths.md) for direct property factories, and the [CaseKeyPaths guide](case-paths.md)
for the factory choices available to unannotated or third-party sealed hierarchies.

## Companion access

The normal generated catalog is a top-level object such as `PersonPaths`. A source-owned,
non-generic class or data class can also opt into the more Swift-like `Person.Paths.name` spelling
by declaring an otherwise ordinary, visible companion object:

```kotlin
@Pathable
data class Person(
  val name: String,
  var city: String,
) {
  companion object
}

PersonPaths.name == Person.Paths.name // true: the exact same canonical KeyPath
Person.Paths.city
// WritableKeyPath<Person, String>
```

`Paths` is a generated extension property, not a member injected into the companion. Outside the
model's package, import it just as you would any Kotlin extension:

```kotlin
import example.Paths
import example.Person

val name = Person.Paths.name
```

The facade is only generated for a non-generic `class` or `data class` with accessible generated
property paths. Generic types instead use their typed catalog instance, for example
`BoxPaths<String>().value`, because `Box.Companion` has no `String` type argument to retain. A
sealed root continues to expose its `TypeNamePaths` catalog. KSP diagnoses a companion that already
declares `Paths`, rather than silently changing either API.

## Multiplatform source sets

`@Pathable` can be placed in `commonMain`, but KSP runs for each compilation target. Configure the
processor for every target that compiles those models:

```kotlin
dependencies {
  add("kspJvm", project(":kpaths-ksp"))
  add("kspIosArm64", project(":kpaths-ksp"))
  add("kspIosSimulatorArm64", project(":kpaths-ksp"))
}
```

This produces the same catalog API for each target. KSP itself runs on the JVM during the build;
the generated catalogs use `kpaths-core` and can run on Kotlin/Native.

For a sealed root, every direct subtype gets a canonical `CaseKeyPath`:

```kotlin
@Pathable
sealed interface Request {
  data object Cancelled : Request
  data class Message(val id: String, val text: String) : Request
  data class Retry(val attempt: Int) : Request
}

RequestPaths.Message
// CaseKeyPath<Request, Request.Message>

RequestPaths.Message.embed(Request.Message("1", "Hello"))
```

## Naming generated sealed-case APIs

`@Pathable("CustomPaths")` names the outer catalog. Within a sealed root, use `@CaseName` and
`@PathsName` independently when the default case spelling and the case property-catalog spelling
need different Kotlin names:

```kotlin
@Pathable
sealed interface Event {
  data object Idle : Event

  @CaseName("message")
  @PathsName("MessageContentPaths")
  data class Message(val text: String) : Event
}

EventPaths.message
// CaseKeyPath<Event, Event.Message>

EventPaths.MessageContentPaths.text
// KeyPath<Event.Message, String>
```

`@CaseName` also applies to enum entries. Both annotations affect only the public generated member
name: the source declaration remains the structural case name in its `PathId`, so renaming an API
does not change equality or hashing. `@PathsName` is valid only for a sealed case that has at least
one accessible property path. KSP diagnoses invalid, reserved, and colliding generated names at the
`@Pathable` declaration.

Data-class cases with one to three primary-constructor properties also receive direct payload CaseKeyPaths:

```kotlin
RequestPaths.Retry.attempt
// CaseKeyPath<Request, Int>

RequestPaths.Message.payload
// CaseKeyPath<Request, Pair<String, String>>
```

They extract only from their case and independently construct that case when embedded. Two- and three-property cases additionally get named bound `CaseKeyPath` factories:

```kotlin
RequestPaths.Message.textFor(id = "1")
  .embed("Updated")
// Request.Message("1", "Updated")
```

Each `propertyFor(...)` binds the other payload properties, matches only that context, and focuses the named property. Cases with more than three properties get a whole-case `CaseKeyPath` and concrete-case property catalog, but no synthetic tuple CaseKeyPath.

Generic sealed roots use a catalog instance so their full `TypeId` is retained:

```kotlin
LoadablePaths<String>().Loaded.value
// CaseKeyPath<Loadable<String>, String>
```

Catalogs expose `allCasePaths` and `caseOf(root)` as `PartialCaseKeyPath<Root>` for type-erased inspection. Use a named CaseKeyPath whenever the payload type matters.

The default catalog name is `TypeNamePaths`; `@Pathable("CustomPaths")` chooses another name. KSP diagnoses public naming collisions and preserves declared identifiers where Kotlin permits them. See the [API reference](api/index.md) for annotation parameters, and [identity and type metadata](identity.md) for why generated generic catalogs retain a concrete `TypeId`.
