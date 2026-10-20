package dev.kpaths.sample

import dev.kpaths.annotations.Pathable

@Pathable
data class Person(
  val name: String,
  val age: Int,
)

@Pathable
sealed interface Connection {
  data object Disconnected : Connection
  data class Connected(val host: String) : Connection
}
