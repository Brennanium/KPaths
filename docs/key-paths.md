# KeyPaths

KPaths brings [Swift-style key-path](https://docs.swift.org/latest/documentation/the-swift-programming-language/expressions/#Key-Path-Expression)
capabilities to Kotlin: a typed description of a field that can be read, compared, stored,
composed, and—when its capability permits—updated. Unlike an anonymous getter closure, a KeyPath
keeps its structural identity and value type after it leaves the call site.

## Make a KeyPath

For an ordinary accessible Kotlin property, use `keyPath` with a property reference. This is the
default construction form and Kotlin’s counterpart to Swift's `\Person.name` expression:

```kotlin
val name: KeyPath<Person, String> = keyPath(Person::name)
```

For a source-owned model whose paths are part of its normal vocabulary, generate canonical paths
and expose them beside the type:

```kotlin
@Pathable
data class Person(val name: String, var address: Address) {
  companion object
}

Person.Paths.name    // KeyPath<Person, String>
Person.Paths.address // WritableKeyPath<Person, Address>
```

`Person.Paths` is the most discoverable generated spelling. `PersonPaths.name` is always the same
canonical value and remains available when the model has no companion. See [Generated catalogs](generation.md)
for the companion and generic-catalog rules.

## What a KeyPath gives an API

A `KeyPath<Root, Value>` is a total typed read: every `Root` has exactly one `Value`. It can be read, passed to a generic function, composed with another KeyPath, and compared with another KeyPath. It is more descriptive than passing an anonymous `(Root) -> Value` getter around.

```kotlin
data class Address(var city: String)
data class Person(val name: String, var address: Address)

val name: KeyPath<Person, String> = keyPath(Person::name)
val person = Person("Blob", Address("Paris"))

person[name]                // "Blob"
person.get(keyPath = name)  // "Blob"
```

The square-bracket spelling is Kotlin operator syntax. `get(keyPath = …)` is the same operation with an explicit label, which is often clearer in an API that accepts several inputs.

KPaths also makes a KeyPath directly implement Kotlin’s function type `(Root) -> Value`. That
means it works anywhere Kotlin expects a read closure, without writing `{ person -> name[person] }`
or `Person::name` again:

```kotlin
val people = listOf(
  Person("Blob", Address("Paris")),
  Person("Jr.", Address("Toronto")),
)

people.map(name)                    // ["Blob", "Jr."]
people.sortedBy(name)               // sorted by name
people.groupBy(name)                // Map<String, List<Person>>

check(name == keyPath(Person::name)) // same structural path, not merely equal behavior
```

The KeyPath does not become “just a closure” when passed this way. It retains its `PathId`, `TypeId`,
equality, and composition behavior, so the same `name` value can also be stored or used with
`person[name]`.

This is why reusable APIs should accept `KeyPath<Root, Value>` when they need to keep track of a
field, rather than immediately erasing it to `(Root) -> Value`. A KeyPath-aware API can compare
selected fields, compose an additional KeyPath, and use the KeyPath’s identity in a registry or cache.
See [KeyPath and CaseKeyPath API design](route-aware-apis.md) for complete API designs and the boundary between a
KeyPath and an ordinary closure.

## Reading and writing

KeyPaths make reading and updating values uniform, even when a field is nested or supplied to a
generic API. Generated paths show the normal value-semantic flow: read the focused value, then
receive a revised root when setting or modifying it.

```kotlin
@Pathable
data class Draft(var text: String) {
  companion object
}

var draft = Draft("Original")

draft[Draft.Paths.text] // "Original"

draft = draft.setting(Draft.Paths.text, "Revised")
draft = draft.modifying(Draft.Paths.text) { it.uppercase() }
// Draft("REVISED")
```

The original `Draft` is never mutated. This lets an API work with any writable field—shallow or
deep—without knowing how the root is reconstructed:

```kotlin
fun <Root, Value> replacing(
  root: Root,
  path: WritableKeyPath<Root, Value>,
  value: Value,
): Root = root.setting(path, value)
```

For intentionally shared classes, a generated reference-writable path updates the existing object
instead. The same square-bracket syntax makes that choice visible at the call site:

```kotlin
@Pathable
class Session(var token: String) {
  companion object
}

val session = Session("old")
session[Session.Paths.token] = "new"
check(session.token == "new")
```

Swift has a hierarchy of `KeyPath`, `WritableKeyPath`, and `ReferenceWritableKeyPath`. KPaths uses
the same capability distinction, translated to Kotlin’s update model. The type tells a generic API
which of the operations above is safe:

| Type | Read | Write result | Mutation |
| --- | --- | --- | --- |
| `KeyPath<Root, Value>` | `get(root)` | none | none |
| `WritableKeyPath<Root, Value>` | `get(root)` | returns a `Root` | value-style replacement |
| `ReferenceWritableKeyPath<Root, Value>` | `get(root)` | returns the same `Root` | mutates that instance |

## Factory details for writable paths

KSP selects the capability for generated stored properties: `val` becomes a read-only `KeyPath`;
data-class `var` becomes a copy-based `WritableKeyPath`; and `class var` becomes a
`ReferenceWritableKeyPath`. The following factories cover models KSP cannot generate for, such as
third-party types, computed properties, and derived values.

`keyPath(Person::address)` is deliberately read-only even though `address` is declared `var`.
Kotlin cannot know whether a caller means to reconstruct a value or mutate an existing reference,
so manual code chooses either write capability explicitly.

### Supplying value-style replacement

Use `writableKeyPath` when writing should produce a replacement root. The `get` property reference supplies the read operation; `set` supplies the reconstruction that Kotlin cannot synthesize through a shared data-class interface.

```kotlin
val address = writableKeyPath(
  get = Person::address,
  set = { person, value -> person.copy(address = value) },
)

val revised = person.setting(address, Address("Toronto"))
person  // unchanged
```

`set(keyPath = …, value = …)`, `setting(…)`, and `modifying(…)` all return the resulting root. Assign that result when working with value-style state:

```kotlin
val revised = person.modifying(address) { current ->
  current.copy(city = current.city.uppercase())
}
```

For a mutable computed property, KPaths can copy first and then invoke the ordinary Kotlin setter on the copy:

```kotlin
data class User(var rawName: String) {
  var displayName: String
    get() = rawName.trim()
    set(value) { rawName = value }
}

val displayName = writableKeyPath(
  property = User::displayName,
  copy = { user -> user.copy() },
)
```

The `copy` lambda is required because Kotlin synthesizes `copy()` per data class rather than through a common protocol that a generic path factory can call.

When neither a `KProperty1` nor a `KMutableProperty1` describes the value, use the named
`writableKeyPath` overload and provide both directions:

```kotlin
val normalizedName: WritableKeyPath<User, String> = writableKeyPath(
  name = "normalizedName",
  get = { user -> user.displayName.lowercase() },
  set = { user, value -> user.copy(rawName = value.trim()) },
)
```

Use the `PathId` overload only for the same narrow library-author case as the read-only KeyPath
factory: custom structural identity or an explicit `TypeId`.

### Supplying reference mutation

Use `referenceWritableKeyPath` when a mutable class is intentionally shared and its existing instance should change:

```kotlin
class Session(var token: String)

val token = referenceWritableKeyPath(Session::token)
val session = Session("old")

session[token] = "new"
check(session.token == "new")
```

This is the Kotlin counterpart to Swift’s `ReferenceWritableKeyPath`. A data class is still a reference object in Kotlin, but KPaths treats generated data-class KeyPaths as value-style. On the JVM, a manual `ReferenceWritableKeyPath` with a data-class root is allowed and emits a runtime warning by default, because it often signals an accidental break from value semantics. Kotlin/Native cannot inspect the `data` modifier at runtime, so it safely omits that diagnostic; generated data-class paths remain copy-based on every platform.

For a derived in-place value without a mutable property reference, use the named factory and make
the mutation explicit:

```kotlin
class Progress(var completed: Int, var total: Int)

val fraction: ReferenceWritableKeyPath<Progress, Double> = referenceWritableKeyPath(
  name = "fraction",
  get = { progress -> progress.completed.toDouble() / progress.total },
  set = { progress, value -> progress.completed = (progress.total * value).toInt() },
)
```

This form is only for reference semantics. A value-style model should use `writableKeyPath(get, set)` instead.

## Composing KeyPaths

Like Swift KeyPaths, KPaths KeyPaths compose. `then` is the Kotlin-friendly spelling; `appending` is available when the longer name reads better.

```kotlin
@Pathable
data class Address(var city: String) { companion object }

@Pathable
data class Person(var address: Address) { companion object }

val personCity = Person.Paths.address.then(Address.Paths.city)
val revised = Person(Address("Paris")).setting(personCity, "Toronto")
// Person(Address("Toronto"))
```

Two writable KeyPaths compose to a `WritableKeyPath`. A reference-writable outer KeyPath composed with a writable inner KeyPath remains reference-writable. Any composition containing a read-only KeyPath is read-only: KPaths will never invent a reconstruction function just to make a composed KeyPath writable. The [API reference](api/index.md) documents the complete `then` overload set.

This capability is useful in generic update APIs. A function accepting
`WritableKeyPath<Root, Value>` can update a shallow or deeply nested field with the same
implementation, while a `KeyPath` parameter correctly refuses an operation that needs write-back.

## Optional KeyPaths

A nullable focus is still a normal, total KeyPath: every root has the property, but its value may
be absent. Its nullability is retained in the focused `TypeId`:

```kotlin
data class Address(val city: String)
data class Person(val address: Address?)

val address = keyPath(Person::address)
// KeyPath<Person, Address?>
```

Use `thenOptional` to read through a nullable focus. It is the Kotlin equivalent of a read-only
Swift optional-chaining key path:

```kotlin
val city = address.thenOptional(keyPath(Address::city))
// KeyPath<Person, String?>

city(Person(Address("Paris"))) // "Paris"
city(Person(null))             // null

val presentCities = people.mapNotNull(city)
```

`thenOptional` is deliberately read-only, even if both component KeyPaths are writable. A write
through a missing `address` has no universal meaning, and assigning `null` to the final focus
could mean either “clear `address`” or “leave it alone.” Model that policy explicitly with a
custom `writableKeyPath` instead.

### Force-unwrapping an optional KeyPath

When absence is a programming error, use `thenForceUnwrapped`. It is the explicit, dangerous
counterpart to Swift’s `!` key-path segment: it has a non-null focus and remains writable when
both components are writable, but it throws `NullPointerException` if the intermediate optional
is absent.

```kotlin
val address = writableKeyPath(
  get = Person::address,
  set = { person, value -> person.copy(address = value) },
)
val city = writableKeyPath(
  get = Address::city,
  set = { address, value -> address.copy(city = value) },
)

val requiredCity = address.thenForceUnwrapped(city)
// WritableKeyPath<Person, String>

requiredCity.set(Person(Address("Paris")), "Toronto")
requiredCity.get(Person(null)) // throws NullPointerException
```

Prefer `thenOptional` unless your domain guarantees presence. The force-unwrapped KeyPath exists
for invariants and migration boundaries, not as a replacement for modeling optional state.

## Whole values, collections, and indexes

`identityKeyPath<Root>()` focuses a whole root, analogous to Swift’s `\Type.self`. Replacing its focus is simply replacing the root value.

```kotlin
val wholePerson = identityKeyPath<Person>()
wholePerson.get(person)              // person
wholePerson.set(person, revised)     // revised
```

KPaths provides canonical KeyPaths for common Kotlin standard-library operations:

```kotlin
StringPaths.at(1).get("Blob")

val second = ListPaths<String>().at(1)
second.get(listOf("Blob", "Jr."))

val score = MapPaths<String, Int>().at("blob")
score.set(mapOf("blob" to 1), null) // removes "blob"
```

`ListPaths` is read-only. `ArrayPaths`, `MutableListPaths`, primitive-array KeyPaths, and `MutableMapPaths` are reference-writable because their Kotlin APIs mutate an existing collection. An assigned map value of `null` always removes the key, including for `Map<Key, Value?>`; KPaths does not introduce a second representation for “stored null” versus “missing key.”

When an API exposes indexed `get` and `set` callables, the factories can make the corresponding KeyPath without an intermediate closure:

```kotlin
val second = keyPath(get = List<String>::get, at = 1)
val editableSecond = referenceWritableKeyPath(
  get = MutableList<String>::get,
  set = MutableList<String>::set,
  at = 1,
)
```

## Detailed factory patterns

The property-reference factory and generated `Type.Paths` catalogs cover most application code.
The remaining factories are deliberate tools for shapes Kotlin property references cannot express:

| Need | Factory | Use it when |
| --- | --- | --- |
| Read an accessible property | `keyPath(Person::name)` | Default choice, including third-party types. |
| Reuse source-owned KeyPaths | `Person.Paths.name` or `PersonPaths.name` | The model is annotated; prefer `Person.Paths` when a source-owned companion is available. |
| Read a computed or derived value | `keyPath(name, get)` | No property reference represents the read. |
| Replace part of an immutable value | `writableKeyPath(get, set)` | You can state how a new focus produces a new root. |
| Set a mutable computed property on a copied root | `writableKeyPath(property, copy)` | A `KMutableProperty1` exists and copying first is the desired value semantics. |
| Mutate an intentionally shared class | `referenceWritableKeyPath(property)` | The root is reference-owned and in-place mutation is intentional. |
| Bind an index or map key | indexed/keyed `keyPath` or `writableKeyPath` factories | The selector is a Kotlin `get`/`set` operation rather than a property. |
| Define library-level identity explicitly | the `PathId` and `TypeId` overloads | A library must control every identity segment or lacks reified type information. |

`asKeyPath()` and `asReferenceWritableKeyPath()` are equivalent extension spellings for the
property-reference factories. They are conveniences, not a separate KeyPath model. The generated
[API reference](api/index.md) lists every overload.

### Derived KeyPaths

Use the named `keyPath` factory when a derived value has no `KProperty1`:

```kotlin
val normalizedName: KeyPath<Person, String> = keyPath("normalizedName") { person ->
  person.name.trim().lowercase()
}
```

The name is part of the KeyPath’s `PathId`. Use a stable, root-local name; two separately-created
KeyPaths with the same root type and name compare equal.

The `PathId` overload is lower level. It is for library authors who need a custom multi-segment
identity or who must pass an explicit `TypeId`:

```kotlin
val normalizedName: KeyPath<Person, String> = keyPath(
  id = PathId.custom(TypeId.of<Person>(), "normalizedName"),
  valueType = TypeId.of<String>(),
  get = { person -> person.name.trim().lowercase() },
)
```

Application code should normally use the named factory instead.

## Where to go next

This guide owns the ordinary property-reference and writable-path story. For named derived,
indexed, keyed, and whole-value paths, continue to [Advanced KeyPaths](advanced-paths.md). For the
generated-catalog naming, diagnostics, and sealed-case surface of `@Pathable`, continue to
[Generated catalogs](generation.md). For why `PathId` and `TypeId` make these values comparable,
read [Identity and type metadata](identity.md); the [API-design guide](route-aware-apis.md) shows
how to retain that information in your own APIs.
