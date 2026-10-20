package dev.kpaths.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PathIdTest {
  @Test
  fun `paths with the same structural route compare equal`() {
    assertEquals(
      PathId.property("dev.kpaths.CounterState", "count"),
      PathId.property("dev.kpaths.CounterState", "count"),
    )
  }

  @Test
  fun `different route segments do not compare equal`() {
    assertNotEquals(
      PathId.property("dev.kpaths.CounterState", "count"),
      PathId.case("dev.kpaths.CounterState", "count"),
    )
  }
}
