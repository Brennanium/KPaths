package dev.kpaths.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.reflect.KProperty1

class StandardKeyPathsTest {
  @Test
  fun `identity path focuses and replaces the whole root`() {
    val original = StandardPerson("Blob")
    val replacement = StandardPerson("Blob Jr.")
    val identity = identityKeyPath<StandardPerson>()

    assertEquals(original, identity.get(original))
    assertEquals(replacement, identity.set(original, replacement))
    assertEquals(identity, identityKeyPath<StandardPerson>())
    assertEquals(PathId(TypeId.of<StandardPerson>(), emptyList()), identity.__kpaths_id)
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
    assertEquals(TypeId.of<Pair<String, Int>>(), pairPaths.first.__kpaths_id.rootType)
    assertEquals(TypeId.of<Triple<String, Int, Boolean>>(), triplePaths.third.__kpaths_id.rootType)
  }

  @Test
  fun `list paths are read-only indexed routes`() {
    val paths = ListPaths<String>()
    val second = paths.at(1)

    assertEquals(3, paths.size.get(listOf("Blob", "Jr.", "Point-Free")))
    assertEquals("Jr.", second.get(listOf("Blob", "Jr.")))
    assertEquals(second, ListPaths<String>().at(1))
    assertNotEquals(second, paths.at(0))
    assertEquals(TypeId.of<List<String>>(), second.__kpaths_id.rootType)
    assertEquals(
      PathId(TypeId.of<List<String>>(), listOf(PathSegment.Index(1))),
      second.__kpaths_id,
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
    assertEquals(TypeId.of<Array<String>>(), arrayPath.__kpaths_id.rootType)
    assertEquals(TypeId.of<MutableList<String>>(), listPath.__kpaths_id.rootType)
    assertNotEquals(TypeId.of<List<String>>(), TypeId.of<MutableList<String>>())
    assertNotEquals<Any?>(ListPaths<String>().at(1), listPath)
  }

  @Test
  fun `manual factories provide stable paths for computed and copy-based routes`() {
    val normalizedName = keyPath(
      id = PathId.custom(TypeId.of<StandardPerson>(), "test.StandardPerson.normalizedName"),
      get = { person: StandardPerson -> person.name.trim().lowercase() },
    )
    val renamed = writableKeyPath(
      id = PathId.property(TypeId.of<StandardPerson>(), "name"),
      get = StandardPerson::name,
      set = { person, name -> person.copy(name = name) },
    )
    val original = StandardPerson(" Blob ")

    assertEquals("blob", normalizedName.get(original))
    assertEquals(StandardPerson("Jr."), renamed.set(original, "Jr."))
    assertEquals(
      normalizedName,
      keyPath(
        id = PathId.custom(TypeId.of<StandardPerson>(), "test.StandardPerson.normalizedName"),
        get = { person: StandardPerson -> person.name.trim().lowercase() },
      ),
    )
    assertNotEquals(normalizedName, keyPath(StandardPerson::name))
  }

  @Test
  fun `named factories derive the root identity automatically`() {
    val normalizedName = keyPath<StandardPerson, String>(
      name = "normalizedName",
      get = { person -> person.name.trim().lowercase() },
    )
    val inferredNormalizedName = keyPath(name = "normalizedName") { person: StandardPerson ->
      person.name.trim().lowercase()
    }
    val renamed = writableKeyPath<StandardPerson, String>(
      name = "name",
      get = StandardPerson::name,
      set = { person, name -> person.copy(name = name) },
    )
    val session = StandardSession("first")
    val token = referenceWritableKeyPath<StandardSession, String>(
      name = "token",
      get = StandardSession::token,
      set = { target, value -> target.token = value },
    )

    assertEquals("blob", normalizedName.get(StandardPerson(" Blob ")))
    assertEquals(
      PathId.custom(TypeId.of<StandardPerson>(), "normalizedName"),
      normalizedName.__kpaths_id,
    )
    assertEquals(normalizedName, inferredNormalizedName)
    assertEquals(StandardPerson("Jr."), renamed.set(StandardPerson("Blob"), "Jr."))
    assertSame(session, token.set(session, "second"))
    assertEquals("second", session.token)
  }

  @Test
  fun `property-reference adapters preserve concrete generic type identities`() {
    val stringProperty: KProperty1<StandardBox<String>, String> = StandardBox<String>::value
    val intProperty: KProperty1<StandardBox<Int>, Int> = StandardBox<Int>::value
    val stringPath = keyPath(stringProperty)
    val intPath = keyPath(intProperty)
    val explicitStringPath = keyPath(
      property = stringProperty,
      id = PathId.property(TypeId.of<StandardBox<String>>(), "value"),
      valueType = TypeId.of<String>(),
    )

    assertEquals("Blob", stringPath.get(StandardBox("Blob")))
    assertEquals(42, intPath.get(StandardBox(42)))
    assertEquals(TypeId.of<StandardBox<String>>(), stringPath.__kpaths_id.rootType)
    assertNotEquals<Any?>(stringPath, intPath)
    assertEquals(stringPath, explicitStringPath)
    assertEquals(stringPath, stringProperty.asKeyPath())
  }

  @Test
  fun `mutable property references opt into in-place mutation`() {
    val session = StandardSession("first")
    val readOnly = keyPath(StandardSession::token)
    val writable = referenceWritableKeyPath(StandardSession::token)

    assertEquals("first", readOnly.get(session))
    assertSame(session, writable.set(session, "second"))
    assertEquals("second", session.token)
    assertEquals(writable, StandardSession::token.asReferenceWritableKeyPath())
  }
}

private data class StandardPerson(val name: String)
private data class StandardBox<Value>(val value: Value)
private class StandardSession(var token: String)
