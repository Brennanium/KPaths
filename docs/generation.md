# Generated catalogs

`@Pathable` generates a `TypeNamePaths` catalog for data classes, ordinary classes, sealed roots, and enums. Generated property KeyPaths follow Kotlin mutability: `val` is `KeyPath`; data-class `var` is copy-based `WritableKeyPath`; class `var` is `ReferenceWritableKeyPath`.

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

The default catalog name is `TypeNamePaths`; `@Pathable("CustomPaths")` chooses another name. KSP diagnoses public naming collisions and preserves declared identifiers where Kotlin permits them.
