# Generated catalogs

`@Pathable` is an optional KSP convenience. It does not grant the runtime capabilities of a path:
manual factories work for any accessible property or ordinary sealed case. Its job is to generate
the canonical path objects that a source-owned model uses repeatedly.

```kotlin
@Pathable
data class Account(
  val id: String,
  var displayName: String,
)

AccountPaths.id          // KeyPath<Account, String>
AccountPaths.displayName // WritableKeyPath<Account, String>
```

This makes paths discoverable in code completion, avoids repeated property-reference adaptation,
and gives callers a shared singleton with a stable structural identity.

## Generated KeyPath catalogs

For accessible properties on an annotated type:

| Kotlin declaration | Generated path |
| --- | --- |
| `val property` | `KeyPath<Root, Value>` |
| data-class primary-constructor `var property` | copy-based `WritableKeyPath<Root, Value>` |
| mutable plain-class property | `ReferenceWritableKeyPath<Root, Value>` |

The `val`/`var` distinction is preserved even for data classes. A `val` never becomes writable
merely because its containing data class has a `copy` method.

### Static versus generic property catalogs

Kotlin object declarations cannot capture an outer type argument. KSP therefore uses an object
for non-generic roots and a catalog class for generic roots:

```kotlin
@Pathable data class Person(val name: String)
PersonPaths.name

@Pathable data class Box<Value>(val value: Value)
BoxPaths<String>().value
```

The parentheses in `BoxPaths<String>()` create the generated catalog with the concrete generic
type token. `BoxPaths<String>.value` is a type member access, not an object instance, and is not
valid Kotlin. In non-reified generic code, use the generated explicit-token `of` factory.

## Generated CasePath catalogs

Annotate the sealed root, not each individual case. KSP treats its direct sealed subtypes as the
cases of the catalog and preserves their declared Kotlin names exactly:

```kotlin
@Pathable
sealed interface Request {
  data object Cancelled : Request
  data class Message(val id: String, val text: String) : Request
  data class Retry(var attempt: Int) : Request
}
```

KSP generates an object-shaped catalog for this non-generic root:

```kotlin
RequestPaths.Cancelled
// WritableCaseKeyPath<Request, Request.Cancelled>

RequestPaths.Message
// WritableCaseKeyPath<Request, Request.Message>

RequestPaths.Retry
// WritableCaseKeyPath<Request, Request.Retry>
```

Every generated case member is first and foremost a **whole-case path**. It succeeds only for an
already-matching root and replaces that existing case with the supplied concrete case:

```kotlin
val message = RequestPaths.Message

message.match(Request.Message("1", "Hello"))
// CaseMatch.Matched(Request.Message("1", "Hello"))

message.embed(Request.Message("1", "Hello"), Request.Message("1", "Updated"))
// Request.Message("1", "Updated")

message.embed(Request.Cancelled, Request.Message("1", "Ignored"))
// Request.Cancelled
```

In particular, `embed` is a conditional replacement, not a way to switch a `Cancelled` root into
the `Message` case. This keeps generated paths consistent with every other `WritableCaseKeyPath`.

### Generated case payload conveniences

For a data-class case with one to three accessible primary-constructor properties, KSP also
generates a direct property/payload case path beneath the whole-case member:

```kotlin
RequestPaths.Retry.attempt
// WritableCaseKeyPath<Request, Int>

RequestPaths.Message.payload
// WritableCaseKeyPath<Request, Pair<String, String>>
```

These paths match the outer case, read their focus, and reconstruct a replacement case when
embedded. That is why `RequestPaths.Retry.attempt` can update `attempt` even if it had been a
`val`: it replaces `Request.Retry`, rather than setting a property on the existing object.

The generated form is especially useful when the same field-level case path is part of a model’s
public vocabulary. For a one-off route, the factory form remains clearer:

```kotlin
val text = caseKeyPath<Request, Request.Message>().then(
  get = Request.Message::text,
  set = { message, value -> message.copy(text = value) },
)
```

Cases with more than three constructor properties receive their whole-case path and the ordinary
concrete-case property catalog, but no synthetic payload tuple. Kotlin has `Pair` and `Triple`,
not a general tuple type, so KPaths deliberately does not invent one.

### Concrete-case property catalogs

KSP also creates a sibling `CaseNamePaths` catalog rooted at the concrete case type:

```kotlin
RequestPaths.MessagePaths.id
// KeyPath<Request.Message, String>

RequestPaths.RetryPaths.attempt
// WritableKeyPath<Request.Retry, Int>
```

These are ordinary property paths, useful after code already has a `Request.Message` or
`Request.Retry`. They retain the normal `val`/`var` capability rules: a `val` yields `KeyPath`; a
data-class primary-constructor `var` yields a copy-based `WritableKeyPath`.

The two generated shapes solve different problems:

| Path | Root | Purpose |
| --- | --- | --- |
| `RequestPaths.Message` | `Request` | match and conditionally replace the whole `Message` case |
| `RequestPaths.Retry.attempt` | `Request` | match `Retry` and conditionally replace its `attempt` field |
| `RequestPaths.RetryPaths.attempt` | `Request.Retry` | access `attempt` after the root is already known to be `Retry` |

`data object` cases have only the whole-case path because they have no payload. Kotlin `enum`
entries are generated similarly, focused as `WritableCaseKeyPath<Enum, Unit>` paths.

### Generic sealed roots

Kotlin nested objects cannot capture a generic root’s type argument, so a generic case catalog is
an instance. The normal reified factory gives it the necessary type token:

```kotlin
@Pathable
sealed interface Loadable<out Value> {
  data object Idle : Loadable<Nothing>
  data class Loaded<Value>(val value: Value) : Loadable<Value>
}

val strings = LoadablePaths<String>()

strings.Idle
// WritableCaseKeyPath<Loadable<String>, Loadable.Idle>

strings.Loaded
// WritableCaseKeyPath<Loadable<String>, Loadable.Loaded<String>>

strings.Loaded.value
// WritableCaseKeyPath<Loadable<String>, String>

strings.LoadedPaths.value
// KeyPath<Loadable.Loaded<String>, String>
```

In non-reified generic code, use `LoadablePaths.of(valueType)` with an explicit `TypeId` or
`KClass` where available. The catalog instance gives every generated path its fully concrete
structural identity.

### Catalog-wide inspection

Every sealed catalog also has `allCasePaths` and `caseOf(root)` for algorithms that care about a
case but not its static focus type:

```kotlin
val current: PartialCaseKeyPath<Request> = RequestPaths.caseOf(Request.Cancelled)
val everyCase: List<PartialCaseKeyPath<Request>> = RequestPaths.allCasePaths
```

`PartialCaseKeyPath` is intentionally type-erased; use a named case member when the focused case
type matters.

## Naming and diagnostics

The default catalog name is `TypeNamePaths`. Give the annotation a string argument to choose a
different public catalog name:

```kotlin
@Pathable("AccountOptics")
data class Account(val id: String)

AccountOptics.id
```

KSP preserves declared case names wherever Kotlin permits them, including backticked identifiers.
It reports collisions that would make a generated catalog or member ambiguous. Generated internal
metadata uses the deliberately reserved `__kpaths_` prefix; applications should not declare that
prefix in an annotated model.

## When not to use it

Use a manual property-reference path when the property is one-off, belongs to a third-party type,
or the model should not take a code-generation dependency. Use a manual named factory for a
computed route that is not a Kotlin property. `@Pathable` is most valuable when a model's paths
are part of a reusable public API.
