# Route-aware APIs

A KPaths value is more than a callable getter. It is a typed description of a route: what it
starts from, what it focuses, whether it can write back, and how it compares with another route.
That makes it useful in an API boundary where an ordinary closure would discard information the
API needs.

Kotlin closures remain excellent for a one-off computation. Prefer a path parameter when an API
needs to retain, compose, compare, cache, inspect, or write through the route after accepting it.

| API needs | Accept | Why a closure alone is insufficient |
| --- | --- | --- |
| Read a known field | `KeyPath<Root, Value>` | Keeps the route available for composition and identity. |
| Transform a field and return a replacement root | `WritableKeyPath<Root, Value>` | Carries both the getter and typed write-back rule. |
| Mutate a shared reference intentionally | `ReferenceWritableKeyPath<Root, Value>` | Makes reference mutation explicit in the type. |
| Handle one branch of a sealed hierarchy | `CaseKeyPath<Root, Case>` | Carries typed matching rather than an ad-hoc nullable extractor. |
| Construct or route a branch payload | `CaseKeyPath<Root, Value>` | Carries matching and independent embedding. |

## Describe a reusable field, not an anonymous getter

For a one-off sort, Kotlin's `sortedBy { it.title }` is perfect. When a feature must retain the
selected field—for example as a table column, saved preference, filter definition, or query
plan—make the field a path instead:

```kotlin
data class Book(
  val title: String,
  val year: Int,
)

data class SortRule<Row, Value : Comparable<Value>>(
  val field: KeyPath<Row, Value>,
  val descending: Boolean,
)

fun <Row, Value : Comparable<Value>> List<Row>.sortedBy(
  rule: SortRule<Row, Value>,
): List<Row> =
  if (rule.descending) sortedByDescending(rule.field) else sortedBy(rule.field)

val titleRule = SortRule(keyPath(Book::title), descending = false)
val yearRule = SortRule(keyPath(Book::year), descending = true)
```

`field` can still be invoked anywhere Kotlin expects `(Book) -> Value`, but the surrounding API
does not lose that it is specifically the `Book.title` or `Book.year` route. It can display a
label for the selected field, compose a nested route onto it, or compare it with another rule.

## Require write-back when an operation updates part of a value

A getter can identify a value, but cannot describe how an edited value becomes a new root. An API
that updates a focused field should require a `WritableKeyPath`, rather than accepting unrelated
read and write closures:

```kotlin
fun <Root, Value> update(
  root: Root,
  path: WritableKeyPath<Root, Value>,
  transform: (Value) -> Value,
): Root = path.modify(root, transform)

data class Preferences(
  val username: String,
  var sendNewsletter: Boolean,
)

val newsletter = writableKeyPath(
  get = Preferences::sendNewsletter,
  set = { preferences, value -> preferences.copy(sendNewsletter = value) },
)

val updated = update(Preferences("Blob", false), newsletter) { true }
```

The signature makes an important promise visible: it will return an updated `Root`. Passing a
read-only `KeyPath` is a compile-time error. A nested writable path works with the same function
without the function knowing anything about the nesting:

```kotlin
val accountCity: WritableKeyPath<Account, String> =
  accountProfile.then(profileAddress).then(addressCity)
val moved = update(accountValue, accountCity) { "Toronto" }
```

For `ReferenceWritableKeyPath`, the same `update` signature is valid, but its result is the same
mutable root instance. That capability is explicit at the call site and can be restricted by an
API that requires value-style replacement instead.

## Route a case payload without repeating `when`

A case path gives an API a named, typed branch *and its payload*. The API can handle only that
payload and remain correct if unrelated cases are added later:

```kotlin
sealed interface DownloadEvent {
  data class Progress(val percent: Int) : DownloadEvent
  data class Finished(val location: String) : DownloadEvent
  data class Failed(val message: String) : DownloadEvent
}

fun <Root, Case : Any, Result> handleCase(
  root: Root,
  path: CaseKeyPath<Root, Case>,
  handler: (Case) -> Result,
): Result? = when (val match = path.match(root)) {
  CaseMatch.NoMatch -> null
  is CaseMatch.Matched -> handler(match.value)
}

val finishedLocation = caseKeyPath<DownloadEvent, DownloadEvent.Finished, String>(
  extract = DownloadEvent.Finished::location,
  embed = { location -> DownloadEvent.Finished(location) },
)
val event: DownloadEvent = DownloadEvent.Finished("/tmp/report.json")
val location = handleCase(event, finishedLocation) { it }
```

Unlike a nullable extraction closure, `match` retains the distinction between “a different case”
and “a matching case whose focused value is null.” That matters for nullable payload routes; see
[Case paths](case-paths.md#extract-embed-compose).

When an API needs to construct or forward a case payload, ask for `CaseKeyPath`. Case paths do
not modify an existing root: their `embed` direction deliberately constructs the represented
case from a payload. That makes a generic forwarding operation very small and unambiguous:

```kotlin
fun <Root, Value> lift(
  path: CaseKeyPath<Root, Value>,
  value: Value,
): Root = path.embed(value)

val progress = caseKeyPath<DownloadEvent, DownloadEvent.Progress>()
val advanced: DownloadEvent = lift(progress, DownloadEvent.Progress(10))
```

For product values that must be updated in place conceptually—such as an existing data class with
one changed field—require a `WritableKeyPath` instead. The separate types prevent an API from
confusing “replace a field in this root” with “construct this sealed case.”

## Stable identity enables registries and caches

Two closures cannot say whether they represent the same operation. In contrast, paths compare by
their structural `PathId` and focused `TypeId`, not by allocation identity:

```kotlin
data class Book(val title: String)

val visibleColumns = linkedSetOf<AnyKeyPath>()
visibleColumns += keyPath(Book::title)
visibleColumns += keyPath(Book::title)

check(visibleColumns.size == 1)
```

That makes a path suitable as the route portion of a key for a subscription registry, prepared
query plan, view-model cache, or deduplicated configuration. A cache of *computed values* must
also include the input identity or version—`Book.title` alone is not a cache key for every book—
but the path gives the cache a stable identity for **what is being computed**.

Generated catalog paths and equivalent factory paths have the same structural identity. A catalog
provides discoverability and a canonical singleton; a factory keeps the same identity behavior
for third-party or one-off types.

## Keep the path type at the boundary

Because `KeyPath` implements a function type, this is tempting:

```kotlin
fun <Root, Value> addColumn(read: (Root) -> Value)
```

It is appropriate when the API truly needs only one immediate read. But it irreversibly erases
path identity, write capability, and composition. Prefer this when those properties matter:

```kotlin
fun <Root, Value> addColumn(path: KeyPath<Root, Value>)
```

The caller can pass `keyPath(Book::title)` just as concisely. The callee gains a reusable route
value instead of an opaque closure. Use an explicitly closure-based overload only when arbitrary
derived computations are a first-class part of the API; do not pretend such a closure has stable
path identity.

## A useful rule of thumb

Use a closure for behavior. Use a KPaths route for an address inside a model.

The difference is most valuable at reusable boundaries: generic utilities, model-driven UI,
saved configuration, subscriptions, routing, and nested value updates. At those boundaries, a
route turns a convention spread across closures into a checked, composable, comparable value.
