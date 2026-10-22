package dev.kpaths.sample

import dev.kpaths.core.CaseKeyPath
import dev.kpaths.core.CaseMatch
import dev.kpaths.core.KeyPath
import dev.kpaths.core.TypeId
import dev.kpaths.core.WritableCaseKeyPath
import dev.kpaths.core.WritableKeyPath
import dev.kpaths.core.caseKeyPath
import dev.kpaths.core.caseMatches
import dev.kpaths.core.get
import dev.kpaths.core.match
import dev.kpaths.core.set
import dev.kpaths.core.then
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeneratedPathsTest {
  @Test
  fun `data-class vals generate read-only paths and vars generate copy-writable paths`() {
    val original = Person(name = "Blob", age = 100, address = Address(city = "Paris"))
    val name: KeyPath<Person, String> = PersonPaths.name
    val address: WritableKeyPath<Person, Address> = PersonPaths.address

    assertEquals("Blob", original[name])
    assertFalse((PersonPaths.name as Any) is WritableKeyPath<*, *>)
    assertEquals(original.copy(address = Address("Toronto")), address.set(original, Address("Toronto")))
  }

  @Test
  fun `generated whole case paths conditionally replace their matched cases`() {
    val connected: WritableCaseKeyPath<Connection, Connection.Connected> = ConnectionPaths.Connected
    val original: Connection = Connection.Connected(host = "example.com")

    assertEquals(Connection.Connected("example.com"), original[connected])
    assertNull(Connection.Disconnected[connected])
    assertEquals(Connection.Connected("updated.example"), connected.embed(original, Connection.Connected("updated.example")))
    assertEquals(Connection.Disconnected, connected.embed(Connection.Disconnected, Connection.Connected("updated.example")))
    assertEquals(ConnectionPaths.Connected, ConnectionPaths.caseOf(original))
  }

  @Test
  fun `generated case payload paths remain exact and root aware`() {
    val selected: WritableCaseKeyPath<Request, String> = RequestPaths.Selected.value
    val nullable: WritableCaseKeyPath<Request, String?> = RequestPaths.Nullable.value
    val selectedRoot: Request = Request.Selected("Blob")

    assertEquals("Blob", selectedRoot[selected])
    assertEquals(Request.Selected("Jr."), selected.embed(selectedRoot, "Jr."))
    assertEquals(Request.Navigate("id", "settings"), selected.embed(Request.Navigate("id", "settings"), "Ignored"))
    assertEquals(CaseMatch.Matched(null), Request.Nullable(null).match(nullable))
    assertEquals(CaseMatch.NoMatch, Request.Selected("Blob").match(nullable))
    assertTrue(Request.Nullable(null).caseMatches(nullable))
  }

  @Test
  fun `case paths compose through an explicit copy setter for a val property`() {
    val connected = ConnectionPaths.Connected
    val host = connected.then(
      get = Connection.Connected::host,
      set = { connection, value -> connection.copy(host = value) },
    )
    val original: Connection = Connection.Connected(host = "example.com")

    assertEquals("example.com", original[host])
    assertEquals(Connection.Connected("updated.example"), host.embed(original, "updated.example"))
  }

  @Test
  fun `generic whole case paths retain specialized identities`() {
    val strings = LoadablePaths<String>()
    val loaded: WritableCaseKeyPath<Loadable<String>, Loadable.Loaded<String>> = strings.Loaded
    val root: Loadable<String> = Loadable.Loaded("Blob")

    assertEquals(Loadable.Loaded("Blob"), root[loaded])
    assertEquals(Loadable.Loaded("Jr."), loaded.embed(root, Loadable.Loaded("Jr.")))
    assertEquals(TypeId.of<Loadable.Loaded<String>>(), loaded.__kpaths_valueType)
    assertEquals(loaded, caseKeyPath<Loadable<String>, Loadable.Loaded<String>>())
  }

  @Test
  fun `read-only case path factories remain useful for generic matching`() {
    val selected: CaseKeyPath<Request, Request.Selected> = caseKeyPath(
      id = RequestPaths.Selected.__kpaths_id,
      valueType = TypeId.of<Request.Selected>(),
      match = { root -> if (root is Request.Selected) CaseMatch.Matched(root) else CaseMatch.NoMatch },
    )

    assertEquals(Request.Selected("Blob"), Request.Selected("Blob")[selected])
    assertNull(Request.Navigate("id", "settings")[selected])
  }
}
