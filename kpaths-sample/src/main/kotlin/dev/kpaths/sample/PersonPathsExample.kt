package dev.kpaths.sample

import dev.kpaths.annotations.CaseName
import dev.kpaths.annotations.Pathable
import dev.kpaths.annotations.PathName
import dev.kpaths.annotations.PathsName

@Pathable
data class Person(
  val name: String,
  val age: Int,
  var address: Address,
  val tags: List<String> = emptyList(),
  val nickname: String? = null,
) {
  companion object
}

@Pathable
data class NamedProfile(
  @PathName("displayName")
  val legalName: String,
) {
  companion object
}

@Pathable
data class Address(
  var city: String,
)

@Pathable
data class Box<T>(
  val value: T,
) {
  companion object Factory
}

@Pathable
data class NamedPathsBox<T>(
  val value: T,
) {
  companion object Paths
}

@Pathable
data class CustomFacadeBox<T>(
  val value: T,
) {
  @PathsName("KPaths")
  companion object Factory
}

@Pathable
data class CustomFacadePerson(
  val name: String,
) {
  @PathsName("KPaths")
  companion object Paths
}

@Pathable
data class Draft(
  var text: String,
)

@Pathable
data class ComputedDraft(
  var text: String,
) {
  var trimmedText: String
    get() = text.trim()
    set(value) {
      text = value.trim()
    }
}

@Pathable
data class RootTypeModel(
  val rootType: String,
)

@Pathable("CustomRecordPaths")
data class Record(
  val identifier: String,
)

@Pathable
data class PairBox<Value, value>(
  val first: Value,
  val second: value,
)

@Pathable
data class CollectionHolder(
  val immutable: List<String>,
  var mutable: MutableList<String>,
)

@Pathable
class Session(
  var token: String,
  val identifier: String,
)

@Pathable
sealed interface Connection {
  companion object {}

  data object Disconnected : Connection
  data class Connected(val host: String?) : Connection
}

@Pathable
sealed interface NamedEvent {
  companion object {}

  data object Idle : NamedEvent

  @CaseName("message")
  @PathsName("MessageContentPaths")
  data class Message(
    @PathName("messageText")
    val text: String,
  ) : NamedEvent
}

@Pathable
enum class NamedDirection {
  @CaseName("north")
  NORTH;

  companion object
}

@Pathable
sealed interface Request {
  data class Selected(val value: String) : Request
  data class Identified(val id: String) : Request
  data class Typed(val valueType: String) : Request
  data class Navigate(val id: String, val destination: String) : Request
  data class Route(val id: String, val destination: String, val replace: Boolean) : Request
  data class Nullable(val value: String?) : Request
}

@Pathable
sealed interface EditableFeedItem {
  data object Loading : EditableFeedItem
  data class Article(val id: String, var title: String) : EditableFeedItem
  data class Notice(val text: String) : EditableFeedItem
}

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

@Pathable
enum class Direction {
  NORTH,
  SOUTH,
}

@Pathable
enum class KeywordDirection {
  `when`,
}

@Pathable
data class KeywordProperty(
  val `display name`: String,
)

@Pathable
sealed interface KeywordCase {
  data class `when`(val value: String) : KeywordCase
}

@Pathable
sealed interface Loadable<out Value : Any> {
  companion object Factory

  data object Idle : Loadable<Nothing>
  data class Loaded<T : Any>(val value: T) : Loadable<T>
}

@Pathable
sealed interface ApiResult<out Value, out Failure> {
  data class Success<T>(val value: T) : ApiResult<T, Nothing>
  data class Error<E>(val error: E) : ApiResult<Nothing, E>
}
