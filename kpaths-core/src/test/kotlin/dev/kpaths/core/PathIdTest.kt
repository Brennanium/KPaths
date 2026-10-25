package dev.kpaths.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathIdTest {
  @Test
  fun `paths with the same structural route compare equal`() {
    assertEquals(
      PathId.property(TypeId("dev.kpaths.CounterState"), "count"),
      PathId.property(TypeId("dev.kpaths.CounterState"), "count"),
    )
  }

  @Test
  fun `different route segments do not compare equal`() {
    assertNotEquals(
      PathId.property(TypeId("dev.kpaths.CounterState"), "count"),
      PathId.case(TypeId("dev.kpaths.CounterState"), "count"),
    )
  }

  @Test
  fun `identity copies input segments to remain hash stable`() {
    val sourceSegments = mutableListOf(PathSegment.Property("address"))
    val identity = PathId(TypeId("example.Person"), sourceSegments)
    val originalHashCode = identity.hashCode()

    sourceSegments += PathSegment.Property("city")

    assertEquals(listOf(PathSegment.Property("address")), identity.segments)
    assertEquals(originalHashCode, identity.hashCode())
  }

  @Test
  fun `type tokens copy input arguments to remain hash stable`() {
    val sourceArguments = mutableListOf(TypeArgument.invariant(TypeId.of<String>()))
    val type = TypeId("example.Box", sourceArguments)
    val originalHashCode = type.hashCode()

    sourceArguments += TypeArgument.invariant(TypeId.of<Int>())

    assertEquals(listOf(TypeArgument.invariant(TypeId.of<String>())), type.arguments)
    assertEquals(originalHashCode, type.hashCode())
  }

  @Test
  fun `paths expose structural ancestor relationships without exposing PathIds`() {
    val address = keyPath(RelationshipProfile::address)
    val city = address.then(keyPath(RelationshipAddress::city))
    val nickname = keyPath(RelationshipProfile::nickname)
    val standaloneCity = keyPath(RelationshipAddress::city)

    assertTrue(address.isPrefixOf(city))
    assertTrue(city.isDescendantOf(address))
    assertTrue(address.isPrefixOf(address))
    assertFalse(city.isPrefixOf(address))
    assertFalse(address.isPrefixOf(nickname))
    assertFalse(address.isPrefixOf(standaloneCity))

    assertEquals(listOf(PathSegment.Property("city")), city.descendantSegments(after = address))
    assertEquals(emptyList(), address.descendantSegments(after = address))
    assertNull(address.descendantSegments(after = city))
    assertNull(city.descendantSegments(after = nickname))
  }
}

private data class RelationshipProfile(
  val address: RelationshipAddress,
  val nickname: String,
)

private data class RelationshipAddress(val city: String)
