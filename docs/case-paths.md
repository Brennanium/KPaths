# Case paths

KPaths’ case paths are inspired by [Point-Free’s CasePaths](https://github.com/pointfreeco/swift-case-paths), adapted to Kotlin sealed hierarchies. Kotlin gives every sealed data case its own concrete type, so the primary route is to the whole case—not to a synthetic payload tuple.

```kotlin
sealed interface Event {
  data object Started : Event
  data class Message(val id: String, val text: String) : Event
}

val message = caseKeyPath<Event, Event.Message>()
// WritableCaseKeyPath<Event, Event.Message>
```

`message` safely matches an `Event.Message` and can conditionally replace it. It needs neither KSP nor a constructor reference:

```kotlin
val event: Event = Event.Message(id = "1", text = "Hello")

event[message] // Event.Message(id = "1", text = "Hello")
Event.Started[message] // null

message.embed(event, Event.Message(id = "1", text = "Updated"))
// Event.Message(id = "1", text = "Updated")

message.embed(Event.Started, Event.Message(id = "1", text = "Ignored"))
// Event.Started
```

## Capabilities

Case paths have the same capability progression as Kotlin property paths, but every write is conditional on a successful case match:

| Type | Capability |
| --- | --- |
| `CaseKeyPath<Root, Value>` | match and extract |
| `WritableCaseKeyPath<Root, Value>` | additionally replace a matching focus with `embed(root, value)` |

`caseKeyPath<Event, Event.Message>()` returns a writable whole-case path. A path can be stored as the read-only `CaseKeyPath` supertype when an API only needs matching.

`embed(root, value)` is the one conditional write operation. It leaves a different case unchanged; `modify(root) { … }` is its update convenience.

This lets a generic API declare whether it only handles a case (`CaseKeyPath`) or conditionally
updates it (`WritableCaseKeyPath`). It does not have to accept a nullable extraction closure and
an unrelated reconstruction closure. [Route-aware APIs](route-aware-apis.md#route-sealed-values-without-repeating-when)
shows both forms.

## Case paths in Kotlin collection algorithms

Like `KeyPath`, `CaseKeyPath` is directly callable. Its function view is the intentionally lossy
`(Root) -> Value?` extraction, which makes the standard `mapNotNull` a natural way to collect one
kind of case:

```kotlin
val events = listOf<Event>(
  Event.Started,
  Event.Message("1", "Hello"),
  Event.Message("2", "Welcome"),
)

events.mapNotNull(message)
// [Event.Message(id="1", text="Hello"), Event.Message(id="2", text="Welcome")]
```

This is the Kotlin counterpart to Swift’s `compactMap(\.message)`. The returned values are the
whole concrete `Event.Message` cases, so normal Kotlin property paths can continue the work:

```kotlin
events
  .mapNotNull(message)
  .map(keyPath(Event.Message::text))
// ["Hello", "Welcome"]
```

When a case focus itself can be `null`, function invocation and `mapNotNull` deliberately discard
both nonmatches and matched nulls. Use `match` when that distinction matters.

## Following a case into a property

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

For a mutable property, provide the copying rule explicitly. This keeps an update from mutating a
case instance that another root reference may share:

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

`text.embed(root, value)` updates the copied `Message` only when `root` is `LiveEvent.Message`; an
`Idle` root is returned untouched. There is deliberately no `.then(KProperty1)` overload without
a replacement rule.

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

KSP also provides direct payload conveniences for cases with one to three primary-constructor
properties, but these are optional shortcuts. The [generated sealed-catalog guide](generation.md#generated-casepath-catalogs)
shows their exact shape, value semantics, generic-root form, and the distinction from
`RequestPaths.SearchPaths` concrete-case property paths.
