# Case paths

KPaths’ case-path model is inspired by [Point-Free’s CasePaths](https://github.com/pointfreeco/swift-case-paths): case paths extend the useful idea of key paths from Kotlin properties to cases of a sealed hierarchy.

A property path always succeeds because every instance has that property. A case path can fail because a sealed value may currently be a different case. It therefore packages two operations:

- **match** a root and recover its focused case value when it is the represented case;
- **embed** a focused value into a new root of the represented case.

```kotlin
sealed interface Navigation {
  data object Home : Navigation
  data class Document(val document: Document) : Navigation
}

data class Document(val title: String)

val document = caseKeyPath<Navigation, Navigation.Document, Document>(
  extract = Navigation.Document::document,
  embed = ::Navigation.Document,
)
```

As `keyPath(Person::name)` is KPaths’ counterpart to Swift’s `\Person.name`, the `caseKeyPath` and `casePath` factories are the normal manual way to construct Kotlin case paths. `@Pathable` can generate named canonical paths for a source-owned sealed root when that is more discoverable; it is not required for ordinary case-path use.

## The usual path: CaseKeyPath

`CaseKeyPath<Root, Value : Any>` is the ordinary non-null case path. Its nullable extraction result is unambiguous: `null` means the root was a different case.

```kotlin
val navigation: Navigation = Navigation.Document(Document("guide"))

navigation[document]                 // Document("guide")
Navigation.Home[document]            // null
navigation.caseMatches(document)     // true
```

Like a Swift `CaseKeyPath`, it can embed a focused value into a fresh root and update a matching root:

```kotlin
document.embed(Document("readme"))
// Navigation.Document(Document("readme"))

document.modify(navigation) { value ->
  value.copy(title = value.title.uppercase())
}
```

`modify` and `replaceIfMatching` leave a nonmatching root unchanged. This makes a case path useful in generic code without first writing a `when` expression at every call site.

### Whole-case paths

When the concrete case object itself is the focus, no extractor or constructor reference is needed. Kotlin can safely cast the root to the requested sealed subtype and embed it by upcast:

```kotlin
val home = caseKeyPath<Navigation, Navigation.Home>()
val documentCase = caseKeyPath<Navigation, Navigation.Document>()

Navigation.Home[home]                 // Navigation.Home
Navigation.Document(Document("guide"))[home] // null
```

This is why `caseKeyPath<Root, Case>()` takes no `extract` or `embed` arguments. It focuses `Case` itself. A payload path such as `CaseKeyPath<Navigation, Document>` has to name the payload property and how to reconstruct the case, so it uses `extract = …` and `embed = …`.

## Exact paths for nullable focuses

`CasePath<Root, Value>` is the more general abstraction. It returns `CaseMatch<Value>` from `match`, preserving the difference between a different case and a matching case whose focus is `null`.

```kotlin
sealed interface Event {
  data object Started : Event
  data class Message(val text: String?) : Event
}

val messageText = casePath<Event, Event.Message, String?>(
  extract = Event.Message::text,
  embed = ::Event.Message,
)

when (val result = Event.Message(null).match(messageText)) {
  CaseMatch.NoMatch -> error("not a message")
  is CaseMatch.Matched -> println(result.value) // null, but it matched
}
```

Use `root.match(path)` or `root[path]` for the exact result, and `root.caseMatches(path)` when only the boolean answer matters. `caseMatches` is `true` for `CaseMatch.Matched(null)`.

Do not choose `CasePath` merely because a case contains a nullable field. A whole-case path has a non-null focus even if one of that case’s properties is nullable:

```kotlin
val message = caseKeyPath<Event, Event.Message>()
// CaseKeyPath<Event, Event.Message>
```

Choose `CasePath` when the path’s *own focus* is nullable and your algorithm must distinguish `NoMatch` from `Matched(null)`. `CaseKeyPath.matching()` intentionally has no exact-path equivalent because `KeyPath<Root, Value?>` would erase that distinction.

## Composing case paths

Case paths compose in the same direction as key paths. `then` is the Kotlin-friendly spelling and `appending` is the explicit alternative.

```kotlin
sealed interface AccountEvent {
  data class SignedIn(val account: Account) : AccountEvent
  data object SignedOut : AccountEvent
}

data class Account(val id: String)

sealed interface AppEvent {
  data class Account(val event: AccountEvent) : AppEvent
  data object Launched : AppEvent
}

val account = caseKeyPath<AppEvent, AppEvent.Account, AccountEvent>(
  extract = AppEvent.Account::event,
  embed = ::AppEvent.Account,
)
val signedIn = caseKeyPath<AccountEvent, AccountEvent.SignedIn>()

val appSignedIn = account.then(signedIn)
// CaseKeyPath<AppEvent, AccountEvent.SignedIn>
```

Composing non-null `CaseKeyPath`s preserves `CaseKeyPath`. The general `CasePath` overload retains exact matching semantics. A nullable matched focus cannot automatically continue into another path because there may be no value to inspect; use `CaseMatch.requiringValue()` only when deliberately treating a matched null as a nonmatch is correct.

## Generated case catalogs

Factories are preferred for a one-off case, a third-party sealed hierarchy, or a type that should not take a KSP dependency. For a source-owned root whose cases form a public vocabulary, annotate it with `@Pathable`:

```kotlin
@Pathable
sealed interface Request {
  data class Search(val query: String) : Request
  data class Navigate(val id: String, val replace: Boolean) : Request
  data class Route(val id: String, val destination: String, val replace: Boolean) : Request
  data class Note(val text: String?) : Request
}

RequestPaths.Search.query       // CaseKeyPath<Request, String>
RequestPaths.Navigate.payload   // CaseKeyPath<Request, Pair<String, Boolean>>
RequestPaths.Route.payload      // CaseKeyPath<Request, Triple<String, String, Boolean>>
RequestPaths.Note.text          // CasePath<Request, String?>
```

The catalog also has whole-case paths such as `RequestPaths.Search`, a `caseOf(root)` lookup, and `allCasePaths` for generic inspection. These generated paths obey the same matching, embedding, equality, and composition rules as factory-created paths.

Kotlin has no anonymous tuple type beyond `Triple`, so direct generated payload paths intentionally stop at three primary-constructor properties. A larger payload should usually become a named data class; otherwise its whole-case path and individual generated property paths remain available.

## Factories beyond one payload

The two- and three-property `caseKeyPath` factories use Kotlin’s standard `Pair` and `Triple` rather than inventing new tuple types:

```kotlin
data class Navigate(val id: String, val destination: String) : Navigation

val navigate = caseKeyPath<Navigation, Navigate, String, String>(
  extract1 = Navigate::id,
  extract2 = Navigate::destination,
  embed = ::Navigate,
)
// CaseKeyPath<Navigation, Pair<String, String>>
```

There are deliberately no higher-arity overloads. A named payload type gives a large associated-value group a clearer API and its own properties to route through.
