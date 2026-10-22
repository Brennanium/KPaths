package dev.kpaths.sample

import dev.kpaths.sample.Request.Navigate as NavigateCase
import dev.kpaths.sample.Request.Nullable as NullableCase
import dev.kpaths.sample.Request.Route as RouteCase
import dev.kpaths.sample.Request.Selected as SelectedCase
import dev.kpaths.sample.Loadable.Loaded as LoadedCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import dev.kpaths.core.CaseKeyPath
import dev.kpaths.core.CaseMatch
import dev.kpaths.core.CasePath
import dev.kpaths.core.KeyPath
import dev.kpaths.core.ReferenceWritableKeyPath
import dev.kpaths.core.TypeArgument
import dev.kpaths.core.TypeId
import dev.kpaths.core.WritableKeyPath
import dev.kpaths.core.KeyPathBase
import dev.kpaths.core.get
import dev.kpaths.core.caseKeyPath
import dev.kpaths.core.casePath
import dev.kpaths.core.caseMatches
import dev.kpaths.core.keyPath
import dev.kpaths.core.getOrNull
import dev.kpaths.core.matching
import dev.kpaths.core.modifying
import dev.kpaths.core.set
import dev.kpaths.core.setting
import dev.kpaths.core.then

class GeneratedPathsTest {
  @Test
  fun `data-class vals generate read-only paths and vars generate copy-writable paths`() {
    val original = Person(name = "Blob", age = 100, address = Address(city = "Paris"))
    val name: KeyPath<Person, String> = PersonPaths.name
    val address: WritableKeyPath<Person, Address> = PersonPaths.address

    assertEquals("Blob", name.get(original))
    assertFalse((PersonPaths.name as Any) is WritableKeyPath<*, *>)
    assertEquals(
      Person(name = "Blob", age = 100, address = Address(city = "Toronto")),
      address.set(original, Address(city = "Toronto")),
    )
    assertEquals(
      original,
      address.set(Person(name = "Blob", age = 100, address = Address(city = "Toronto")), Address(city = "Paris")),
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
      city.__kpaths_id,
    )
  }

  @Test
  fun `generated key paths compare by structural identity`() {
    assertEquals<Any?>(PersonPaths.name, EquivalentNamePath)
    assertEquals(PersonPaths.name.hashCode(), EquivalentNamePath.hashCode())
    assertEquals(PersonPaths.name, keyPath(Person::name))
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
      integerValue.__kpaths_id.rootType,
    )
    assertNotEquals(integerValue.__kpaths_id, stringValue.__kpaths_id)
    assertEquals(
      TypeId(
        "dev.kpaths.sample.Box",
        listOf(TypeArgument.invariant(TypeId.of<List<String?>>())),
      ),
      nullableStringsValue.__kpaths_id.rootType,
    )
  }

  @Test
  fun `generated tokens preserve nested generic arguments and nullability`() {
    assertEquals(TypeId.of<List<String>>(), PersonPaths.tags.__kpaths_valueType)
    assertEquals(TypeId.of<String?>(), PersonPaths.nickname.__kpaths_valueType)
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
  fun `data-class computed vars generate copy-based writable paths`() {
    val original = ComputedDraft(" first ")
    val trimmedText: WritableKeyPath<ComputedDraft, String> = ComputedDraftPaths.trimmedText

    assertEquals("first", trimmedText.get(original))
    assertEquals(ComputedDraft("second"), trimmedText.set(original, " second "))
    assertEquals(ComputedDraft(" first "), original)
  }

  @Test
  fun `model properties may be named rootType`() {
    val original = RootTypeModel(rootType = "document")
    val rootType: KeyPath<RootTypeModel, String> = RootTypeModelPaths.rootType

    assertEquals("document", original[rootType])
    assertFalse((RootTypeModelPaths.rootType as Any) is WritableKeyPath<*, *>)
  }

  @Test
  fun `Pathable may choose a custom generated namespace`() {
    val record = Record(identifier = "record-1")

    assertEquals("record-1", record[CustomRecordPaths.identifier])
  }

  @Test
  fun `generic type parameters that differ by capitalization remain distinct`() {
    val paths = PairBoxPaths<String, Int>()
    val value = PairBox(first = "Blob", second = 42)

    assertEquals("Blob", value[paths.first])
    assertEquals(42, value[paths.second])
  }

  @Test
  fun `generated paths preserve mutable collection type identities`() {
    assertEquals(TypeId.of<List<String>>(), CollectionHolderPaths.immutable.__kpaths_valueType)
    assertEquals(TypeId.of<MutableList<String>>(), CollectionHolderPaths.mutable.__kpaths_valueType)
    assertNotEquals(CollectionHolderPaths.immutable.__kpaths_valueType, CollectionHolderPaths.mutable.__kpaths_valueType)
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
    val connected: CaseKeyPath<Connection, Connection.Connected> = ConnectionPaths.Connected
    val connectedWithNullHost = Connection.Connected(host = null)

    assertEquals(connectedWithNullHost, connectedWithNullHost[ConnectionPaths.Connected])
    assertEquals(
      connectedWithNullHost,
      connectedWithNullHost.get(caseKeyPath = ConnectionPaths.Connected),
    )
    assertNull(Connection.Disconnected[ConnectionPaths.Connected])
    assertEquals(connectedWithNullHost, connected.embed(connectedWithNullHost))
    assertEquals(Connection.Disconnected, Connection.Disconnected[ConnectionPaths.Disconnected])
    assertEquals(
      ConnectionPaths.Connected,
      ConnectionPaths.caseOf(connectedWithNullHost),
    )
    assertEquals(
      ConnectionPaths.Disconnected,
      ConnectionPaths.caseOf(Connection.Disconnected),
    )
    assertEquals(
      setOf<Any?>(ConnectionPaths.Disconnected, ConnectionPaths.Connected),
      ConnectionPaths.allCasePaths.toSet(),
    )
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
      ConnectionPaths.Connected.__kpaths_id,
    )
    assertEquals(
      ConnectionPaths.Connected,
      caseKeyPath<Connection, Connection.Connected>(),
    )
    assertNull(
      connectedWithNullHost[ConnectionPaths.Connected]
        ?.get(keyPath = ConnectionPaths.ConnectedPaths.host),
    )
    assertEquals(
      Connection.Connected(host = "example.com"),
      connectedWithNullHost[ConnectionPaths.Connected]
        ?.copy(host = "example.com"),
    )
  }

  @Test
  fun `KSP generates canonical reconstructable payload paths for sealed data cases`() {
    val selected: CaseKeyPath<Request, String> = RequestPaths.Selected.value
    val identified: CaseKeyPath<Request, String> = RequestPaths.Identified.id
    val typed: CaseKeyPath<Request, String> = RequestPaths.Typed.valueType
    val navigate: CaseKeyPath<Request, Pair<String, String>> = RequestPaths.Navigate.payload
    val route: CaseKeyPath<Request, Triple<String, String, Boolean>> = RequestPaths.Route.payload
    val nullable: CasePath<Request, String?> = RequestPaths.Nullable.value

    assertEquals("Blob", Request.Selected("Blob")[selected])
    assertEquals("id-1", Request.Identified("id-1")[identified])
    assertEquals(Request.Identified("id-2"), identified.embed("id-2"))
    assertEquals("text", Request.Typed("text")[typed])
    assertEquals(Request.Typed("other"), typed.embed("other"))
    assertNull(Request.Nullable(null)[selected])
    assertEquals(CaseMatch.Matched(null), Request.Nullable(null)[nullable])
    assertEquals(CaseMatch.Matched("note"), Request.Nullable("note")[nullable])
    assertEquals(CaseMatch.NoMatch, Request.Selected("Blob")[nullable])
    assertTrue(Request.Nullable(null).caseMatches(nullable))
    assertFalse(Request.Selected("Blob").caseMatches(nullable))
    assertNull(Request.Nullable(null).getOrNull(nullable))
    assertEquals(Request.Nullable(null), nullable.embed(null))
    assertEquals(
      nullable,
      casePath<Request, Request.Nullable, _>(
        extract = Request.Nullable::value,
        embed = ::NullableCase,
      ),
    )
    assertEquals(Request.Selected("Jr."), selected.embed("Jr."))
    assertEquals(
      selected,
      caseKeyPath<Request, Request.Selected, _>(
        extract = Request.Selected::value,
        embed = ::SelectedCase,
      ),
    )
    assertEquals(
      "id-1" to "settings",
      Request.Navigate("id-1", "settings")[navigate],
    )
    assertEquals(
      Request.Navigate("id-2", "profile"),
      navigate.embed("id-2" to "profile"),
    )
    assertEquals(
      navigate,
      caseKeyPath<Request, Request.Navigate, _, _>(
        extract1 = Request.Navigate::id,
        extract2 = Request.Navigate::destination,
        embed = ::NavigateCase,
      ),
    )
    assertEquals(
      Triple("id-3", "documents", true),
      Request.Route("id-3", "documents", true)[route],
    )
    assertEquals(
      Request.Route("id-4", "home", false),
      route.embed(Triple("id-4", "home", false)),
    )
    assertEquals(
      route,
      caseKeyPath<Request, Request.Route, _, _, _>(
        extract1 = Request.Route::id,
        extract2 = Request.Route::destination,
        extract3 = Request.Route::replace,
        embed = ::RouteCase,
      ),
    )
    assertEquals("Blob", Request.Selected("Blob")[RequestPaths.SelectedPaths.value])
    assertEquals(null, Request.Nullable(null)[RequestPaths.NullablePaths.value])
  }

  @Test
  fun `KSP generates enum case paths with Unit focuses`() {
    val north: CaseKeyPath<Direction, Unit> = DirectionPaths.NORTH

    assertEquals(Unit, Direction.NORTH[DirectionPaths.NORTH])
    assertNull(Direction.SOUTH[DirectionPaths.NORTH])
    assertEquals(Direction.NORTH, north.embed(Unit))
    assertTrue(north.matches(Direction.NORTH))
    assertFalse(north.matches(Direction.SOUTH))
    assertEquals(
      dev.kpaths.core.PathId.case(TypeId("dev.kpaths.sample.Direction"), "NORTH"),
      DirectionPaths.NORTH.__kpaths_id,
    )
    assertEquals(TypeId.of<Unit>(), DirectionPaths.NORTH.__kpaths_valueType)
    assertEquals(DirectionPaths.NORTH, caseKeyPath(Direction.NORTH))
    assertEquals(DirectionPaths.NORTH, DirectionPaths.caseOf(Direction.NORTH))
    assertEquals(DirectionPaths.SOUTH, DirectionPaths.caseOf(Direction.SOUTH))
    assertEquals(
      setOf<Any?>(DirectionPaths.NORTH, DirectionPaths.SOUTH),
      DirectionPaths.allCasePaths.toSet(),
    )
  }

  @Test
  fun `KSP preserves keyword enum case names with Kotlin backticks`() {
    assertEquals(Unit, KeywordDirection.`when`[KeywordDirectionPaths.`when`])
    assertEquals(KeywordDirection.`when`, KeywordDirectionPaths.`when`.embed(Unit))
  }

  @Test
  fun `KSP preserves backticked property and sealed case names`() {
    val property = KeywordProperty(`display name` = "Blob")
    val state: KeywordCase = KeywordCase.`when`("now")

    assertEquals("Blob", property[KeywordPropertyPaths.`display name`])
    assertEquals(KeywordCase.`when`("now"), state[KeywordCasePaths.`when`])
    assertEquals("now", state[KeywordCasePaths.`when`]?.get(keyPath = KeywordCasePaths.whenPaths.value))
  }

  @Test
  fun `KSP generates generic sealed case paths with substituted type identities`() {
    val stringPaths = LoadablePaths<String>()
    val integerPaths = LoadablePaths<Int>()
    val loaded: CaseKeyPath<Loadable<String>, Loadable.Loaded<String>> = stringPaths.Loaded
    val loadedValue: CaseKeyPath<Loadable<String>, String> = stringPaths.Loaded.value
    val value: Loadable<String> = Loadable.Loaded("Blob")

    assertEquals(Loadable.Loaded("Blob"), value[stringPaths.Loaded])
    assertEquals("Blob", value[loadedValue])
    assertEquals(Loadable.Loaded("Blob Jr."), loadedValue.embed("Blob Jr."))
    assertEquals("Blob", value[stringPaths.Loaded]?.get(keyPath = stringPaths.LoadedPaths.value))
    assertFalse((stringPaths.LoadedPaths.value as Any) is WritableKeyPath<*, *>)
    assertNull(Loadable.Idle[stringPaths.Loaded])
    assertEquals(Loadable.Idle, Loadable.Idle[stringPaths.Idle])
    assertEquals(stringPaths.Loaded, stringPaths.caseOf(value))
    assertEquals(stringPaths.Idle, stringPaths.caseOf(Loadable.Idle))
    assertEquals(
      setOf<Any?>(stringPaths.Idle, stringPaths.Loaded),
      stringPaths.allCasePaths.toSet(),
    )
    assertEquals(Loadable.Loaded("Blob Jr."), loaded.embed(Loadable.Loaded("Blob Jr.")))
    assertEquals(
      stringPaths.Loaded,
      caseKeyPath<Loadable<String>, Loadable.Loaded<String>>(),
    )
    assertEquals(
      loadedValue,
      caseKeyPath<Loadable<String>, Loadable.Loaded<String>, _>(
        extract = Loadable.Loaded<String>::value,
        embed = ::LoadedCase,
      ),
    )
    assertNotEquals(stringPaths.Loaded.__kpaths_id, integerPaths.Loaded.__kpaths_id)
    assertEquals(
      TypeId(
        "dev.kpaths.sample.Loadable",
        listOf(TypeArgument.invariant(TypeId.of<String>())),
      ),
      stringPaths.Loaded.__kpaths_id.rootType,
    )
    assertEquals(
      TypeId(
        "dev.kpaths.sample.Loadable.Loaded",
        listOf(TypeArgument.invariant(TypeId.of<String>())),
      ),
      stringPaths.Loaded.__kpaths_valueType,
    )
  }

  @Test
  fun `KSP substitutes each generic root parameter through its matching case`() {
    val paths = ApiResultPaths<String, Int>()
    val success: CaseKeyPath<ApiResult<String, Int>, ApiResult.Success<String>> = paths.Success
    val error: CaseKeyPath<ApiResult<String, Int>, ApiResult.Error<Int>> = paths.Error
    val successValue: CasePath<ApiResult<String, Int>, String> = paths.Success.value
    val successState: ApiResult<String, Int> = ApiResult.Success("Blob")
    val errorState: ApiResult<String, Int> = ApiResult.Error(404)

    assertEquals("Blob", successState[success]?.get(keyPath = paths.SuccessPaths.value))
    assertEquals(CaseMatch.Matched("Blob"), successState[successValue])
    assertEquals(CaseMatch.NoMatch, errorState[successValue])
    assertNull(errorState[success])
    assertEquals(404, errorState[error]?.get(keyPath = paths.ErrorPaths.error))
    assertNull(successState[error])
    assertEquals(
      TypeId(
        "dev.kpaths.sample.ApiResult.Success",
        listOf(TypeArgument.invariant(TypeId.of<String>())),
      ),
      paths.Success.__kpaths_valueType,
    )
    assertEquals(
      TypeId(
        "dev.kpaths.sample.ApiResult.Error",
        listOf(TypeArgument.invariant(TypeId.of<Int>())),
      ),
      paths.Error.__kpaths_valueType,
    )
  }

  @Test
  fun `matching views a generated case path as a nullable read-only key path`() {
    val selected = RequestPaths.Selected.matching()
    val selectedCase = Request.Selected("Blob")

    assertEquals(selectedCase, selected.get(selectedCase))
    assertNull(selected.get(Request.Navigate("id-1", "settings")))
    assertEquals(RequestPaths.Selected.__kpaths_id, selected.__kpaths_id)
    assertEquals(TypeId.of<Request.Selected?>(), selected.__kpaths_valueType)
    assertEquals(selected, RequestPaths.Selected.matching())
  }
}

private object EquivalentNamePath : KeyPathBase<Person, String>() {
  override val __kpaths_id = dev.kpaths.core.PathId.property(TypeId("dev.kpaths.sample.Person"), "name")
  override val __kpaths_valueType = TypeId.of<String>()

  override fun get(root: Person): String = root.name
}
