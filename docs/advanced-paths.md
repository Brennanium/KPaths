# Advanced KeyPaths

The usual KPaths API is small: create a `KeyPath` from a Kotlin property reference, or create a `WritableKeyPath` by also supplying its replacement behavior. This guide covers KeyPath factories for values Kotlin cannot express as a simple property reference. For sealed cases, see [CaseKeyPaths](case-paths.md).

## Define a named derived KeyPath

Swift can place computed properties and custom subscripts inside a key-path expression. In Kotlin, create the equivalent `KeyPath` with a stable custom name. That name contributes to the `KeyPath`’s identity, so it describes one conceptual read—not merely a display label.

```kotlin
data class Person(val displayName: String)

val normalizedName: KeyPath<Person, String> =
  keyPath("normalizedName") { person ->
    person.displayName.trim().lowercase()
  }

normalizedName(Person("  Blob  ")) // "blob"
listOf(Person("  Blob  ")).map(normalizedName) // ["blob"]
```

Two independently created `KeyPath`s with the same root type and name compare equal. Do not reuse a name for unrelated computations.

## Define a named WritableKeyPath

A computed value becomes a `WritableKeyPath` only when the application can state how a replacement value produces a replacement root. This is Kotlin’s equivalent of supplying the getter and setter portions of a custom Swift subscript:

```kotlin
data class Person(val displayName: String)

val normalizedName: WritableKeyPath<Person, String> = writableKeyPath(
  name = "normalizedName",
  get = { person -> person.displayName.trim().lowercase() },
  set = { person, value -> person.copy(displayName = value.trim()) },
)

val revised = normalizedName.set(Person("  Blob  "), "  BLOB JR.  ")
// Person(displayName = "BLOB JR.")
```

It composes exactly like a generated `WritableKeyPath`:

```kotlin
data class Account(var owner: Person)

val owner: WritableKeyPath<Account, Person> = writableKeyPath(
  get = Account::owner,
  set = { account, value -> account.copy(owner = value) },
)

val accountOwnerName: WritableKeyPath<Account, String> = owner.then(normalizedName)
```

## Create indexed KeyPaths

The indexed factories are Kotlin’s counterpart to a Swift key path such as `\[Contact][1].email`. They capture the index in a `KeyPath` rather than closing over a mutable variable:

```kotlin
data class Contact(val email: String)

val secondContact: KeyPath<List<Contact>, Contact> =
  keyPath(get = List<Contact>::get, at = 1)
val secondEmail: KeyPath<List<Contact>, String> =
  secondContact.then(keyPath(Contact::email))

secondEmail(listOf(Contact("first@example.com"), Contact("second@example.com")))
// "second@example.com"
```

For a `MutableList`, use `referenceWritableKeyPath` because Kotlin’s collection API changes the existing list instance:

```kotlin
val secondContact: ReferenceWritableKeyPath<MutableList<Contact>, Contact> =
  referenceWritableKeyPath(
    get = MutableList<Contact>::get,
    set = MutableList<Contact>::set,
    at = 1,
  )

secondContact.set(mutableListOf(Contact("a"), Contact("b")), Contact("revised"))
```

`ListPaths`, `ArrayPaths`, `MutableListPaths`, and primitive-array catalogs provide the same standard KeyPaths with named accessors.

## Create keyed Map KeyPaths

The map factory is the equivalent of a keyed Swift subscript. Its `WritableKeyPath` focus is nullable because lookup may miss; assigning `null` removes the key:

```kotlin
val showTips: WritableKeyPath<Map<String, Boolean>, Boolean?> = writableKeyPath(
  get = Map<String, Boolean>::get,
  key = "showTips",
)

val enabled = showTips.set(emptyMap(), true)
// mapOf("showTips" to true)

val removed = showTips.set(enabled, null)
// emptyMap()
```

This is a copy-based `WritableKeyPath` for `Map`. Use `referenceWritableKeyPath` with `MutableMap<String, Boolean>::get` only when mutation of a shared mutable map is intentional.

## Focus the whole value

`identityKeyPath<Root>()` is the equivalent of Swift’s `\.self`. It is a `WritableKeyPath<Root, Root>` whose replacement simply becomes the new root value:

```kotlin
val wholePerson: WritableKeyPath<Person, Person> = identityKeyPath()
val replacement = wholePerson.set(oldPerson, newPerson)
```

It is useful in generic code accepting `WritableKeyPath<Root, Value>` that sometimes needs to focus the complete root.
