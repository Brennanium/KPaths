package dev.kpaths.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
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

    assertEquals(3, paths.size.get(listOf("Blob", "Jr.", "Revised")))
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

    assertSame(array, arrayPath.set(array, "Revised"))
    assertEquals(arrayOf("Blob", "Revised").toList(), array.toList())
    assertSame(list, listPath.set(list, "Revised"))
    assertEquals(listOf("Blob", "Revised"), list)
    assertEquals(TypeId.of<Array<String>>(), arrayPath.__kpaths_id.rootType)
    assertEquals(TypeId.of<MutableList<String>>(), listPath.__kpaths_id.rootType)
    assertNotEquals(TypeId.of<List<String>>(), TypeId.of<MutableList<String>>())
    assertNotEquals<Any?>(ListPaths<String>().at(1), listPath)
  }

  @Test
  fun `map paths replace values and use null to remove keys`() {
    val readWrite = MapPaths<String, Int>().at("score")
    val nullableValue = MapPaths<String, Int?>().at("score")
    val mutable = mutableMapOf("score" to 1)
    val mutablePath = MutableMapPaths<String, Int>().at("score")

    assertEquals(null, readWrite.get(emptyMap()))
    assertEquals(mapOf("score" to 2), readWrite.set(mapOf("score" to 1), 2))
    assertEquals(emptyMap<String, Int>(), readWrite.set(mapOf("score" to 1), null))
    assertEquals(emptyMap<String, Int?>(), nullableValue.set(mapOf("score" to null), null))
    assertSame(mutable, mutablePath.set(mutable, 2))
    assertEquals(2, mutable["score"])
    assertSame(mutable, mutablePath.set(mutable, null))
    assertEquals(false, mutable.containsKey("score"))
    assertNotEquals<Any?>(readWrite, mutablePath)
  }

  @Test
  fun `primitive array paths use Kotlin operator get and set semantics`() {
    val booleans = booleanArrayOf(false, false)
    val bytes = byteArrayOf(0, 0)
    val chars = charArrayOf('a', 'b')
    val shorts = shortArrayOf(0, 0)
    val ints = intArrayOf(0, 0)
    val longs = longArrayOf(0L, 0L)
    val floats = floatArrayOf(0F, 0F)
    val doubles = doubleArrayOf(0.0, 0.0)

    assertSame(booleans, BooleanArrayPaths.at(1).set(booleans, true))
    assertSame(bytes, ByteArrayPaths.at(1).set(bytes, 1))
    assertSame(chars, CharArrayPaths.at(1).set(chars, 'z'))
    assertSame(shorts, ShortArrayPaths.at(1).set(shorts, 1))
    assertSame(ints, IntArrayPaths.at(1).set(ints, 1))
    assertSame(longs, LongArrayPaths.at(1).set(longs, 1L))
    assertSame(floats, FloatArrayPaths.at(1).set(floats, 1F))
    assertSame(doubles, DoubleArrayPaths.at(1).set(doubles, 1.0))

    assertEquals(true, booleans[1])
    assertEquals(1, bytes[1])
    assertEquals('z', chars[1])
    assertEquals(1, shorts[1])
    assertEquals(1, ints[1])
    assertEquals(1L, longs[1])
    assertEquals(1F, floats[1])
    assertEquals(1.0, doubles[1])
    assertEquals(2, IntArrayPaths.size.get(ints))
  }

  @Test
  fun `char sequence and string paths use Kotlin UTF-16 indexing`() {
    val sequence: CharSequence = "Blob"

    assertEquals('l', CharSequencePaths.at(1).get(sequence))
    assertEquals('l', StringPaths.at(1).get("Blob"))
    assertEquals(4, CharSequencePaths.length.get(sequence))
    assertEquals(StringPaths.at(1), StringPaths.at(1))
    assertNotEquals<Any?>(CharSequencePaths.at(1), StringPaths.at(1))
  }

  @Test
  fun `indexed callable references can bind their index with at`() {
    val second: (List<String>) -> String = List<String>::get.at(1)
    val secondSetter: (IntArray, Int) -> Unit = IntArray::set.at(1)
    val integers = intArrayOf(0, 0)

    assertEquals("Jr.", second(listOf("Blob", "Jr.")))
    secondSetter(integers, 42)
    assertEquals(42, integers[1])
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

  @Test
  fun `property-reference writable factories support explicit reconstruction and copy`() {
    val name = writableKeyPath(
      get = StandardMutablePerson::name,
      set = { person, value -> person.copy(name = value) },
    )
    val normalizedName = writableKeyPath(
      property = StandardMutablePerson::normalizedName,
      copy = { person -> person.copy() },
    )
    val original = StandardMutablePerson(" Blob ")

    assertEquals(StandardMutablePerson("Jr."), name.set(original, "Jr."))
    assertEquals(StandardMutablePerson("Jr."), normalizedName.set(original, " Jr. "))
    assertEquals(StandardMutablePerson(" Blob "), original)
  }

  @Test
  fun `reference-writable paths warn for data-class roots without rejecting them`() {
    val originalHandler = KPathsRuntimeWarnings.handler
    val warnings = mutableListOf<KPathsRuntimeWarning>()
    KPathsRuntimeWarnings.handler = warnings::add
    try {
      val path = referenceWritableKeyPath(
        name = "dataClassWarning.name",
        get = StandardMutablePerson::name,
        set = { person, value -> person.name = value },
      )
      val person = StandardMutablePerson("Blob")

      assertSame(person, path.set(person, "Jr."))
      assertEquals("Jr.", person.name)
      assertEquals(1, warnings.size)
      assertContains(warnings.single().message, "data-class root")
      assertContains(warnings.single().message, "WritableKeyPath")
    } finally {
      KPathsRuntimeWarnings.handler = originalHandler
    }
  }

  @Test
  fun `null runtime-warning handler opts out before recording a data-class path`() {
    val originalHandler = KPathsRuntimeWarnings.handler
    val warnings = mutableListOf<KPathsRuntimeWarning>()
    try {
      KPathsRuntimeWarnings.handler = null
      referenceWritableKeyPath(
        name = "dataClassWarning.disabled",
        get = StandardMutablePerson::name,
        set = { person, value -> person.name = value },
      )

      KPathsRuntimeWarnings.handler = warnings::add
      referenceWritableKeyPath(
        name = "dataClassWarning.disabled",
        get = StandardMutablePerson::name,
        set = { person, value -> person.name = value },
      )

      assertEquals(1, warnings.size)
    } finally {
      KPathsRuntimeWarnings.handler = originalHandler
    }
  }
}

private data class StandardPerson(val name: String)
private data class StandardMutablePerson(var name: String) {
  var normalizedName: String
    get() = name.trim()
    set(value) {
      name = value.trim()
    }
}
private data class StandardBox<Value>(val value: Value)
private class StandardSession(var token: String)
