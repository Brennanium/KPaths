package dev.kpaths.consumer

import dev.kpaths.sample.Paths
import dev.kpaths.sample.Person
import dev.kpaths.sample.PersonPaths
import kotlin.test.Test
import kotlin.test.assertEquals

class CompanionPathsTest {
  @Test
  fun `the companion extension is importable from another package`() {
    assertEquals(PersonPaths.name, Person.Paths.name)
  }
}
