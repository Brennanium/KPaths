package dev.kpaths.sample

import dev.kpaths.annotations.Pathable

@Pathable
data class Person(
  val name: String,
  val age: Int,
  var address: Address,
  val tags: List<String> = emptyList(),
  val nickname: String? = null,
)

@Pathable
data class Address(
  var city: String,
)

@Pathable
data class Box<T>(
  val value: T,
)

@Pathable
data class Draft(
  var text: String,
)

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
class Session(
  var token: String,
  val identifier: String,
)

@Pathable
sealed interface Connection {
  data object Disconnected : Connection
  data class Connected(val host: String?) : Connection
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
sealed interface Loadable<out Value> {
  data object Idle : Loadable<Nothing>
  data class Loaded<T>(val value: T) : Loadable<T>
}

@Pathable
sealed interface ApiResult<out Value, out Failure> {
  data class Success<T>(val value: T) : ApiResult<T, Nothing>
  data class Error<E>(val error: E) : ApiResult<Nothing, E>
}
