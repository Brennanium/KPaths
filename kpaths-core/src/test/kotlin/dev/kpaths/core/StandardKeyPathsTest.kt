package dev.kpaths.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class StandardKeyPathsTest {
  @Test
  fun `identity path focuses and replaces the whole root`() {
    val original = StandardPerson("Blob")
    val replacement = StandardPerson("Blob Jr.")
    val identity = identityKeyPath<StandardPerson>()

    assertEquals(original, identity.get(original))
    assertEquals(replacement, identity.set(original, replacement))
    assertEquals(identity, identityKeyPath<StandardPerson>())
    assertEquals(PathId(TypeId.of<StandardPerson>(), emptyList()), identity.id)
  }

  @Test
  fun `string pair and triple paths are stable read paths`() {
    val pairPaths = PairPaths<String, Int>()
    val triplePaths = TriplePaths<String, Int, Boolean>()

    assertEquals(4, StringPaths.length.get("Blob"))
    assertEquals("Blob", pairPaths.first.get("Blob" to 42))
    assertEquals(42, pairPaths.second.get("Blob" to 42))
    assertEquals("Blob", triplePaths.first.get(Triple("Blob", 42, true)))
    assertEquals(42, triplePaths.second.get(Triple("Blob", 42, true)))
    assertEquals(true, triplePaths.third.get(Triple("Blob", 42, true)))
    assertEquals(TypeId.of<Pair<String, Int>>(), pairPaths.first.id.rootType)
    assertEquals(TypeId.of<Triple<String, Int, Boolean>>(), triplePaths.third.id.rootType)
  }

  @Test
  fun `list paths are read-only indexed routes`() {
    val paths = ListPaths<String>()
    val second = paths.at(1)

    assertEquals(3, paths.size.get(listOf("Blob", "Jr.", "Point-Free")))
    assertEquals("Jr.", second.get(listOf("Blob", "Jr.")))
    assertEquals(second, ListPaths<String>().at(1))
    assertNotEquals(second, paths.at(0))
    assertEquals(TypeId.of<List<String>>(), second.id.rootType)
    assertEquals(
      PathId(TypeId.of<List<String>>(), listOf(PathSegment.Index(1))),
      second.id,
    )
  }

  @Test
  fun `array and mutable list index paths mutate their existing roots`() {
    val array = arrayOf("Blob", "Jr.")
    val list = mutableListOf("Blob", "Jr.")
    val arrayPath: ReferenceWritableKeyPath<Array<String>, String> = ArrayPaths<String>().at(1)
    val listPath: ReferenceWritableKeyPath<MutableList<String>, String> = MutableListPaths<String>().at(1)

    assertSame(array, arrayPath.set(array, "Point-Free"))
    assertEquals(arrayOf("Blob", "Point-Free").toList(), array.toList())
    assertSame(list, listPath.set(list, "Point-Free"))
    assertEquals(listOf("Blob", "Point-Free"), list)
    assertEquals(TypeId.of<Array<String>>(), arrayPath.id.rootType)
    assertEquals(TypeId.of<MutableList<String>>(), listPath.id.rootType)
    assertNotEquals(TypeId.of<List<String>>(), TypeId.of<MutableList<String>>())
    assertNotEquals<Any?>(ListPaths<String>().at(1), listPath)
  }
}

private data class StandardPerson(val name: String)
