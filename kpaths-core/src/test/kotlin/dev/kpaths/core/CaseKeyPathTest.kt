package dev.kpaths.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaseKeyPathTest {
  @Test
  fun `concrete subtype factory safely extracts and embeds a sealed case`() {
    val selected = caseKeyPath<ManualEvent, ManualEvent.Selected>()

    assertEquals(ManualEvent.Selected("Blob"), selected.extract(ManualEvent.Selected("Blob")))
    assertNull(selected.extract(ManualEvent.Dismissed))
    assertEquals(ManualEvent.Selected("Jr."), selected.embed(ManualEvent.Selected("Jr.")))
    assertEquals(
      PathId.case(TypeId.of<ManualEvent>(), "Selected"),
      selected.id,
    )
  }

  @Test
  fun `enum factory extracts and embeds one exact entry`() {
    val north = caseKeyPath(ManualDirection.NORTH)

    assertEquals(Unit, north.extract(ManualDirection.NORTH))
    assertNull(north.extract(ManualDirection.SOUTH))
    assertEquals(ManualDirection.NORTH, north.embed(Unit))
    assertEquals(PathId.case(TypeId.of<ManualDirection>(), "NORTH"), north.id)
  }

  @Test
  fun `explicit factory supports a custom case representation`() {
    val even = caseKeyPath(
      id = PathId.custom(TypeId.of<Int>(), "test.Int.even"),
      valueType = TypeId.of<Int>(),
      extract = { value: Int -> value.takeIf { it % 2 == 0 } },
      embed = { value -> value },
    )

    assertEquals(42, even.extract(42))
    assertNull(even.extract(41))
    assertEquals(2, even.embed(2))
  }

  @Test
  fun `case paths compose while retaining extraction and embedding`() {
    val outer = caseKeyPath<ManualOuter, ManualOuter.Nested>()
    val inner = caseKeyPath<ManualOuter.Nested, ManualOuter.Nested.Selected>()
    val selected = outer.then(inner)

    assertEquals(
      ManualOuter.Nested.Selected("Blob"),
      selected.extract(ManualOuter.Nested.Selected("Blob")),
    )
    assertNull(selected.extract(ManualOuter.Nested.Dismissed))
    assertEquals(
      ManualOuter.Nested.Selected("Jr."),
      selected.embed(ManualOuter.Nested.Selected("Jr.")),
    )
    assertEquals(
      PathId(
        rootType = TypeId.of<ManualOuter>(),
        segments = listOf(
          PathSegment.Case("Nested"),
          PathSegment.Case("Selected"),
        ),
      ),
      selected.id,
    )
  }

  @Test
  fun `constructor-reference factory creates a unary case payload path`() {
    val selected = caseKeyPath<ManualEvent, ManualSelected, _>(
      extract = ManualSelected::name,
      embed = ::ManualSelected,
    )

    assertEquals("Blob", selected.extract(ManualSelected("Blob")))
    assertNull(selected.extract(ManualEvent.Dismissed))
    assertEquals(ManualSelected("Jr."), selected.embed("Jr."))
    assertEquals(
      PathId(
        rootType = TypeId.of<ManualEvent>(),
        segments = listOf(
          PathSegment.Case("ManualSelected"),
          PathSegment.Property("name"),
        ),
      ),
      selected.id,
    )
  }

  @Test
  fun `constructor-reference factory creates a two-value case payload path`() {
    val navigate = caseKeyPath<ManualEvent, ManualNavigate, _, _>(
      extract1 = ManualNavigate::id,
      extract2 = ManualNavigate::action,
      embed = ::ManualNavigate,
    )

    assertEquals(
      "id-1" to ManualChildAction.Refresh,
      navigate.extract(ManualNavigate("id-1", ManualChildAction.Refresh)),
    )
    assertNull(navigate.extract(ManualEvent.Dismissed))
    assertEquals(
      ManualNavigate("id-2", ManualChildAction.Save),
      navigate.embed("id-2" to ManualChildAction.Save),
    )
    assertEquals(
      TypeId.of<Pair<String, ManualChildAction>>(),
      navigate.valueType,
    )
  }

  @Test
  fun `constructor-reference factory creates a three-value case payload path`() {
    val navigate = caseKeyPath<ManualEvent, ManualRoute, _, _, _>(
      extract1 = ManualRoute::id,
      extract2 = ManualRoute::destination,
      extract3 = ManualRoute::action,
      embed = ::ManualRoute,
    )

    assertEquals(
      Triple("id-1", "settings", ManualChildAction.Refresh),
      navigate.extract(ManualRoute("id-1", "settings", ManualChildAction.Refresh)),
    )
    assertNull(navigate.extract(ManualEvent.Dismissed))
    assertEquals(
      ManualRoute("id-2", "profile", ManualChildAction.Save),
      navigate.embed(Triple("id-2", "profile", ManualChildAction.Save)),
    )
    assertEquals(
      TypeId.of<Triple<String, String, ManualChildAction>>(),
      navigate.valueType,
    )
  }
}

private sealed interface ManualEvent {
  data object Dismissed : ManualEvent
  data class Selected(val name: String) : ManualEvent
}

private data class ManualSelected(val name: String) : ManualEvent

private data class ManualNavigate(
  val id: String,
  val action: ManualChildAction,
) : ManualEvent

private data class ManualRoute(
  val id: String,
  val destination: String,
  val action: ManualChildAction,
) : ManualEvent

private enum class ManualChildAction {
  Refresh,
  Save,
}

private enum class ManualDirection {
  NORTH,
  SOUTH,
}

private sealed interface ManualOuter {
  sealed interface Nested : ManualOuter {
    data object Dismissed : Nested
    data class Selected(val name: String) : Nested
  }
}
