# Key paths

`KeyPath<Root, Value>` is a total, typed read route: for every `Root`, it produces a `Value`.
Use it when an algorithm needs to name a property, compose a route, or compare routes without
holding on to an ad-hoc getter closure.

```kotlin
val title: KeyPath<Article, String> = keyPath(Article::title)

article[title]
article.get(keyPath = title)
```

The square-bracket spelling is an operator extension. The named `get(keyPath = …)` spelling is
useful when explicitness reads better at a call site.

## Choose the capability deliberately

| Type | Read | Replacement result | Mutates the supplied root? |
| --- | --- | --- | --- |
| `KeyPath<Root, Value>` | `get(root)` | none | no |
| `WritableKeyPath<Root, Value>` | `get(root)` | `set(root, value): Root` | only if its implementation chooses to |
| `ReferenceWritableKeyPath<Root, Value>` | `get(root)` | returns the same root | yes |

`WritableKeyPath` is the value-style operation. It returns a root, so the caller can preserve the
old value or assign the replacement explicitly:

```kotlin
data class Profile(var displayName: String)

val displayName = writableKeyPath(
  get = Profile::displayName,
  set = { profile, value -> profile.copy(displayName = value) },
)

val revised = Profile("Blob").setting(displayName, "Blob Jr.")
```

`ReferenceWritableKeyPath` is the explicit opt-in for a mutable class. Its `setInPlace` operation
changes the instance it receives; Kotlin also permits the natural assignment spelling:

```kotlin
class Session(var token: String)

val token = referenceWritableKeyPath(Session::token)
val session = Session("old")

session[token] = "new"
check(session.token == "new")
```

Do not treat a `data class` as automatically value-semantic merely because Kotlin supports
`copy()`: it is still a reference object. KPaths treats generated data-class paths as
value-style, and warns when a manually-created reference-writable path targets a data class.

## Property references and custom paths

For ordinary stored or computed reads, a Kotlin property reference is the shortest manual path:

```kotlin
val length = keyPath(String::length)
val title = keyPath(ThirdPartyArticle::title)
```

`keyPath` intentionally returns a read-only path even for a `var`. That prevents accidentally
choosing reference mutation where a copy-based replacement was intended. Pick one of these
writable factories instead:

```kotlin
// You supply value-style reconstruction.
val name = writableKeyPath(
  get = Person::name,
  set = { person, value -> person.copy(name = value) },
)

// A computed mutable property can be set after copying a root.
val label = writableKeyPath(
  property = Person::label,
  copy = { person -> person.copy() },
)

// A mutable class can use its ordinary setter in place.
val token = referenceWritableKeyPath(Session::token)
```

For a route that is not represented by a property, use a stable root-local name. Equal names on
the same concrete root type describe equal paths, so choose a name as carefully as a public API
identifier:

```kotlin
val normalizedName = keyPath<Person, String>("normalizedName") { person ->
  person.name.trim().lowercase()
}
```

The lower-level `PathId` and `TypeId` overloads are for libraries that need to define a
multi-segment custom identity. Most application code should use a property reference, a generated
catalog, or the named factory above.

## Composition

Use `then` for Kotlin-friendly composition, or `appending` when the longer spelling is clearer.
Capabilities compose conservatively:

```kotlin
val city = PersonPaths.address.then(AddressPaths.city)

person[city]
person.setting(city, "Toronto")
```

Two `WritableKeyPath`s compose to a `WritableKeyPath`. A reference-writable outer path preserves
reference mutation when composed with a writable inner path. Any composition involving a
read-only component is read-only, because KPaths cannot soundly invent a replacement operation.

## Whole values and collections

`identityKeyPath<Root>()` focuses the full root. Replacing it is simply functional whole-value
replacement.

KPaths also supplies canonical paths for the unambiguous parts of Kotlin's standard library:

```kotlin
StringPaths.at(1).get("Blob")

val second = ListPaths<String>().at(1)
second.get(listOf("Blob", "Jr."))

val score = MapPaths<String, Int>().at("blob")
score.set(mapOf("blob" to 1), null) // removes "blob"
```

`ListPaths` is read-only. `ArrayPaths`, `MutableListPaths`, primitive-array paths, and
`MutableMapPaths` are reference-writable because their Kotlin APIs mutate an existing collection.
For both immutable and mutable map paths, assigning `null` removes the key. That policy is
intentional even for `Map<Key, Value?>`: KPaths does not attempt to represent the otherwise
ambiguous distinction between an absent key and a stored null value.

The indexed callable factories are useful when an API has a `get`/`set` pair but no generated
catalog:

```kotlin
val second = keyPath(get = List<String>::get, at = 1)
val editableSecond = referenceWritableKeyPath(
  get = MutableList<String>::get,
  set = MutableList<String>::set,
  at = 1,
)
```
