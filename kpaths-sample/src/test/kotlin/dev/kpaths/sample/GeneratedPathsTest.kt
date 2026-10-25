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
  fun `property catalog names are independently configurable`() {
    val displayName: KeyPath<NamedProfile, String> = NamedProfilePaths.displayName
    val messageText: KeyPath<NamedEvent.Message, String> = NamedEventPaths.MessageContentPaths.messageText

    assertEquals("Blob", NamedProfile("Blob")[displayName])
    assertEquals(NamedProfilePaths.displayName, NamedProfile.Paths.displayName)
    assertEquals(PathId.property(TypeId.of<NamedProfile>(), "legalName"), displayName.__kpaths_id)
    assertEquals("Hello", NamedEvent.Message("Hello")[messageText])
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
    val text: KeyPath<NamedEvent.Message, String> = NamedEventPaths.MessageContentPaths.messageText
    val north: CaseKeyPath<NamedDirection, Unit> = NamedDirectionPaths.north

    assertEquals(NamedEvent.Message("Hello"), message.embed(NamedEvent.Message("Hello")))
    assertEquals("Hello", NamedEvent.Message("Hello")[text])
    assertEquals(PathId.case(TypeId.of<NamedEvent>(), "Message"), message.__kpaths_id)
    assertTrue(NamedDirection.NORTH.caseMatches(north))
  }

  @Test
  fun `non-generic companions expose generated case catalogs`() {
    assertEquals(ConnectionPaths.Connected, Connection.Paths.Connected)
    assertEquals(NamedEventPaths.message, NamedEvent.Paths.message)
    assertEquals(
      NamedEventPaths.MessageContentPaths.messageText,
      NamedEvent.Paths.MessageContentPaths.messageText,
    )
    assertEquals(NamedDirectionPaths.north, NamedDirection.Paths.north)
    assertEquals(NamedEventPaths.allCasePaths, NamedEvent.Paths.allCasePaths)
    assertEquals(NamedEventPaths.caseOf(NamedEvent.Idle), NamedEvent.Paths.caseOf(NamedEvent.Idle))
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

  @Test
  fun `generic companions create specialized generated catalogs`() {
    val boxed: KeyPath<Box<String>, String> = Box.Paths<String>().value
    val boxedByReified: KeyPath<Box<String>, String> = Box.Paths.of<String>().value
    val boxedByClass: KeyPath<Box<String>, String> = Box.Paths.of(String::class).value
    val namedPathsByReified: KeyPath<NamedPathsBox<String>, String> = NamedPathsBox.Paths.of<String>().value
    val namedPathsByClass: KeyPath<NamedPathsBox<String>, String> = NamedPathsBox.Paths.of(String::class).value
    val customFacade: KeyPath<CustomFacadeBox<String>, String> = CustomFacadeBox.KPaths<String>().value
    val customFacadeByReified: KeyPath<CustomFacadeBox<String>, String> = CustomFacadeBox.KPaths.of<String>().value
    val customFacadeByClass: KeyPath<CustomFacadeBox<String>, String> = CustomFacadeBox.KPaths.of(String::class).value
    val loaded: CaseKeyPath<Loadable<String>, String> = Loadable.Paths<String>().Loaded.value
    val loadedByReified: CaseKeyPath<Loadable<String>, String> = Loadable.Paths.of<String>().Loaded.value
    val customPerson: KeyPath<CustomFacadePerson, String> = CustomFacadePerson.Paths.KPaths.name

    assertEquals("Blob", Box("Blob")[boxed])
    assertEquals("Blob", Box("Blob")[boxedByReified])
    assertEquals("Blob", Box("Blob")[boxedByClass])
    assertEquals("Blob", NamedPathsBox("Blob")[namedPathsByReified])
    assertEquals("Blob", NamedPathsBox("Blob")[namedPathsByClass])
    assertEquals("Blob", CustomFacadeBox("Blob")[customFacade])
    assertEquals("Blob", CustomFacadeBox("Blob")[customFacadeByReified])
    assertEquals("Blob", CustomFacadeBox("Blob")[customFacadeByClass])
    assertEquals("Hello", Loadable.Loaded("Hello")[loaded])
    assertEquals("Hello", Loadable.Loaded("Hello")[loadedByReified])
    assertEquals("Blob", CustomFacadePerson("Blob")[customPerson])
    assertEquals(BoxPaths<String>().value, boxed)
    assertEquals(LoadablePaths<String>().Loaded.value, loaded)
  }
}
