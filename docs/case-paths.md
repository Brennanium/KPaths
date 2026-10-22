# Case paths

`CasePath<Root, Value>` describes one reconstructable variant of a sealed hierarchy. It has two
fundamental operations:

- `match(root)` returns `CaseMatch.NoMatch` or `CaseMatch.Matched(value)`.
- `embed(value)` constructs the represented case as a `Root`.

The match result is deliberately not nullable. A nullable result cannot distinguish a different
case from a matching case whose payload itself is `null`.

```kotlin
@Pathable
sealed interface Event {
  data object Started : Event
  data class Message(val text: String?) : Event
}

val message = caseKeyPath<Event, Event.Message>()
val text = casePath<Event, Event.Message, String?>(
  extract = Event.Message::text,
  embed = ::Event.Message,
)

when (val result = Event.Message(null).match(text)) {
  CaseMatch.NoMatch -> error("not a message")
  is CaseMatch.Matched -> println(result.value) // null, but definitely matched
}
```

`root[casePath]` is shorthand for `root.match(casePath)`. If only the predicate matters, use
`root.caseMatches(casePath)`: it returns `true` for `CaseMatch.Matched(null)`.

## CaseKeyPath: the non-null specialization

`CaseKeyPath<Root, Value : Any>` is a `CasePath` for a focus that can never be null. It adds
`extract(root): Value?`, where `null` can safely mean “this is another case.” Generated whole-case
paths are normally this type because Kotlin case objects are non-null.

```kotlin
val started: CaseKeyPath<Event, Event.Started> = EventPaths.Started

Event.Started[started]          // Event.Started
Event.Message("hello")[started] // null
```

`matching()` gives a `CaseKeyPath` a read-only `KeyPath<Root, Value?>` view. It is intentionally
not available on every `CasePath`: the nullable key-path result would collapse `NoMatch` and
`Matched(null)`.

## Generated case catalogs

Annotating a sealed root produces a whole-case path for every direct variant. A data-class variant
with one, two, or three accessible primary-constructor properties also gets a direct reconstructable
payload path:

```kotlin
@Pathable
sealed interface Request {
  data class Search(val query: String) : Request
  data class Navigate(val id: String, val replace: Boolean) : Request
  data class Route(val id: String, val destination: String, val replace: Boolean) : Request
  data class Note(val text: String?) : Request
}

RequestPaths.Search.value       // CaseKeyPath<Request, String>
RequestPaths.Navigate.payload   // CaseKeyPath<Request, Pair<String, Boolean>>
RequestPaths.Route.payload      // CaseKeyPath<Request, Triple<String, String, Boolean>>
RequestPaths.Note.value         // CasePath<Request, String?>
```

There is no anonymous tuple type beyond `Triple` in Kotlin. Variants with more than three payload
properties retain their whole-case path and generated property paths; introduce a named payload
type when that group deserves a first-class route.

## Manual case paths

Use `caseKeyPath<Root, Case>()` for the whole concrete case of a sealed hierarchy. Constructor and
property references make the common single-property payload route concise:

```kotlin
sealed interface Navigation
data class OpenDocument(val document: Document) : Navigation

val openDocument = caseKeyPath<Navigation, OpenDocument>()
val document = caseKeyPath<Navigation, OpenDocument, Document>(
  extract = OpenDocument::document,
  embed = ::OpenDocument,
)
```

The exact `casePath` factory has the same property/constructor form and is the right choice when
the payload may be nullable. Its lower-level overload receives an explicit `CaseMatch`:

```kotlin
val exact = casePath<Navigation, String?>(
  id = PathId.custom(TypeId.of<Navigation>(), "optionalDocumentName"),
  match = { navigation -> /* return CaseMatch.NoMatch or CaseMatch.Matched(value) */ },
  embed = { name -> /* construct the matching Navigation case */ },
)
```

For multi-property cases, `caseKeyPath` has two- and three-property overloads that use `Pair` and
`Triple` respectively. Their `extract1`, `extract2`, and `extract3` arguments document the
payload order, and the constructor reference proves how that ordered data reconstructs the case.

## Composition and deliberate null handling

Case paths compose with `then`. When all focuses are non-null, composing `CaseKeyPath`s returns a
`CaseKeyPath`; the general `CasePath` overload retains exact match semantics.

An exact path with a nullable focus cannot automatically continue into another path: there is no
root value to inspect when it matches `null`. Handle that decision at the use site. If treating a
matched null as “do not continue” is correct for an algorithm, `CaseMatch.requiringValue()` makes
that collapse explicit rather than hiding it in composition.
