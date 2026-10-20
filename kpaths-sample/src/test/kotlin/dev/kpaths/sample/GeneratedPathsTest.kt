package dev.kpaths.sample

import kotlin.test.Test
import kotlin.test.assertEquals
import dev.kpaths.core.WritableKeyPathBase

class GeneratedPathsTest {
  @Test
  fun `KSP generates immutable writable key paths for data class properties`() {
    val original = Person(name = "Blob", age = 100)

    assertEquals("Blob", PersonPaths.name.get(original))
    assertEquals(Person(name = "Blob Jr.", age = 100), PersonPaths.name.set(original, "Blob Jr."))
    assertEquals(original, Person(name = "Blob Jr.", age = 100).let { PersonPaths.name.set(it, "Blob") })
  }

  @Test
  fun `generated key paths compare by structural identity`() {
    assertEquals<Any?>(PersonPaths.name, EquivalentNamePath)
    assertEquals(PersonPaths.name.hashCode(), EquivalentNamePath.hashCode())
  }

  @Test
  fun `KSP still generates a namespace for case-path types`() {
    assertEquals("dev.kpaths.sample.Connection", ConnectionPaths.sourceType)
  }
}

private object EquivalentNamePath : WritableKeyPathBase<Person, String>() {
  override val id = dev.kpaths.core.PathId.property("dev.kpaths.sample.Person", "name")
  override val valueType = "kotlin.String"

  override fun get(root: Person): String = root.name

  override fun set(root: Person, value: String): Person = root.copy(name = value)
}
