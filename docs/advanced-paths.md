# Advanced paths

The normal KPaths story is deliberately small: make a route with a property reference or a
whole-case factory, then compose it. This guide covers the escape hatches that let a library or
application model routes which Kotlin cannot express as a simple property reference.

## Define a named derived route

Swift can place computed properties and custom subscripts inside a key-path expression. In Kotlin,
the equivalent is a named custom factory. The name is not cosmetic: it becomes the stable,
root-local identity of the route.

```kotlin
data class Person(val displayName: String)

val normalizedName = keyPath<Person, String>("normalizedName") { person ->
  person.displayName.trim().lowercase()
}

normalizedName(Person("  Blob  ")) // "blob"
listOf(Person("  Blob  ")).map(normalizedName) // ["blob"]
```

Two independently created paths with the same root type and name compare equal. Choose a name
that describes one stable conceptual route; do not reuse it for unrelated computations.

## Make a derived route writable

A computed path becomes writable when the application can define its inverse or replacement
behavior. `writableKeyPath` is Kotlin’s equivalent of supplying the getter and setter portions of
a custom Swift subscript:

```kotlin
data class Person(val displayName: String)

val normalizedName = writableKeyPath<Person, String>(
  name = "normalizedName",
  get = { person -> person.displayName.trim().lowercase() },
  set = { person, value -> person.copy(displayName = value.trim()) },
)

val revised = normalizedName.set(Person("  Blob  "), "  BLOB JR.  ")
// Person(displayName = "BLOB JR.")
```

The resulting path composes exactly like a generated one:

```kotlin
data class Account(var owner: Person)

val owner = writableKeyPath(
  get = Account::owner,
  set = { account, value -> account.copy(owner = value) },
)

val accountOwnerName = owner.then(normalizedName)
// WritableKeyPath<Account, String>
```

The custom segment retains its own identity inside the composed path, so equivalent paths remain
equal even when built at different call sites.

## Model subscript-like indexed routes

The indexed factories are the Kotlin analogue of a Swift route such as `\[Contact][1].email`.
They bind the index when the path is created, rather than closing over a mutable index variable:

```kotlin
data class Contact(val email: String)

val secondContact = keyPath(get = List<Contact>::get, at = 1)
val secondEmail = secondContact.then(keyPath(Contact::email))
// KeyPath<List<Contact>, String>

secondEmail(listOf(Contact("first@example.com"), Contact("second@example.com")))
// "second@example.com"
```

For mutable collections, use the explicit reference-writable indexed factory because Kotlin’s
`MutableList` API updates the existing list instance:

```kotlin
val secondContact = referenceWritableKeyPath(
  get = MutableList<Contact>::get,
  set = MutableList<Contact>::set,
  at = 1,
)

secondContact.set(mutableListOf(Contact("a"), Contact("b")), Contact("revised"))
```

KPaths also provides `ListPaths`, `ArrayPaths`, `MutableListPaths`, and primitive-array catalog
variants when a named standard route is more readable than the factory form.

## Model a keyed map route

The map factory is the analogue of a keyed Swift subscript. Its focus is nullable because Kotlin
map lookup can miss; assigning `null` removes the key:

```kotlin
val showTips = writableKeyPath(
  get = Map<String, Boolean>::get,
  key = "showTips",
)

val enabled = showTips.set(emptyMap(), true)
// mapOf("showTips" to true)

val removed = showTips.set(enabled, null)
// emptyMap()
```

This is intentionally a copy-based route for `Map`; use `referenceWritableKeyPath` with
`MutableMap<String, Boolean>::get` when mutation of a shared mutable map is the intended model.

## Bridge a case path into a read-only optional KeyPath

`matching()` views a non-null whole-case path as an ordinary nullable KeyPath. This is useful when
an API is written in terms of KeyPaths but the source model is a sealed hierarchy:

```kotlin
sealed interface Destination {
  data object Home : Destination
  data class Editor(val draft: String) : Destination
}

val editor = caseKeyPath<Destination, Destination.Editor>()
val maybeEditor: KeyPath<Destination, Destination.Editor?> = editor.matching()
val maybeDraft = maybeEditor.thenOptional(keyPath(Destination.Editor::draft))

val visibleEditors = destinations.map(maybeEditor)
val visibleDrafts = destinations.mapNotNull(maybeDraft)
```

The bridge is read-only. Writing a nullable case view would need a universal meaning for assigning
`null`—leave the current case alone, remove it, or select a different case—and KPaths deliberately
does not guess.

For extraction from a collection, pass the case path itself to `mapNotNull(editor)` instead. That
is shorter and gives a non-null `List<Destination.Editor>`.

## Build a manual case route for an external hierarchy

The whole-case `caseKeyPath<Root, Case>()` factory already works for any accessible concrete
subtype, including one declared in another module:

```kotlin
val connected = caseKeyPath<Connection, Connection.Connected>()
val connections = connectionEvents.mapNotNull(connected)
```

When a library must focus something other than one whole concrete subtype, use the lower-level
`caseKeyPath(id = …, match = …, embed = …)` factory.
Those forms require an explicit stable `PathId`, just as a library-defined custom route must choose
a stable name. They are intended for library authors; most application code should prefer the
whole-case factory or an `@Pathable` catalog.

## Focus the whole value

`identityKeyPath<Root>()` is the equivalent of Swift’s `\.self`. It is a writable route whose
replacement is simply the new root value:

```kotlin
val wholePerson = identityKeyPath<Person>()
val replacement = wholePerson.set(oldPerson, newPerson)
```

It is useful in generic code that accepts any `WritableKeyPath<Root, Value>` and occasionally
needs to operate on the entire root as its focus.
