package dev.kpaths.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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
    val sourceArguments = mutableListOf(TypeArgument.invariant(TypeId.string))
    val type = TypeId("example.Box", sourceArguments)
    val originalHashCode = type.hashCode()

    sourceArguments += TypeArgument.invariant(TypeId.int)

    assertEquals(listOf(TypeArgument.invariant(TypeId.string)), type.arguments)
    assertEquals(originalHashCode, type.hashCode())
  }
}
