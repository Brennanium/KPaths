# Case paths

KPaths’ case paths are inspired by [Point-Free’s CasePaths](https://github.com/pointfreeco/swift-case-paths). A `CaseKeyPath<Root, Value>` is a reusable, hashable route that matches one sealed case and focuses a value associated with it. It makes extraction, construction, composition, and generic APIs possible without repeating local `when` expressions.

```kotlin
interface CaseKeyPath<Root, Value> {
  fun match(root: Root): CaseMatch<Value>
  fun extract(root: Root): Value?
  fun embed(value: Value): Root
}
```

`embed` always constructs a root, independent of any existing root—just like Swift CasePaths:

```kotlin
sealed interface Event {
  data object Started : Event
  data class Message(val text: String) : Event
}

val message = caseKeyPath<Event, Event.Message>()
message.embed(Event.Message("Hello")) // Event.Message("Hello")
Event.Started[message]                 // null
```

There is no `WritableCaseKeyPath`: a genuine case path always embeds a complete case. `val`/`var` capability distinctions belong to ordinary Kotlin property paths.

## Create paths

For an ordinary data-class case, use the concise reflection-backed factory. Import a nested case so its constructor can be referenced directly:

```kotlin
import example.Event.Message

val message = casePathsOf<Event>().pathFor(::Message)
```

It validates and caches the primary-constructor shape once. Unary cases focus their property; binary and ternary cases focus Kotlin’s `Pair` and `Triple`.

For a hot path, computed extraction, or an unusual model, provide both directions explicitly:

```kotlin
val text = caseKeyPath<Event, Event.Message, String>(
  extract = Event.Message::text,
  embed = ::Message,
)
```

`@Pathable` is preferred for source-owned models used repeatedly. It generates direct access and canonical singleton identity:

```kotlin
EventPaths.Message.text // CaseKeyPath<Event, String>
```

## Extract, embed, compose

Case paths are callable as their intentionally lossy `(Root) -> Value?` extraction:

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

## Multi-value cases and bound routes

KSP generates a named context-bound route for every property of a two- or three-value case:

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

Generated `LoadablePaths<String>().Loaded.value` carries the same concrete identity. Both manual and generated paths compare and hash structurally by `PathId`; generated catalogs remain the most ergonomic no-reflection form.
