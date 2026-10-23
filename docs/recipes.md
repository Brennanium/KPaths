# Path recipes

KPaths lets Kotlin models carry a typed route through an application. A route can be read, passed to a collection algorithm, composed, compared, or—when it is a `WritableKeyPath`—used to produce a replacement value. These examples stand on their own; no particular UI or state-management framework is required.

## Use a KeyPath wherever Kotlin expects a reader

`KeyPath<Root, Value>` implements `(Root) -> Value`, so collection APIs can accept it directly:

```kotlin
data class Book(
  val title: String,
  val author: String,
  val finished: Boolean,
)

val title = keyPath(Book::title)
val author = keyPath(Book::author)
val finished = keyPath(Book::finished)

val titles = books.map(title)
val completedTitles = books.filter(finished).map(title)
val byAuthor = books.groupBy(author)
val alphabetical = books.sortedBy(title)
```

This is the Kotlin equivalent of using a Swift key path as a function, such as `books.filter(\\.finished).map(\\.title)`. Unlike a one-off lambda, `title` remains a comparable, composable route that can be reused in another API.

## Compose an immutable update once

Generated writable property paths make a nested data-class update reusable instead of repeatedly nesting `copy` calls:

```kotlin
@Pathable data class Address(var city: String)
@Pathable data class Profile(var address: Address)
@Pathable data class Member(val id: String, var profile: Profile)

val memberCity = MemberPaths.profile
  .then(ProfilePaths.address)
  .then(AddressPaths.city)
// WritableKeyPath<Member, String>

val moved = members.map { member ->
  member.setting(memberCity, "Toronto")
}
```

Each `var` route above is copy-based because its root is a data class: `moved` contains new `Member` values and the original values remain unchanged. The same `memberCity` can be passed to any generic API accepting `WritableKeyPath<Member, String>`.

For a third-party or one-off model, create the same route with `writableKeyPath(get = …, set = …)` and compose it with `then`.

## Collect one case's payload

Case paths make a specific sealed case into a reusable filter-and-project operation. A generated unary payload route is often all that is needed:

```kotlin
@Pathable
sealed interface FeedItem {
  data object Loading : FeedItem
  data class Notice(val text: String) : FeedItem
  data class Article(val id: String, val title: String) : FeedItem
}

val noticeText = FeedItemPaths.Notice.text
// CaseKeyPath<FeedItem, String>

val notices: List<String> = items.mapNotNull(noticeText)
```

Only `Notice` values contribute text. `Loading` and `Article` values naturally become `null` in the path's callable, lossy view. `noticeText.embed("Maintenance tonight")` constructs `FeedItem.Notice("Maintenance tonight")` without needing an existing `FeedItem`.

For an unannotated hierarchy, the explicit factory is equally direct:

```kotlin
val noticeText = caseKeyPath<FeedItem, FeedItem.Notice, String>(
  extract = FeedItem.Notice::text,
  embed = { text -> FeedItem.Notice(text) },
)
```

## Bind the context of a multi-value case

An independently embeddable case path must know every constructor argument. For a two- or three-property generated data case, KSP provides named `…For` routes that bind the other arguments:

```kotlin
val articleTitle = FeedItemPaths.Article.titleFor(id = "42")
// CaseKeyPath<FeedItem, String>

articleTitle(FeedItem.Article("42", "Original")) // "Original"
articleTitle(FeedItem.Article("7", "Other"))    // null

articleTitle.embed("Revised")
// FeedItem.Article(id = "42", title = "Revised")
```

The bound `id` is part of the route identity, so `titleFor("42")` and `titleFor("7")` are distinct paths. If all fields should be the focus instead, use `FeedItemPaths.Article.payload`, whose value is `Pair<String, String>`.

## Follow nested sealed cases

Unary case payloads compose into a route through multiple sealed hierarchies. Embedding the final payload rebuilds every wrapper on the way out:

```kotlin
@Pathable
sealed interface AppEvent {
  data class Profile(val action: ProfileAction) : AppEvent
  data object Dismissed : AppEvent
}

@Pathable
sealed interface ProfileAction {
  data class Editor(val action: EditorAction) : ProfileAction
  data object Back : ProfileAction
}

@Pathable
sealed interface EditorAction {
  data class Rename(val text: String) : EditorAction
  data object Save : EditorAction
}

val renameText = AppEventPaths.Profile.action
  .then(ProfileActionPaths.Editor.action)
  .then(EditorActionPaths.Rename.text)
// CaseKeyPath<AppEvent, String>

val names = events.mapNotNull(renameText)
val revised = renameText.embed("Blob Jr.")
// AppEvent.Profile(ProfileAction.Editor(EditorAction.Rename("Blob Jr.")))
```

This differs from a `WritableKeyPath`: the case route represents a case constructor as well as a matcher. It is therefore ideal for routing and lifting nested values, while a `WritableKeyPath` remains the tool for updating an existing product value.

## Distinguish no match from a matched null

Calling a case path or using `extract` deliberately collapses “not this case” and “this case carries null” into `null`, which is ideal for `mapNotNull`. Use `match` when the distinction matters:

```kotlin
when (val result = StatusPaths.Note.text.match(status)) {
  CaseMatch.NoMatch -> println("not a note")
  is CaseMatch.Matched -> println(result.value) // String?, including null
}
```

## Classify a sealed value without a `when`

Generated sealed catalogs expose `caseOf` for algorithms that need only stable case identity:

```kotlin
val counts = items
  .groupingBy { FeedItemPaths.caseOf(it) }
  .eachCount()
// Map<PartialCaseKeyPath<FeedItem>, Int>
```

The result is type-erased because each case has a different payload type, but its path identity remains stable. It can be compared with `FeedItemPaths.Article`, `FeedItemPaths.Notice`, and other catalog entries.

## Model an indexed or keyed route

The collection factories are Kotlin's analogue of custom subscript key paths. Bind the index or key when the path is made:

```kotlin
data class Contact(val email: String)

val secondContact = keyPath(get = List<Contact>::get, at = 1)
val secondEmail = secondContact.then(keyPath(Contact::email))

secondEmail(listOf(Contact("first@example.com"), Contact("second@example.com")))
// "second@example.com"

val showTips = writableKeyPath(
  get = Map<String, Boolean>::get,
  key = "showTips",
)

val enabled = showTips.set(emptyMap(), true)
val removed = showTips.set(enabled, null) // null removes the map key
```

See [advanced paths](advanced-paths.md) for custom property routes, computed routes, and explicit reference-mutable collection paths.
