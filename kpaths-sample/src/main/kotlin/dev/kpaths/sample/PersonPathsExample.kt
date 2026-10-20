package dev.kpaths.sample

import dev.kpaths.annotations.Pathable

@Pathable
data class Person(
  val name: String,
  val age: Int,
  val address: Address,
)

@Pathable
data class Address(
  val city: String,
)

@Pathable
sealed interface Connection {
  data object Disconnected : Connection
  data class Connected(val host: String) : Connection
}
