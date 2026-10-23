# Path recipes

KPaths lets Kotlin models carry the same routes through an application that Swift key paths and
case paths make possible: a route can be read, passed to a collection algorithm, composed, or—if
it has a replacement rule—used to produce an updated value. This guide collects the most useful
patterns without tying them to a particular UI or state-management framework.

## Use a KeyPath wherever Kotlin expects a reader

`KeyPath<Root, Value>` implements `(Root) -> Value`. Kotlin collection APIs therefore accept a
path directly:

```kotlin
data class Book(
  val title: String,
  val author: String,
  val finished: Boolean,
)

val title = keyPath(Book::title)
val author = keyPath(Book::author)
val finished = keyPath(Book::finished)

val books: List<Book> = loadBooks()

val titles = books.map(title)
val completedTitles = books.filter(finished).map(title)
val byAuthor = books.groupBy(author)
val alphabetical = books.sortedBy(title)
```

This is the closest Kotlin spelling of Swift code such as
`books.filter(\.finished).map(\.title)`. Unlike a one-off lambda, `title` is still a comparable,
composable value that can be reused elsewhere.

That becomes important when the selected field itself is configuration—for example a reusable
sort rule, visible table column, or saved report definition. The [route-aware API guide](route-aware-apis.md#describe-a-reusable-field-not-an-anonymous-getter)
shows how to retain a path at those boundaries rather than erase it to a closure.

## Compose a deeply nested value update once

Generated writable KeyPaths make nested immutable updates reusable rather than repeatedly nesting
`copy` calls:

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

Each generated `var` path is copy-based because its root is a data class. `moved` contains new
`Member` values; `members` and its nested values are unchanged. The same path can read cities
directly:

```kotlin
val cities = members.map(memberCity)
```

The exact same `memberCity` can be passed to a generic update utility that accepts a
`WritableKeyPath<Member, String>`; it does not need special knowledge of the nesting. See
[Require write-back when an operation updates part of a value](route-aware-apis.md#require-write-back-when-an-operation-updates-part-of-a-value).

For a third-party or one-off model, create the same route with `writableKeyPath(get = …, set = …)`
and compose it with `then`.

## Extract one kind of sealed case

Case paths focus the whole concrete case. That makes collection extraction particularly natural:

```kotlin
@Pathable
sealed interface FeedItem {
  data object Loading : FeedItem
  data class Article(val id: String, var title: String) : FeedItem
  data class Notice(val text: String) : FeedItem
}

val articles: List<FeedItem.Article> = items.mapNotNull(FeedItemPaths.Article)
val titles: List<String> = articles.map(FeedItemPaths.ArticlePaths.title)
```

The first line is a typed filter-and-extract in one operation. `Loading` and `Notice` disappear;
the compiler knows each remaining value is `FeedItem.Article`.

For a small, read-only query, a factory needs no annotation:

```kotlin
val article = caseKeyPath<FeedItem, FeedItem.Article>()
val articles = items.mapNotNull(article)
```

## Compose case matching with generated KeyPaths

If a case has a `var` property, its generated concrete-case property path is copy-writable. Chain
it from the whole-case path to create one conditional, value-semantic update route:

```kotlin
val articleTitle = FeedItemPaths.Article
  .then(FeedItemPaths.ArticlePaths.title)
// WritableCaseKeyPath<FeedItem, String>

val normalized = items.map { item ->
  articleTitle.modify(item) { title -> title.trim() }
}
```

Only `Article` values change. A `Loading` or `Notice` value is returned exactly as it was. The
update of an `Article` is also copy-based: its original `title` is not mutated in place.

This is the important distinction between these two generated paths:

| Path | Root | Role |
| --- | --- | --- |
| `FeedItemPaths.Article` | `FeedItem` | conditionally focus the whole `Article` case |
| `FeedItemPaths.ArticlePaths.title` | `FeedItem.Article` | replace `title` on an already-known `Article` |
| `articleTitle` | `FeedItem` | conditionally replace `title` through the case |

## Follow nested sealed cases

Generated direct payload paths are especially useful when one sealed case wraps the next action or
event. Each unary `action` path extracts and reconstructs its wrapper, so even `val` action
properties can form one deeply nested route:

```kotlin
@Pathable
sealed interface AppEvent {
  data object Dismissed : AppEvent
  data class Profile(val action: ProfileAction) : AppEvent
}

@Pathable
sealed interface ProfileAction {
  data object Back : ProfileAction
  data class Editor(val action: EditorAction) : ProfileAction
}

@Pathable
sealed interface EditorAction {
  data class Rename(val text: String) : EditorAction
  data object Save : EditorAction
}

val renameText = AppEventPaths.Profile.action
  .then(ProfileActionPaths.Editor.action)
  .then(EditorActionPaths.Rename.text)
// WritableCaseKeyPath<AppEvent, String>
```

`renameText` matches only `Profile(Editor(Rename(...)))`. It can collect text from many events or
conditionally replace it, recreating every wrapper on the successful route back out:

```kotlin
val names = events.mapNotNull(renameText)
val revised = renameText.embed(
  AppEvent.Profile(ProfileAction.Editor(EditorAction.Rename("Blob"))),
  "Jr.",
)
// AppEvent.Profile(ProfileAction.Editor(EditorAction.Rename("Jr.")))
```

If a wrapper also has metadata—say `Profile(val id: String, val action: ProfileAction)`—the
current generated convenience for that two-value payload is `AppEventPaths.Profile.payload` as a
`Pair`. For a named nested action route, make the one explicit copying boundary and continue
composing:

```kotlin
val profileAction = AppEventPaths.Profile.then(
  get = AppEvent.Profile::action,
  set = { profile, action -> profile.copy(action = action) },
)

val renameText = profileAction
  .then(ProfileActionPaths.Editor.action)
  .then(EditorActionPaths.Rename.text)
```

This is a promising place for future KSP ergonomics: generating an individual reconstructing case
path for every primary-constructor property would remove that explicit boundary for multi-property
cases too.

## Cross a property, then a case, then another property

Composition can mix writable KeyPaths and writable CaseKeyPaths. This expresses a nested route
without a handwritten chain of casts and `when` expressions:

```kotlin
@Pathable
sealed interface Destination {
  data object Home : Destination
  data class Editor(var draft: String) : Destination
}

@Pathable
sealed interface Screen {
  data class Detail(var destination: Destination) : Screen
  data object Settings : Screen
}

val draft = ScreenPaths.Detail
  .then(ScreenPaths.DetailPaths.destination)
  .then(DestinationPaths.Editor)
  .then(DestinationPaths.EditorPaths.draft)
// WritableCaseKeyPath<Screen, String>

val saved: Screen = draft.modify(screen) { it.trim() }
```

`saved` only changes when `screen` is `Detail(Editor(...))`. Every other route—`Settings`,
`Detail(Home)`, or a future destination case—passes through unchanged. Each successful write
reconstructs the data-class values on the way back out.

## Classify a sealed value without a `when`

Generated sealed catalogs expose `caseOf` for algorithms that only need the case identity:

```kotlin
val counts = items
  .groupingBy { FeedItemPaths.caseOf(it) }
  .eachCount()

// Map<PartialCaseKeyPath<FeedItem>, Int>
```

The resulting paths are type-erased because the cases have different focused types. They are
still stable, comparable values, so they work as map keys or can be compared with
`FeedItemPaths.Article`, `FeedItemPaths.Loading`, and so on.

## Count pending, empty, and populated responses separately

This recipe is useful when a sealed case carries an optional field and a report needs to distinguish
all three states—not the usual “pluck the non-null values” behavior:

```kotlin
@Pathable
sealed interface Response {
  data object Pending : Response
  data class Note(val text: String?) : Response
}

val noteText = ResponsePaths.Note.text

val counts = responses
  .groupingBy { response ->
    when (val result = response.match(noteText)) {
      CaseMatch.NoMatch -> "pending"
      is CaseMatch.Matched -> if (result.value == null) "empty" else "populated"
    }
  }
  .eachCount()
```

`mapNotNull(noteText)` would be perfect if the only question were “which notes have text?” Here it
would hide both `Pending` and `Note(null)`, so `match` is the right ingredient for the recipe.
