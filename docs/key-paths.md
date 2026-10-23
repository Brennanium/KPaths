# KeyPaths

KPaths is inspired by [Swift key-path expressions](https://docs.swift.org/latest/documentation/the-swift-programming-language/expressions/#Key-Path-Expression).
In Swift, `\Person.name` is a compiler-recognized expression that creates a typed `KeyPath<Person, String>`.
Kotlin has property references rather than key-path expressions, so KPaths uses a factory as the equivalent construction syntax:

```kotlin
// Swift: \Person.name
val name = keyPath(Person::name)
// KeyPath<Person, String>
```

This factory-first style is the normal way to create a KeyPath. It works for any accessible Kotlin property, including one declared in another module. `@Pathable` is an optional way to generate a named canonical KeyPath when a source-owned model exposes KeyPaths repeatedly; see [Generated catalogs](generation.md).

## A KeyPath that can act as a closure

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

Swift has a hierarchy of `KeyPath`, `WritableKeyPath`, and `ReferenceWritableKeyPath`. KPaths uses the same capability distinction, translated to Kotlin’s update model:

| Type | Read | Write result | Mutation |
| --- | --- | --- | --- |
| `KeyPath<Root, Value>` | `get(root)` | none | none |
| `WritableKeyPath<Root, Value>` | `get(root)` | returns a `Root` | value-style replacement |
| `ReferenceWritableKeyPath<Root, Value>` | `get(root)` | returns the same `Root` | mutates that instance |

`keyPath(Person::address)` is deliberately read-only even though `address` is declared `var`. Kotlin cannot know whether a caller means to reconstruct a value or mutate an existing reference, so choosing either write capability is explicit.

### Value-style replacement

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
val displayName = writableKeyPath(
  property = User::displayName,
  copy = { user -> user.copy() },
)
```

The `copy` lambda is required because Kotlin synthesizes `copy()` per data class rather than through a common protocol that a generic path factory can call.

### Reference mutation

Use `referenceWritableKeyPath` when a mutable class is intentionally shared and its existing instance should change:

```kotlin
class Session(var token: String)

val token = referenceWritableKeyPath(Session::token)
val session = Session("old")

session[token] = "new"
check(session.token == "new")
```

This is the Kotlin counterpart to Swift’s `ReferenceWritableKeyPath`. A data class is still a reference object in Kotlin, but KPaths treats generated data-class KeyPaths as value-style. A manual `ReferenceWritableKeyPath` with a data-class root is allowed and emits a runtime warning by default, because it often signals an accidental break from value semantics.

## Composing KeyPaths

Like Swift KeyPaths, KPaths KeyPaths compose. `then` is the Kotlin-friendly spelling; `appending` is available when the longer name reads better.

```kotlin
val address = writableKeyPath(
  get = Person::address,
  set = { person, value -> person.copy(address = value) },
)
val city = writableKeyPath(
  get = Address::city,
  set = { address, value -> address.copy(city = value) },
)

val personCity = address.then(city)
val revised = person.setting(personCity, "Toronto")
```

Two writable KeyPaths compose to a `WritableKeyPath`. A reference-writable outer KeyPath composed with a writable inner KeyPath remains reference-writable. Any composition containing a read-only KeyPath is read-only: KPaths will never invent a reconstruction function just to make a composed KeyPath writable.

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

## Computed and custom KeyPaths

Property references cover stored and computed Kotlin properties. For a KeyPath that is not based on a property, give the factory a stable root-local name:

```kotlin
val normalizedName = keyPath<Person, String>("normalizedName") { person ->
  person.name.trim().lowercase()
}
```

The name is part of the KeyPath’s identity. Two KeyPaths with the same root type and custom name compare equal, even if they were constructed separately. Use the lower-level `PathId` and `TypeId` overloads only when a library needs to define an explicit multi-segment identity; the named factory above is the normal escape hatch.

## Generated catalogs

Factories are preferred for one-off and third-party properties:

```kotlin
val title = keyPath(ThirdPartyArticle::title)
```

For a source-owned model with KeyPaths that form part of its public vocabulary, `@Pathable` generates an autocomplete-friendly catalog:

```kotlin
@Pathable
data class Person(val name: String, var address: Address)

PersonPaths.name       // KeyPath<Person, String>
PersonPaths.address    // WritableKeyPath<Person, Address>
```

The generated KeyPath is a stable singleton, but it is not a different kind of KeyPath: it has the same read/write capability and structural equality rules as a factory-created KeyPath. Use generated catalogs for discoverability and repetition, not because a property must be annotated before it can participate in KPaths.
