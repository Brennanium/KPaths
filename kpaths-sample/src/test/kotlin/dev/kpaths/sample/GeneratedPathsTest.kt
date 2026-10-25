package dev.kpaths.sample

import dev.kpaths.core.CaseKeyPath
import dev.kpaths.core.CaseMatch
import dev.kpaths.core.KeyPath
import dev.kpaths.core.PathId
import dev.kpaths.core.TypeId
import dev.kpaths.core.WritableKeyPath
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
  fun `existing companions expose the canonical generated paths through Paths`() {
    assertEquals(PersonPaths.name, Person.Paths.name)
    assertEquals(PersonPaths.address, Person.Paths.address)
  }

  @Test
  fun `generated case paths independently embed complete cases and payloads`() {
    val connected: CaseKeyPath<Connection, Connection.Connected> = ConnectionPaths.Connected
    val selected: CaseKeyPath<Request, String> = RequestPaths.Selected.value
    val nullable: CaseKeyPath<Request, String?> = RequestPaths.Nullable.value

    assertEquals(Connection.Connected("updated.example"), connected.embed(Connection.Connected("updated.example")))
    assertEquals(Request.Selected("Jr."), selected.embed("Jr."))
    assertEquals(CaseMatch.Matched(null), Request.Nullable(null).match(nullable))
    assertTrue(Request.Nullable(null).caseMatches(nullable))
    assertNull(Request.Selected("Blob")[nullable])
  }

  @Test
  fun `case and nested property catalog names are independently configurable`() {
    val message: CaseKeyPath<NamedEvent, NamedEvent.Message> = NamedEventPaths.message
    val text: KeyPath<NamedEvent.Message, String> = NamedEventPaths.MessageContentPaths.text
    val north: CaseKeyPath<NamedDirection, Unit> = NamedDirectionPaths.north

    assertEquals(NamedEvent.Message("Hello"), message.embed(NamedEvent.Message("Hello")))
    assertEquals("Hello", NamedEvent.Message("Hello")[text])
    assertEquals(PathId.case(TypeId.of<NamedEvent>(), "Message"), message.__kpaths_id)
    assertTrue(NamedDirection.NORTH.caseMatches(north))
  }

  @Test
  fun `generated multi-payload cases provide named context-bound routes`() {
    val destination = RequestPaths.Navigate.destinationFor(id = "settings")
    val replace = RequestPaths.Route.replaceFor(id = "settings", destination = "privacy")

    assertEquals(Request.Navigate("settings", "privacy"), destination.embed("privacy"))
    assertEquals("privacy", Request.Navigate("settings", "privacy")[destination])
    assertNull(Request.Navigate("profile", "privacy")[destination])
    assertEquals(Request.Route("settings", "privacy", true), replace.embed(true))
    assertNull(Request.Route("settings", "profile", true)[replace])
  }

  @Test
  fun `generated case paths compose into independently embeddable nested routes`() {
    val renameText = AppEventPaths.Profile.action
      .then(ProfileActionPaths.Editor.action)
      .then(EditorActionPaths.Rename.text)
    val original: AppEvent = AppEvent.Profile(ProfileAction.Editor(EditorAction.Rename("Blob")))

    assertEquals("Blob", original[renameText])
    assertEquals(
      AppEvent.Profile(ProfileAction.Editor(EditorAction.Rename("Jr."))),
      renameText.embed("Jr."),
    )
    assertNull(AppEvent.Dismissed[renameText])
  }

  @Test
  fun `generic case paths retain specialized identities`() {
    val strings = LoadablePaths<String>()
    val loaded: CaseKeyPath<Loadable<String>, Loadable.Loaded<String>> = strings.Loaded

    assertEquals(Loadable.Loaded("Jr."), loaded.embed(Loadable.Loaded("Jr.")))
    assertEquals(TypeId.of<Loadable.Loaded<String>>(), loaded.__kpaths_valueType)
  }
}
