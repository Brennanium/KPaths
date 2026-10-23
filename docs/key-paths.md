# Key paths

KPaths is inspired by [Swift key-path expressions](https://docs.swift.org/latest/documentation/the-swift-programming-language/expressions/#Key-Path-Expression).
In Swift, `\Person.name` is a compiler-recognized expression that creates a typed route from `Person` to `String`.
Kotlin has property references rather than key-path expressions, so KPaths uses a factory as the equivalent construction syntax:

```kotlin
// Swift: \Person.name
val name = keyPath(Person::name)
// KeyPath<Person, String>
```

This factory-first style is the normal way to create a KPaths key path. It works for any accessible Kotlin property, including one declared in another module. `@Pathable` is an optional way to generate a named canonical path when a source-owned model exposes paths repeatedly; see [Generated catalogs](generation.md).

## A route that can act as a closure

A `KeyPath<Root, Value>` names a total route through a value: every `Root` has exactly one `Value` at that route. It can be read, passed to a generic function, composed, and compared with another path. It is more descriptive than passing an anonymous `(Root) -> Value` getter around.

```kotlin
data class Address(var city: String)
data class Person(val name: String, var address: Address)

val name: KeyPath<Person, String> = keyPath(Person::name)
val person = Person("Blob", Address("Paris"))

person[name]                // "Blob"
person.get(keyPath = name)  // "Blob"
```

The square-bracket spelling is Kotlin operator syntax. `get(keyPath = …)` is the same operation with an explicit label, which is often clearer in an API that accepts several inputs.

KPaths also makes a key path directly implement Kotlin’s function type `(Root) -> Value`. That
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

The path does not become “just a closure” when passed this way. It retains its `PathId`, `TypeId`,
equality, and composition behavior, so the same `name` value can also be stored or used with
`person[name]`.

## Reading and writing

Swift has a hierarchy of `KeyPath`, `WritableKeyPath`, and `ReferenceWritableKeyPath`. KPaths uses the same capability distinction, translated to Kotlin’s update model:

| Type | Read | Write result | Mutation |
| --- | --- | --- | --- |
| `KeyPath<Root, Value>` | `get(root)` | none | none |
| `WritableKeyPath<Root, Value>` | `get(root)` | returns a `Root` | value-style replacement |
| `ReferenceWritableKeyPath<Root, Value>` | `get(root)` | returns the same `Root` | mutates that instance |

`keyPath(Person::address)` is deliberately read-only even though `address` is declared `var`. Kotlin cannot know whether a caller means to reconstruct a value or mutate an existing reference, so choosing either write capability is explicit.

### Value-style replacement

Use `writableKeyPath` when writing should produce a replacement root. The `get` property reference supplies the route; `set` supplies the reconstruction that Kotlin cannot synthesize through a shared data-class interface.

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

This is the Kotlin counterpart to Swift’s `ReferenceWritableKeyPath`. A data class is still a reference object in Kotlin, but KPaths treats generated data-class paths as value-style. A manual reference-writable path with a data-class root is allowed and emits a runtime warning by default, because it often signals an accidental break from value semantics.

## Composing routes

Like Swift key paths, KPaths key paths compose. `then` is the Kotlin-friendly spelling; `appending` is available when the longer name reads better.

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

Two writable paths compose to a `WritableKeyPath`. A reference-writable outer path composed with a writable inner path remains reference-writable. Any route containing a read-only segment is read-only: KPaths will never invent a reconstruction function just to make a composed path writable.

## Whole values, collections, and indexes

`identityKeyPath<Root>()` focuses a whole root, analogous to Swift’s `\Type.self`. Replacing its focus is simply replacing the root value.

```kotlin
val wholePerson = identityKeyPath<Person>()
wholePerson.get(person)              // person
wholePerson.set(person, revised)     // revised
```

KPaths provides canonical paths for common Kotlin standard-library routes:

```kotlin
StringPaths.at(1).get("Blob")

val second = ListPaths<String>().at(1)
second.get(listOf("Blob", "Jr."))

val score = MapPaths<String, Int>().at("blob")
score.set(mapOf("blob" to 1), null) // removes "blob"
```

`ListPaths` is read-only. `ArrayPaths`, `MutableListPaths`, primitive-array paths, and `MutableMapPaths` are reference-writable because their Kotlin APIs mutate an existing collection. An assigned map value of `null` always removes the key, including for `Map<Key, Value?>`; KPaths does not introduce a second representation for “stored null” versus “missing key.”

When an API exposes indexed `get` and `set` callables, the factories can make that route without an intermediate closure:

```kotlin
val second = keyPath(get = List<String>::get, at = 1)
val editableSecond = referenceWritableKeyPath(
  get = MutableList<String>::get,
  set = MutableList<String>::set,
  at = 1,
)
```

## Computed and custom routes

Property references cover stored and computed Kotlin properties. For a route that is not a property, give the factory a stable root-local name:

```kotlin
val normalizedName = keyPath<Person, String>("normalizedName") { person ->
  person.name.trim().lowercase()
}
```

The name is part of the path’s identity. Two paths with the same root type and custom name compare equal, even if they were constructed separately. Use the lower-level `PathId` and `TypeId` overloads only when a library needs to define an explicit multi-segment identity; the named factory above is the normal escape hatch.

## Generated catalogs

Factories are preferred for one-off and third-party properties:

```kotlin
val title = keyPath(ThirdPartyArticle::title)
```

For a source-owned model with paths that form part of its public vocabulary, `@Pathable` generates an autocomplete-friendly catalog:

```kotlin
@Pathable
data class Person(val name: String, var address: Address)

PersonPaths.name       // KeyPath<Person, String>
PersonPaths.address    // WritableKeyPath<Person, Address>
```

The generated path is a stable singleton, but it is not a different kind of path: it has the same read/write capability and structural equality rules as a factory-created path. Use generated catalogs for discoverability and repetition, not because a property must be annotated before it can participate in KPaths.
