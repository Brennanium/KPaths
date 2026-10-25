# Generated catalogs

`@Pathable` generates a `TypeNamePaths` catalog for data classes, ordinary classes, sealed roots,
and enums. Generated property KeyPaths follow Kotlin mutability: `val` is a read-only `KeyPath`;
data-class `var` is copy-based `WritableKeyPath`; class `var` is `ReferenceWritableKeyPath`.

Catalogs are a discoverability and repeated-use feature, not a prerequisite for paths. Use the
[KeyPaths guide](key-paths.md) for direct property factories, and the [CaseKeyPaths guide](case-paths.md)
for the factory choices available to unannotated or third-party sealed hierarchies.

## Three ways to reach a path

KPaths deliberately gives source-owned models a progressively more discoverable surface without
making any one surface mandatory:

| Model setup | Preferred use | What it provides |
| --- | --- | --- |
| No annotation | `keyPath(Person::name)` | A one-off KeyPath for any accessible property. |
| `@Pathable`, no companion | `PersonPaths.name` | A canonical generated catalog that is always available for a valid annotated model. |
| `@Pathable` plus a companion | `Person.Paths.name` | The same canonical path through the model's most discoverable namespace. |

For a source-owned model used repeatedly, the third form is the recommended default. It keeps the
catalog next to the type in code completion, and Kotlin IDEs normally offer or add the generated
`Paths` extension import when it is needed outside the model's package. The top-level catalog is
not replaced: `PersonPaths.name` and `Person.Paths.name` are the exact same canonical value.

The companion is therefore ergonomic opt-in, not a requirement. If a model cannot or should not
declare one, continue using `PersonPaths`; nothing about the generated KeyPaths or CaseKeyPaths is
lost.

## Companion access

The normal generated catalog is a top-level object such as `PersonPaths`. A source-owned,
non-generic model can also opt into the more Swift-like `Person.Paths.name` spelling by declaring
an otherwise ordinary, visible companion object:

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

Without that companion declaration, the ordinary generated catalog is still available:

```kotlin
@Pathable
data class Person(val name: String)

PersonPaths.name
// KeyPath<Person, String>
```

`Paths` is a generated extension property, not a member injected into the companion. Outside the
model's package, import it just as you would any Kotlin extension:

```kotlin
import example.Paths
import example.Person

val name = Person.Paths.name
```

For a non-generic sealed root or enum, the same facade exposes its generated case paths, nested
case-property catalogs, and type-erased case catalog:

```kotlin
@Pathable
sealed interface Event {
  companion object {}

  data object Idle : Event
  data class Message(val text: String) : Event
}

Event.Paths.Message
// CaseKeyPath<Event, Event.Message>

Event.Paths.MessagePaths.text
// KeyPath<Event.Message, String>

Event.Paths.allCasePaths
Event.Paths.caseOf(Event.Idle)
```

`@PathsName` may be applied to a companion object to rename this generated facade. This is useful
when the companion itself is named `Paths`, because a default facade would otherwise read as
`Person.Paths.Paths`:

```kotlin
@Pathable
data class Person(val name: String) {
  @PathsName("KPaths")
  companion object Paths
}

Person.Paths.KPaths.name
```

Generic catalogs need concrete type arguments for `TypeId`. Their top-level catalog always offers
all three construction forms:

```kotlin
BoxPaths<String>()
BoxPaths.of<String>()
BoxPaths.of(String::class)
```

The reified forms preserve full Kotlin type information, including nullability and nested generic
arguments. The `KClass` form is useful when a non-generic caller has only a class token, but it
cannot represent those details.

An explicitly named generic companion exposes a root-level facade. A companion whose name is not
`Paths` supports the short constructor spelling *and* both `.of` forms:

```kotlin
@Pathable
data class Box<Value>(val value: Value) {
  companion object Factory
}

Box.Paths<String>().value
Box.Paths.of<String>().value
Box.Paths.of(String::class).value
// KeyPath<Box<String>, String>
```

If the companion is itself named `Paths`, it owns the `Root.Paths` namespace. KPaths therefore
adds only the two `.of` overloads rather than a competing `Paths<T>()` function:

```kotlin
@Pathable
data class Box<Value>(val value: Value) {
  companion object Paths
}

Box.Paths.of<String>().value
Box.Paths.of(String::class).value
```

`@PathsName` customizes the facade for another named companion:

```kotlin
@Pathable
data class Box<Value>(val value: Value) {
  @PathsName("KPaths")
  companion object Factory
}

Box.KPaths<String>().value
Box.KPaths.of<String>().value
Box.KPaths.of(String::class).value
```

For a generic `companion object` with no explicit name, KPaths intentionally creates **no**
`Root.Paths` facade and reports a warning at `@Pathable`. Name it—normally `Paths` or `Factory`—or
use the top-level `RootPaths` catalog directly. This rule applies to generic classes and generic
sealed interfaces alike, so their API stays predictable.

For example, a generic sealed root can use the `Factory` form and exposes the same alternatives:

```kotlin
@Pathable
sealed interface Loadable<out Value : Any> {
  companion object Factory

  data object Idle : Loadable<Nothing>
  data class Loaded<T : Any>(val value: T) : Loadable<T>
}

Loadable.Paths<String>().Loaded.value
Loadable.Paths.of<String>().Loaded.value
// CaseKeyPath<Loadable<String>, String>
```

`Paths` (or its `@PathsName` replacement) is generated only when a catalog has content to expose.
KSP diagnoses a collision with the precise generated companion member—`Paths`, `KPaths`, or `of`—
rather than silently changing either API.

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

## Naming generated APIs

`@Pathable("CustomPaths")` names the outer catalog. `@PathName` names a generated `KeyPath`
member without changing the underlying Kotlin property or its structural identity:

```kotlin
@Pathable
data class Profile(
  @PathName("displayName")
  val legalName: String,
)

ProfilePaths.displayName
// KeyPath<Profile, String>
```

The generated path still reads `Profile.legalName`, and its `PathId` identifies `legalName`.
`@PathName` also applies to the property catalog nested under a sealed data case. It intentionally
does not rename a direct payload CaseKeyPath such as `EventPaths.Message.text`: that member is a
case payload API rather than a property KeyPath.

Within a sealed root, use `@CaseName` and `@PathsName` independently when the default case spelling
and the case property-catalog spelling need different Kotlin names:

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

`@CaseName` also applies to enum entries. All three naming annotations affect only public generated
member names: source declarations remain the structural names in their `PathId`, so renaming an API
does not change equality or hashing. `@PathsName` is valid for a companion facade or for a sealed
case that has at least one accessible property path. KSP diagnoses invalid, reserved, and colliding
generated names at the annotated declaration.

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
