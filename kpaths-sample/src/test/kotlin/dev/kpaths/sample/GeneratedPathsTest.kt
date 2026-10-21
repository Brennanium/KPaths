package dev.kpaths.sample

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import dev.kpaths.core.CaseKeyPath
import dev.kpaths.core.KeyPath
import dev.kpaths.core.ReferenceWritableKeyPath
import dev.kpaths.core.TypeArgument
import dev.kpaths.core.TypeId
import dev.kpaths.core.WritableKeyPathBase
import dev.kpaths.core.get
import dev.kpaths.core.modifying
import dev.kpaths.core.set
import dev.kpaths.core.setting
import dev.kpaths.core.then

class GeneratedPathsTest {
  @Test
  fun `KSP generates immutable writable key paths for data class properties`() {
    val original = Person(name = "Blob", age = 100, address = Address(city = "Paris"))

    assertEquals("Blob", PersonPaths.name.get(original))
    assertEquals(
      Person(name = "Blob Jr.", age = 100, address = Address(city = "Paris")),
      PersonPaths.name.set(original, "Blob Jr."),
    )
    assertEquals(
      original,
      Person(name = "Blob Jr.", age = 100, address = Address(city = "Paris"))
        .let { PersonPaths.name.set(it, "Blob") },
    )
  }

  @Test
  fun `generated writable paths compose into a writable path`() {
    val city = PersonPaths.address.then(AddressPaths.city)
    val original = Person(name = "Blob", age = 100, address = Address(city = "Paris"))

    assertEquals("Paris", city.get(original))
    assertEquals(
      Person(name = "Blob", age = 100, address = Address(city = "Toronto")),
      city.set(original, "Toronto"),
    )
    assertEquals(city, PersonPaths.address.then(AddressPaths.city))
    assertEquals(
      dev.kpaths.core.PathId(
        rootType = TypeId("dev.kpaths.sample.Person"),
        segments = listOf(
          dev.kpaths.core.PathSegment.Property("address"),
          dev.kpaths.core.PathSegment.Property("city"),
        ),
      ),
      city.id,
    )
  }

  @Test
  fun `generated key paths compare by structural identity`() {
    assertEquals<Any?>(PersonPaths.name, EquivalentNamePath)
    assertEquals(PersonPaths.name.hashCode(), EquivalentNamePath.hashCode())
  }

  @Test
  fun `generic path factories retain concrete type arguments in their identities`() {
    val integerValue = BoxPaths<Int>().value
    val stringValue = BoxPaths<String>().value
    val nullableStringsValue = BoxPaths<List<String?>>().value
    val classTokenValue = BoxPaths.of(Int::class).value
    val explicitTokenValue = BoxPaths.of<Int>(TypeId.of<Int>()).value

    assertEquals(1, integerValue.get(Box(1)))
    assertEquals("one", stringValue.get(Box("one")))
    assertEquals(listOf("one", null), nullableStringsValue.get(Box(listOf("one", null))))
    assertEquals(integerValue, classTokenValue)
    assertEquals(integerValue, explicitTokenValue)
    assertEquals(
      TypeId(
        "dev.kpaths.sample.Box",
        listOf(TypeArgument.invariant(TypeId.of<Int>())),
      ),
      integerValue.id.rootType,
    )
    assertNotEquals(integerValue.id, stringValue.id)
    assertEquals(
      TypeId(
        "dev.kpaths.sample.Box",
        listOf(TypeArgument.invariant(TypeId.of<List<String?>>())),
      ),
      nullableStringsValue.id.rootType,
    )
  }

  @Test
  fun `generated tokens preserve nested generic arguments and nullability`() {
    assertEquals(TypeId.of<List<String>>(), PersonPaths.tags.valueType)
    assertEquals(TypeId.of<String?>(), PersonPaths.nickname.valueType)
  }

  @Test
  fun `data-class writable paths copy even when the property is mutable`() {
    val original = Draft(text = "first")

    assertEquals("first", original[DraftPaths.text])
    assertEquals("first", original.get(keyPath = DraftPaths.text))

    val updated = original.set(keyPath = DraftPaths.text, value = "second")
    val modified = updated.modifying(DraftPaths.text) { it.uppercase() }
    val reset = modified.setting(DraftPaths.text, "first")

    assertEquals("first", original.text)
    assertEquals("second", updated.text)
    assertEquals("SECOND", modified.text)
    assertEquals("first", reset.text)
  }

  @Test
  fun `plain-class mutable properties generate reference-writable paths`() {
    val token: ReferenceWritableKeyPath<Session, String> = SessionPaths.token
    val session = Session(token = "first", identifier = "session-1")

    val returned = token.set(session, "second")

    assertSame(session, returned)
    assertEquals("second", session.token)

    session[SessionPaths.token] = "third"
    assertEquals("third", session.token)
    assertSame(session, session.set(keyPath = SessionPaths.token, value = "fourth"))
    assertEquals("fourth", session.token)
  }

  @Test
  fun `plain-class immutable properties generate read-only paths`() {
    val identifier: KeyPath<Session, String> = SessionPaths.identifier

    assertEquals("session-1", identifier.get(Session(token = "first", identifier = "session-1")))
  }

  @Test
  fun `KSP generates concrete sealed case paths and nested case-property namespaces`() {
    val connected: CaseKeyPath<Connection, Connection.Connected> = ConnectionPaths.connected
    val connectedWithNullHost = Connection.Connected(host = null)

    assertEquals(TypeId("dev.kpaths.sample.Connection"), ConnectionPaths.rootType)
    assertEquals(connectedWithNullHost, connectedWithNullHost[ConnectionPaths.connected])
    assertEquals(
      connectedWithNullHost,
      connectedWithNullHost.get(caseKeyPath = ConnectionPaths.connected),
    )
    assertNull(Connection.Disconnected[ConnectionPaths.connected])
    assertEquals(connectedWithNullHost, connected.embed(connectedWithNullHost))
    assertEquals(Connection.Disconnected, Connection.Disconnected[ConnectionPaths.disconnected])
    assertEquals(
      Connection.Disconnected,
      connected.replaceIfMatching(Connection.Disconnected, connectedWithNullHost),
    )
    assertEquals(
      Connection.Connected(host = "updated.example"),
      connected.modify(connectedWithNullHost) { it.copy(host = "updated.example") },
    )
    assertEquals(
      dev.kpaths.core.PathId.case(TypeId("dev.kpaths.sample.Connection"), "Connected"),
      ConnectionPaths.connected.id,
    )
    assertNull(
      connectedWithNullHost[ConnectionPaths.connected]
        ?.get(keyPath = ConnectionPaths.ConnectedPaths.host),
    )
    assertEquals(
      Connection.Connected(host = "example.com"),
      connectedWithNullHost[ConnectionPaths.connected]
        ?.setting(ConnectionPaths.ConnectedPaths.host, "example.com"),
    )
  }

  @Test
  fun `KSP generates enum case paths with Unit focuses`() {
    val north: CaseKeyPath<Direction, Unit> = DirectionPaths.north

    assertEquals(Unit, Direction.NORTH[DirectionPaths.north])
    assertNull(Direction.SOUTH[DirectionPaths.north])
    assertEquals(Direction.NORTH, north.embed(Unit))
    assertTrue(north.matches(Direction.NORTH))
    assertFalse(north.matches(Direction.SOUTH))
    assertEquals(
      dev.kpaths.core.PathId.case(TypeId("dev.kpaths.sample.Direction"), "NORTH"),
      DirectionPaths.north.id,
    )
    assertEquals(TypeId.of<Unit>(), DirectionPaths.north.valueType)
  }
}

private object EquivalentNamePath : WritableKeyPathBase<Person, String>() {
  override val id = dev.kpaths.core.PathId.property(TypeId("dev.kpaths.sample.Person"), "name")
  override val valueType = TypeId.of<String>()

  override fun get(root: Person): String = root.name

  override fun set(root: Person, value: String): Person = root.copy(name = value)
}
