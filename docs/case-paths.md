# Case paths

KPaths’ case paths are inspired by [Point-Free’s CasePaths](https://github.com/pointfreeco/swift-case-paths), adapted to Kotlin sealed hierarchies. Their important job is not to replace a local `when`: it is to make a case’s associated data a typed, reusable route value.

In Swift, an enum case’s associated values are the natural focus of a case path because the case has no separate nominal type. Kotlin sealed data cases *do* have a concrete type, so KPaths starts with a whole-case route and then projects the case data that matters. The whole case preserves every other field when a payload update is written back.

```kotlin
sealed interface Event {
  data object Started : Event
  data class Message(val id: String, val text: String) : Event
}

val message = caseKeyPath<Event, Event.Message>()
// WritableCaseKeyPath<Event, Event.Message>
```

`message` safely matches an `Event.Message` and conditionally replaces it. It needs neither KSP nor a constructor reference:

```kotlin
val event: Event = Event.Message(id = "1", text = "Hello")

event[message] // Event.Message(id = "1", text = "Hello")
Event.Started[message] // null

message.embed(event, Event.Message(id = "1", text = "Updated"))
// Event.Message(id = "1", text = "Updated")

message.embed(Event.Started, Event.Message(id = "1", text = "Ignored"))
// Event.Started
```

## Focus the associated data

For a case with one payload, the direct factory is the closest Kotlin equivalent of focusing a Swift enum case’s associated value:

```kotlin
@Pathable
sealed interface DownloadEvent {
  data object Started : DownloadEvent
  data class Finished(val location: String) : DownloadEvent
  data class Failed(val message: String) : DownloadEvent
}

val location = caseKeyPath<DownloadEvent, DownloadEvent.Finished, String>(
  extract = DownloadEvent.Finished::location,
  embed = ::DownloadEvent.Finished,
)
// WritableCaseKeyPath<DownloadEvent, String>

```

The path does both pieces of work: it extracts a `String` only from `Finished`, and it can replace that payload only when the root already is `Finished`:

```kotlin
location.embed(DownloadEvent.Finished("/tmp/old"), "/tmp/new")
// DownloadEvent.Finished(location="/tmp/new")

location.embed(DownloadEvent.Started, "/tmp/new")
// DownloadEvent.Started
```

This is where a case path becomes substantially more useful than an inline `when`: `location` can be stored, passed to a generic API, used directly with `mapNotNull`, and composed just like any other route. Use a `when` for a one-off local decision; use a case path when the associated data is part of a reusable operation.

For a source-owned sealed hierarchy, KSP gives this same payload route a short, canonical name.
This is the most direct counterpart to the CasePaths pattern `events.compactMap(\.finished)`:

```kotlin
val locations = downloadEvents.mapNotNull(DownloadEventPaths.Finished.location)
// List<String>
```

`DownloadEventPaths.Finished.location` is a `WritableCaseKeyPath<DownloadEvent, String>`. It
does not merely identify that an event is `Finished`; it extracts the associated `location` value
and conditionally replaces that value in an already-matching event.

For a multi-property case, start with its whole-case route and focus the field with a copy rule. The rule retains every other property from the already-matched case:

```kotlin
val messageText = caseKeyPath<Event, Event.Message>().then(
  get = Event.Message::text,
  set = { message, text -> message.copy(text = text) },
)
// WritableCaseKeyPath<Event, String>

val eventValues = listOf<Event>(Event.Started, Event.Message("1", "Hello"))
val texts = eventValues.mapNotNull(messageText)
val updated = messageText.embed(Event.Message("1", "Hello"), "Updated")
// Event.Message(id="1", text="Updated")
```

This is payload focus, not whole-case replacement. The whole-case path is simply the mechanism that lets the update keep `id` without asking for a lossy `String -> Event.Message` constructor.

For a case whose useful focus is two or three independent values, the direct factories return Kotlin’s standard `Pair` or `Triple`:

```kotlin
sealed interface NetworkEvent {
  data object Connecting : NetworkEvent
  data class Failed(val code: Int, val message: String) : NetworkEvent
}

val failure = caseKeyPath<NetworkEvent, NetworkEvent.Failed, Int, String>(
  extract1 = NetworkEvent.Failed::code,
  extract2 = NetworkEvent.Failed::message,
  embed = ::NetworkEvent.Failed,
)
// WritableCaseKeyPath<NetworkEvent, Pair<Int, String>>
```

KPaths deliberately stops there rather than inventing an anonymous tuple system. For a named field in a larger case, prefer the whole-case-plus-`then` form above.

## Capabilities

Case paths have the same capability progression as Kotlin property paths, but every write is conditional on a successful case match:

| Type | Capability |
| --- | --- |
| `CaseKeyPath<Root, Value>` | match and extract |
| `WritableCaseKeyPath<Root, Value>` | additionally replace a matching focus with `embed(root, value)` |

`caseKeyPath<Event, Event.Message>()` returns a writable whole-case path. A path can be stored as the read-only `CaseKeyPath` supertype when an API only needs matching.

`embed(root, value)` is the one conditional write operation. It leaves a different case unchanged; `modify(root) { … }` is its update convenience.

This lets a generic API declare whether it only handles a case (`CaseKeyPath`) or conditionally updates it (`WritableCaseKeyPath`). It does not have to accept a nullable extraction closure and an unrelated reconstruction closure. [Route-aware APIs](route-aware-apis.md#route-a-case-payload-without-repeating-when) shows both forms.

## Use payload paths in Kotlin collection algorithms

Like `KeyPath`, `CaseKeyPath` is directly callable. Its function view is the intentionally lossy `(Root) -> Value?` extraction, which makes the standard `mapNotNull` a natural way to collect one kind of payload:

```kotlin
val events = listOf<DownloadEvent>(
  DownloadEvent.Started,
  DownloadEvent.Finished("/tmp/one"),
  DownloadEvent.Failed("offline"),
  DownloadEvent.Finished("/tmp/two"),
)

events.mapNotNull(location)
// ["/tmp/one", "/tmp/two"]
```

This is the Kotlin counterpart to Swift’s `compactMap(\.finished)`: it filters to one case and projects its associated data in one reusable value. A whole-case path remains useful when the consumer genuinely needs the entire concrete case:

```kotlin
events
  .mapNotNull(caseKeyPath<DownloadEvent, DownloadEvent.Finished>())
  .map(keyPath(DownloadEvent.Finished::location))
// ["/tmp/one", "/tmp/two"]
```

When a case focus itself can be `null`, function invocation and `mapNotNull` deliberately discard both nonmatches and matched nulls. Use `match` when that distinction matters.

## Compose a payload route through a property

Case paths intentionally compose only with another writable route. A bare `KProperty1` is not enough: it can read a member but cannot say how an updated member becomes an updated case.

For a `val`, provide the copy/reconstruction rule explicitly. This is valid even though the property itself is immutable, because the case is replaced rather than mutated:

```kotlin
val messageText = caseKeyPath<Event, Event.Message>().then(
  get = Event.Message::text,
  set = { message, text -> message.copy(text = text) },
)
// WritableCaseKeyPath<Event, String>

messageText.embed(event, "Updated")
// Event.Message(id = "1", text = "Updated")
```

The existing matched `Event.Message` supplies `id`, so there is no need for a `String -> Event.Message` constructor.

For a mutable property, provide the copying rule explicitly. This keeps an update from mutating a case instance that another root reference may share:

```kotlin
sealed interface LiveEvent {
  data object Idle : LiveEvent
  data class Message(var text: String) : LiveEvent
}

val text = caseKeyPath<LiveEvent, LiveEvent.Message>()
  .then(
    property = LiveEvent.Message::text,
    copy = LiveEvent.Message::copy,
  )
// WritableCaseKeyPath<LiveEvent, String>
```

`text.embed(root, value)` updates the copied `Message` only when `root` is `LiveEvent.Message`; an `Idle` root is returned untouched. There is deliberately no `.then(KProperty1)` overload without a replacement rule.

## Exact nullable matching

`CaseKeyPath` itself supports nullable focuses. Use `match` when a matching null must stay distinct from a different case:

```kotlin
sealed interface Status {
  data object Idle : Status
  data class Note(val text: String?) : Status
}

val noteText = caseKeyPath<Status, Status.Note, String?>(
  extract = Status.Note::text,
  embed = ::Status.Note,
)

when (val result = Status.Note(null).match(noteText)) {
  CaseMatch.NoMatch -> error("not a note")
  is CaseMatch.Matched -> println(result.value) // null, but it matched
}
```

`root[path]` and `path.extract(root)` intentionally collapse `NoMatch` and `Matched(null)` to `null`. `root.caseMatches(path)` remains true for a matched null.

## Generated catalogs

Factories are preferred for one-off and third-party cases. `@Pathable` generates canonical whole-case paths for source-owned sealed roots:

```kotlin
@Pathable
sealed interface Request {
  data class Search(val query: String) : Request
  data class Navigate(val id: String, val destination: String) : Request
}

RequestPaths.Search
// WritableCaseKeyPath<Request, Request.Search>
```

KSP also provides canonical direct payload routes for cases with one to three primary-constructor properties. For example, a unary `Search(val query: String)` gets `RequestPaths.Search.query`, a `WritableCaseKeyPath<Request, String>`. For a two- or three-property case it generates a `payload` path focused on `Pair` or `Triple`; compose the whole-case path with a concrete-case property path when a named field is clearer. The [generated sealed-catalog guide](generation.md#generated-casepath-catalogs) shows their exact shape, value semantics, generic-root form, and the distinction from `RequestPaths.SearchPaths` concrete-case property paths.
