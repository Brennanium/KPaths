# CaseKeyPaths

KPaths’ CaseKeyPaths are inspired by [Point-Free’s CasePaths](https://github.com/pointfreeco/swift-case-paths). A `CaseKeyPath<Root, Value>` is a reusable, hashable value that matches one sealed case and focuses a value associated with it. It makes extraction, construction, composition, and generic APIs possible without repeating local `when` expressions.

```kotlin
interface CaseKeyPath<Root, Value> {
  fun match(root: Root): CaseMatch<Value>
  fun extract(root: Root): Value?
  fun embed(value: Value): Root
}
```

`embed` always constructs a root, independent of any existing root—just like Swift CasePaths:

```kotlin
@Pathable
sealed interface Event {
  data object Started : Event
  data class Message(val text: String) : Event
  data class Navigate(val id: String, val destination: String) : Event
  data class Replace(
    val id: String,
    val destination: String,
    val animated: Boolean,
  ) : Event
}

val message = caseKeyPath<Event, Event.Message>()
message.embed(Event.Message("Hello")) // Event.Message("Hello")
Event.Started[message]                 // null
```

There is no `WritableCaseKeyPath`: a genuine CaseKeyPath always embeds a complete case. `val`/`var` capability distinctions belong to ordinary Kotlin KeyPaths.

## Choose a CaseKeyPath factory

For a source-owned sealed hierarchy used repeatedly, `@Pathable` is the preferred choice. The
annotated `Event` above gets canonical CaseKeyPaths without runtime shape discovery:

```kotlin
EventPaths.Message.text
// CaseKeyPath<Event, String>

EventPaths.Navigate.destinationFor(id = "settings")
// CaseKeyPath<Event, String>
```

For one-off and external hierarchies, choose the smallest manual factory that fully describes the
case:

| Need | Factory | Use it when |
| --- | --- | --- |
| Reuse a source-owned case | generated `EventPaths.Message.text` | Preferred: canonical, discoverable, no reflection. |
| Match and embed the complete concrete case | `caseKeyPath<Root, Case>()` | The case object itself is the desired focus. |
| Match one enum entry | `caseKeyPath(Entry)` | A Kotlin `enum` entry has no associated payload. |
| Reflect a conventional data-class constructor | `casePathsOf<Root>().pathFor(::Case)` | A one-off data case has one to three stored constructor properties. |
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
case. Import a nested case so Kotlin can use its constructor reference directly:

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

When the root and focus types are reified, the overload without `valueType` captures that token
automatically. Generated catalogs and the higher-level factories should remain the default.

## Extract, embed, compose

CaseKeyPaths are callable as their intentionally lossy `(Root) -> Value?` extraction:

```kotlin
events.mapNotNull(EventPaths.Message.text)
```

Use `match` to preserve a matched `null` payload:

```kotlin
when (val result = status.match(StatusPaths.Note.text)) {
  CaseMatch.NoMatch -> Unit
  is CaseMatch.Matched -> println(result.value) // may be null
}
```

Composition retains independent embedding through every enclosing case:

```kotlin
val renameText = AppEventPaths.Profile.action
  .then(ProfileActionPaths.Editor.action)
  .then(EditorActionPaths.Rename.text)

renameText.embed("Jr.")
// AppEvent.Profile(ProfileAction.Editor(EditorAction.Rename("Jr.")))
```

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

`casePathsOf<Root>()` retains complete reified generic metadata:

```kotlin
val loaded: CaseKeyPath<Loadable<String>, String> =
  casePathsOf<Loadable<String>>().pathFor(::Loaded)
```

Generated `LoadablePaths<String>().Loaded.value` carries the same concrete identity. Both manual and generated CaseKeyPaths compare and hash structurally by `PathId`; generated catalogs remain the most ergonomic no-reflection form.
