# KeyPath and CaseKeyPath API design

A KeyPath or CaseKeyPath is more than a callable getter. Its type states the root and focus,
its identity states what it selects, and its capability states whether it can write back or embed
a case. That makes it useful at an API boundary where an ordinary closure would discard
information the API needs.

Kotlin closures remain excellent for a one-off computation. Prefer a KeyPath or CaseKeyPath
parameter when an API needs to retain, compose, compare, cache, inspect, or use it after accepting it. This
guide assumes familiarity with the construction APIs in [KeyPaths](key-paths.md) and [CaseKeyPaths](case-paths.md);
it is about designing APIs that receive those values.

| API needs | Accept | Why a closure alone is insufficient |
| --- | --- | --- |
| Read a known field | `KeyPath<Root, Value>` | Keeps the KeyPath available for composition and identity. |
| Transform a field and return a replacement root | `WritableKeyPath<Root, Value>` | Carries both the getter and typed write-back rule. |
| Mutate a shared reference intentionally | `ReferenceWritableKeyPath<Root, Value>` | Makes reference mutation explicit in the type. |
| Handle one branch of a sealed hierarchy | `CaseKeyPath<Root, Case>` | Carries typed matching rather than an ad-hoc nullable extractor. |
| Construct a branch payload | `CaseKeyPath<Root, Value>` | Carries matching and independent embedding. |

## Retain a field only when the API needs its semantics

For a one-off sort, Kotlin's `sortedBy { it.title }` is perfect. Accept a `KeyPath` when the API
must retain which field was selected after the immediate read: it may need to compose it, compare
it, use it for a typed update, or associate it with policy. Otherwise, accept an ordinary closure.

The [Advanced KeyPath APIs](advanced-paths.md) guide shows field schemas, sort/query plans, and
path-indexed policies. This guide focuses on the boundary types that make those APIs honest.

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

@Pathable
data class Preferences(
  val username: String,
  var sendNewsletter: Boolean,
) {
  companion object
}

val updated = update(Preferences("Blob", false), Preferences.Paths.sendNewsletter) { true }
```

The signature makes an important promise visible: it will return an updated `Root`. Passing a
read-only `KeyPath` is a compile-time error. A nested `WritableKeyPath` works with the same function
without the function knowing anything about the nesting:

```kotlin
@Pathable data class Address(var city: String) { companion object }
@Pathable data class Profile(var address: Address) { companion object }

val profileCity: WritableKeyPath<Profile, String> =
  Profile.Paths.address.then(Address.Paths.city)
val moved = update(Profile(Address("Paris")), profileCity) { "Toronto" }
```

For `ReferenceWritableKeyPath`, the same `update` signature is valid, but its result is the same
mutable root instance. That capability is explicit at the call site and can be restricted by an
API that requires value-style replacement instead.

## Handle a CaseKeyPath without repeating `when`

A `CaseKeyPath` gives an API a named, typed branch *and its payload*. The API can handle only that
payload and remain correct if unrelated cases are added later:

```kotlin
@Pathable
sealed interface DownloadEvent {
  companion object {}

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

val event: DownloadEvent = DownloadEvent.Finished("/tmp/report.json")
val location = handleCase(event, DownloadEvent.Paths.Finished) { it.location }
```

Unlike a nullable extraction closure, `match` retains the distinction between “a different case”
and “a matching case whose focused value is null.” That matters for nullable CaseKeyPaths; see
[CaseKeyPaths](case-paths.md#what-a-casekeypath-gives-an-api).

When an API needs to construct or forward a case payload, ask for `CaseKeyPath`. CaseKeyPaths do
not modify an existing root: their `embed` direction deliberately constructs the represented
case from a payload. That makes a generic forwarding operation very small and unambiguous:

```kotlin
fun <Root, Value> lift(
  path: CaseKeyPath<Root, Value>,
  value: Value,
): Root = path.embed(value)

val progress = DownloadEvent.Paths.Progress
val advanced: DownloadEvent = lift(progress, DownloadEvent.Progress(10))
```

For product values that must be updated in place conceptually—such as an existing data class with
one changed field—require a `WritableKeyPath` instead. The separate types prevent an API from
confusing “replace a field in this root” with “construct this sealed case.”

For heterogeneous schemas, registries, reversible edits, state projection, and selective
invalidation, continue to [Advanced KeyPath APIs](advanced-paths.md). That guide builds those
library-level patterns from the same boundary types.

## Keep the KeyPath type at the boundary

Because `KeyPath` implements a function type, this is tempting:

```kotlin
data class Book(val title: String)

fun <Root, Value> addColumn(read: (Root) -> Value)
```

It is appropriate when the API truly needs only one immediate read. But it irreversibly erases
KeyPath identity, write capability, and composition. Prefer this when those properties matter:

```kotlin
fun <Root, Value> addColumn(path: KeyPath<Root, Value>)
```

The caller can pass `keyPath(Book::title)` just as concisely. The callee gains a reusable KeyPath
value instead of an opaque closure. Use an explicitly closure-based overload only when arbitrary
derived computations are a first-class part of the API; do not pretend such a closure has stable
KeyPath identity.

## A useful rule of thumb

Use a closure for behavior. Use a KeyPath to address a field inside a model, or a CaseKeyPath to
match and embed a sealed case.

The difference is most valuable at reusable boundaries: generic utilities, model-driven UI,
saved configuration, subscriptions, sealed-case construction, and nested value updates. At those
boundaries, a KeyPath or CaseKeyPath turns a convention spread across closures into a checked,
composable, comparable value. For compact applications of these ideas, return to [recipes](recipes.md);
for the exact equality contract, read [identity and type metadata](identity.md).
