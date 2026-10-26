# CaseKeyPaths

KPaths’ CaseKeyPaths are inspired by [Point-Free’s CasePaths](https://github.com/pointfreeco/swift-case-paths).
A `CaseKeyPath<Root, Value>` is a reusable, hashable description of one sealed case and the value
it carries. Instead of repeating a local `when`, code can retain the case description, match it,
extract from it, construct it, compose it, and hand it to a generic API.

## Make a CaseKeyPath

The simple manual form focuses the entire concrete case. It is the default for a one-off or
third-party sealed hierarchy:

```kotlin
val message: CaseKeyPath<Event, Event.Message> =
  caseKeyPath<Event, Event.Message>()
```

For a source-owned hierarchy used repeatedly, generate the canonical path and expose it with the
sealed root:

```kotlin
@Pathable
sealed interface Event {
  companion object {}

  data object Started : Event
  data class Message(val text: String) : Event
}

val message = Event.Paths.Message
```

`EventPaths.Message` is the same canonical CaseKeyPath and remains available without a companion.
For an enum entry, use `caseKeyPath(Entry)`; its focus is `Unit`. The reflection-backed and explicit
payload factories are covered after the core behaviors below.

You do not need to keep a separate `"Event.Message"` string correct. The whole-case factory refers
to `Event.Message` directly, and generated catalogs use the names from the sealed declaration. If
you rename a root, case, or case property, normal Kotlin refactoring and KSP update the path code
too.

## What a CaseKeyPath gives an API

```kotlin
interface CaseKeyPath<Root, Value> {
  fun match(root: Root): CaseMatch<Value>
  fun extract(root: Root): Value?
  fun embed(value: Value): Root
}
```

`match` distinguishes “this is not the case” from “this case carries `null`.” `extract` is the
convenient lossy form when a nullable result is all an algorithm needs:

```kotlin
val events: List<Event> = listOf(Event.Started, Event.Message("Hello"))

events.mapNotNull(message)       // [Event.Message("Hello")]
Event.Started[message]           // null
Event.Message("Hello")[message] // Event.Message("Hello")
message.matches(Event.Message("Hello")) // true
```

Embedding is independent of an existing root: a CaseKeyPath can construct the enclosing case from
its focus. That is why generic code can use the same value for both matching and construction:

```kotlin
message.embed(Event.Message("Updated"))
// Event.Message("Updated")
```

CaseKeyPaths compose through nested sealed cases while retaining that ability to construct every
enclosing case. Generated top-level catalogs provide especially concise payload paths for this:

```kotlin
val renameText = AppEventPaths.Profile.action
  .then(ProfileActionPaths.Editor.action)
  .then(EditorActionPaths.Rename.text)

renameText.embed("Jr.")
// AppEvent.Profile(ProfileAction.Editor(EditorAction.Rename("Jr.")))
```

There is no `WritableCaseKeyPath`: a genuine CaseKeyPath always embeds a complete case. `val`/`var`
capability distinctions belong to ordinary Kotlin KeyPaths.

## Detailed factory choices

For a source-owned sealed hierarchy used repeatedly, `@Pathable` is the preferred choice. Add a
companion object when you own the sealed root to make its canonical CaseKeyPaths discoverable under
the root itself:

```kotlin
@Pathable
sealed interface Event {
  companion object {}

  data object Started : Event
  data class Message(val text: String) : Event
  data class Navigate(val id: String, val destination: String) : Event
}

Event.Paths.Message
// CaseKeyPath<Event, Event.Message>

Event.Paths.MessagePaths.text
// KeyPath<Event.Message, String>

Event.Paths.Navigate
// CaseKeyPath<Event, Event.Navigate>
```

The same whole-case values remain available from the always-generated top-level catalog, so a
companion is only a discoverability upgrade. The top-level catalog also retains its direct payload
conveniences:

```kotlin
EventPaths.Message == Event.Paths.Message
// CaseKeyPath<Event, Event.Message>

EventPaths.Message.text
// CaseKeyPath<Event, String> (a generated direct-payload convenience)

EventPaths.Navigate.destinationFor(id = "settings")
// CaseKeyPath<Event, String>
```

For one-off and external hierarchies, choose the smallest manual factory that fully describes the
case:

| Need | Factory | Use it when |
| --- | --- | --- |
| Reuse a source-owned case | generated `Event.Paths.Message` or `EventPaths.Message` | Canonical and no-reflection; prefer `Event.Paths` when a source-owned companion exists. |
| Match and embed the complete concrete case | `caseKeyPath<Root, Case>()` | The case object itself is the desired focus. |
| Match one enum entry | `caseKeyPath(Entry)` | A Kotlin `enum` entry has no associated payload. |
| Reflect a conventional data-class constructor (JVM) | `casePathsOf<Root>().pathFor(::Case)` | Add `kpaths-reflection-jvm`; a one-off data case has one to three stored constructor properties. |
| Match and embed one payload property | `caseKeyPath(extract, embed)` | A `KProperty1` and one-argument constructor or embedder describe the case. |
| Match and embed two or three payload properties | `caseKeyPath(extract1, extract2[, extract3], embed)` | A `Pair` or `Triple` is an acceptable focus. |
| Define a nonstandard case algorithm | `caseKeyPath(id, valueType, match, embed)` | A library needs full control of identity, matching, and embedding. |

### Whole cases and enum entries

The whole-case factory has no reflection and no constructor requirement:

```kotlin
val message: CaseKeyPath<Event, Event.Message> =
  caseKeyPath<Event, Event.Message>()

message.embed(Event.Message("Hello"))
```

For a Kotlin enum, the focused value is `Unit` because the entry itself is already fully known:

```kotlin
enum class Compass { NORTH, SOUTH }

val north: CaseKeyPath<Compass, Unit> = caseKeyPath(Compass.NORTH)
north.embed(Unit) // Compass.NORTH
```

### Reflection-backed constructor factories

`casePathsOf<Root>().pathFor(::Case)` is the short manual factory for a conventional data-class
case. It is a JVM-only convenience. The `kpaths-jvm` umbrella includes it with the core and
annotations; depend on `kpaths-reflection-jvm` directly only when you intentionally use the
lower-level modules:

```kotlin
implementation("io.github.brennanium:kpaths-jvm:<version>")
```

Import a nested case so Kotlin can use its constructor reference directly:

```kotlin
import example.Event.Message
import example.Event.Navigate

val text: CaseKeyPath<Event, String> =
  casePathsOf<Event>().pathFor(::Message)
val navigate: CaseKeyPath<Event, Pair<String, String>> =
  casePathsOf<Event>().pathFor(::Navigate)
```

It reflects and validates the constructor shape once. Unary cases focus their one property; binary
and ternary cases focus `Pair` and `Triple`. Prefer a generated catalog when this CaseKeyPath is
on a hot or repeated path, and use an explicit factory for non-data cases, computed extraction,
or more than three constructor values.

### Explicit payload factories

The explicit factory avoids reflection while retaining property names in the `PathId`:

```kotlin
import example.Event.Message
import example.Event.Navigate
import example.Event.Replace

val text: CaseKeyPath<Event, String> = caseKeyPath(
  extract = Event.Message::text,
  embed = ::Message,
)

val navigate: CaseKeyPath<Event, Pair<String, String>> = caseKeyPath(
  extract1 = Event.Navigate::id,
  extract2 = Event.Navigate::destination,
  embed = ::Navigate,
)
```

The three-property overload is the same shape and focuses `Triple<First, Second, Third>`:

```kotlin
val replace: CaseKeyPath<Event, Triple<String, String, Boolean>> = caseKeyPath(
  extract1 = Event.Replace::id,
  extract2 = Event.Replace::destination,
  extract3 = Event.Replace::animated,
  embed = ::Replace,
)
```

For a case with four or more primary-constructor values, use the whole-case CaseKeyPath, create a
named payload data class, or write a focused custom CaseKeyPath. KPaths intentionally does not
invent an anonymous tuple type beyond `Triple`.

### Fully custom CaseKeyPaths

The low-level factory is for library-defined matching and embedding behavior. It is not the normal
application API, because it requires an explicit stable `PathId` and `TypeId`:

```kotlin
sealed interface Signal {
  data object Ping : Signal
}

val ping: CaseKeyPath<Signal, Unit> = caseKeyPath(
  id = PathId.case(TypeId.of<Signal>(), "Ping"),
  valueType = TypeId.of<Unit>(),
  match = { signal ->
    if (signal is Signal.Ping) CaseMatch.Matched(Unit) else CaseMatch.NoMatch
  },
  embed = { Signal.Ping },
)
```

The `PathId` strings in this form are library-defined identifiers, not names derived from Kotlin
declarations. Keep them stable and review them alongside any relevant rename.

When the root and focus types are reified, the overload without `valueType` captures that token
automatically. Generated catalogs and the higher-level factories should remain the default.

## Multi-value cases and bound CaseKeyPaths

KSP generates a named context-bound `CaseKeyPath` factory for every property of a two- or three-value case:

```kotlin
@Pathable
sealed interface Request {
  data class Navigate(val id: String, val destination: String) : Request
}

val destination = RequestPaths.Navigate.destinationFor(id = "settings")
destination.embed("privacy") // Request.Navigate("settings", "privacy")
Request.Navigate("profile", "privacy")[destination] // null
```

`propertyFor(...)` binds the remaining one or two constructor values, matches only that context, and independently embeds the focused value. This is useful for identifier-plus-action envelopes.

## Generic roots and identity

On the JVM, `casePathsOf<Root>()` retains complete reified generic metadata:

```kotlin
val loaded: CaseKeyPath<Loadable<String>, String> =
  casePathsOf<Loadable<String>>().pathFor(::Loaded)
```

Generated `LoadablePaths<String>().Loaded.value` carries the same concrete identity. Both manual and generated CaseKeyPaths compare and hash structurally by `PathId`; generated catalogs remain the most ergonomic no-reflection form. Read [Generated catalogs](generation.md) for static versus generic catalog rules, [Identity and type metadata](identity.md) for the equality model, and [recipes](recipes.md) for collection and nested-case patterns.
