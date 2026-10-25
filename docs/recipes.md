# KeyPath and CaseKeyPath recipes

KPaths provides two related values for two different jobs:

- A `KeyPath` reads a value from every root; a `WritableKeyPath` can also return a revised root.
- A `CaseKeyPath` matches one sealed case, extracts its associated value, and embeds that value in a newly constructed root.

These examples use those concrete types throughout and do not require a particular UI or state-management framework. For factory selection and capability rules, read [KeyPaths](key-paths.md) and [CaseKeyPaths](case-paths.md) first; this page concentrates on combinations you can reuse.

## Pass a KeyPath to Kotlin collection functions

`KeyPath<Root, Value>` implements `(Root) -> Value`, so a Kotlin collection function can receive it directly:

```kotlin
data class Book(
  val title: String,
  val author: String,
  val finished: Boolean,
)

val title: KeyPath<Book, String> = keyPath(Book::title)
val author: KeyPath<Book, String> = keyPath(Book::author)
val finished: KeyPath<Book, Boolean> = keyPath(Book::finished)

val titles = books.map(title)
val completedTitles = books.filter(finished).map(title)
val byAuthor = books.groupBy(author)
val alphabetical = books.sortedBy(title)
```

This is Kotlin’s equivalent of using a Swift key path as a function, such as `books.filter(\.finished).map(\.title)`. Unlike an anonymous lambda, `title` is still a comparable `KeyPath<Book, String>` that another API can store or compose.

## Compose a WritableKeyPath for a nested immutable update

Generated `WritableKeyPath`s make a nested data-class update reusable instead of repeatedly nesting `copy` calls:

```kotlin
@Pathable data class Address(var city: String) { companion object }
@Pathable data class Profile(var address: Address) { companion object }
@Pathable data class Member(val id: String, var profile: Profile) { companion object }

val memberCity: WritableKeyPath<Member, String> = Member.Paths.profile
  .then(Profile.Paths.address)
  .then(Address.Paths.city)

val moved = members.map { member ->
  member.setting(memberCity, "Toronto")
}
```

Each generated `var` `WritableKeyPath` is copy-based because its root is a data class. `moved` contains new `Member` values; the original values remain unchanged. For a third-party model, make the same kind of `WritableKeyPath` with `writableKeyPath(get = …, set = …)`.

## Extract one case’s payload

A generated unary `CaseKeyPath` is a reusable filter-and-project operation:

```kotlin
@Pathable
sealed interface FeedItem {
  data object Loading : FeedItem
  data class Notice(val text: String) : FeedItem
  data class Article(val id: String, val title: String) : FeedItem
}

val noticeText: CaseKeyPath<FeedItem, String> = FeedItemPaths.Notice.text

val notices: List<String> = items.mapNotNull(noticeText)
```

`noticeText` extracts text only from `Notice`; its callable view returns `null` for `Loading` and `Article`. `noticeText.embed("Maintenance tonight")` independently constructs `FeedItem.Notice("Maintenance tonight")`.

For an external hierarchy, the explicit factory creates the same kind of CaseKeyPath without reflection:

```kotlin
import example.FeedItem.Notice

val noticeText: CaseKeyPath<FeedItem, String> = caseKeyPath(
  extract = FeedItem.Notice::text,
  embed = ::Notice,
)
```

The nested-case import permits the concise constructor reference. The full
[explicit-factory section](case-paths.md#explicit-payload-factories) covers `Pair` and `Triple`
payloads as well.

## Bind the other values in a multi-value case

A `CaseKeyPath` must be able to construct the complete case when it embeds. For a generated two- or three-property data case, KSP provides a named `…For` CaseKeyPath factory that binds the other constructor values:

```kotlin
val articleTitle: CaseKeyPath<FeedItem, String> =
  FeedItemPaths.Article.titleFor(id = "42")

articleTitle(FeedItem.Article("42", "Original")) // "Original"
articleTitle(FeedItem.Article("7", "Other"))    // null

articleTitle.embed("Revised")
// FeedItem.Article(id = "42", title = "Revised")
```

The bound `id` belongs to this `CaseKeyPath`’s identity, so `titleFor("42")` and `titleFor("7")` are different CaseKeyPaths. When all fields should be focused together, `FeedItemPaths.Article.payload` is a `CaseKeyPath<FeedItem, Pair<String, String>>`.

## Compose nested CaseKeyPaths

Unary case payloads compose through multiple sealed hierarchies. Embedding the final value constructs every enclosing case:

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

val renameText: CaseKeyPath<AppEvent, String> = AppEventPaths.Profile.action
  .then(ProfileActionPaths.Editor.action)
  .then(EditorActionPaths.Rename.text)

val names = events.mapNotNull(renameText)
val revised = renameText.embed("Blob Jr.")
// AppEvent.Profile(ProfileAction.Editor(EditorAction.Rename("Blob Jr.")))
```

This is deliberately not a `WritableKeyPath`: it constructs a new nested `AppEvent` rather than modifying an existing one. Use a `WritableKeyPath` when the operation must replace one field inside an existing product value.

## Preserve a matched null with CaseMatch

Calling a `CaseKeyPath` or `extract` intentionally collapses “a different case” and “this case has a null payload” into `null`. Use `match` when those states differ:

```kotlin
@Pathable
sealed interface Status {
  data object Loading : Status
  data class Note(val text: String?) : Status
}

val noteText: CaseKeyPath<Status, String?> = StatusPaths.Note.text

when (val result = noteText.match(status)) {
  CaseMatch.NoMatch -> println("not a note")
  is CaseMatch.Matched -> println(result.value) // String?, including null
}
```

## Classify a sealed value with PartialCaseKeyPath

Generated sealed catalogs expose `caseOf` when an algorithm needs the stable identity of the matching case, but not its payload type:

```kotlin
val counts = items
  .groupingBy { FeedItemPaths.caseOf(it) }
  .eachCount()
// Map<PartialCaseKeyPath<FeedItem>, Int>
```

The `PartialCaseKeyPath` values are type-erased because the cases have different focused types, but they remain comparable and hashable. See [identity and type metadata](identity.md) for what their identity represents.

## Create indexed and keyed KeyPaths

The standard factories capture an index or key in a `KeyPath`:

```kotlin
data class Contact(val email: String)

val secondContact: KeyPath<List<Contact>, Contact> =
  keyPath(get = List<Contact>::get, at = 1)
val secondEmail: KeyPath<List<Contact>, String> =
  secondContact.then(keyPath(Contact::email))

val showTips: WritableKeyPath<Map<String, Boolean>, Boolean?> = writableKeyPath(
  get = Map<String, Boolean>::get,
  key = "showTips",
)

val enabled = showTips.set(emptyMap(), true)
val removed = showTips.set(enabled, null) // null removes the map key
```

See [KeyPaths](key-paths.md#detailed-factory-patterns) for named computed KeyPaths and explicit reference-mutable collection KeyPaths. The [API reference](api/index.md) lists the complete indexed and keyed overloads.
