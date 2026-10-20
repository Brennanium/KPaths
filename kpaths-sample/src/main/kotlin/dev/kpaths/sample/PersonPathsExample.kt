package dev.kpaths.sample

import dev.kpaths.annotations.Pathable

@Pathable
data class Person(
  val name: String,
  val age: Int,
  val address: Address,
  val tags: List<String> = emptyList(),
  val nickname: String? = null,
)

@Pathable
data class Address(
  val city: String,
)

@Pathable
data class Box<T>(
  val value: T,
)

@Pathable
sealed interface Connection {
  data object Disconnected : Connection
  data class Connected(val host: String) : Connection
}
