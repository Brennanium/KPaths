package dev.kpaths.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaseKeyPathTest {
  @Test
  fun `bound payload helper preserves matching context and embeds`() {
    val whole = caseKeyPath<MultiEvent, MultiEvent.PairValue>()
    val payload = caseKeyPath<MultiEvent, MultiEvent.PairValue, _, _>(
      extract1 = MultiEvent.PairValue::id,
      extract2 = MultiEvent.PairValue::action,
      embed = MultiEvent::PairValue,
    )
    val bound = __kpaths_boundCasePayload<MultiEvent, String>(
      whole, payload, TypeId.of<MultiEvent.PairValue>(), TypeId.of<String>(), "action", 1, listOf("one"),
    )
    assertEquals(CaseMatch.Matched("go"), bound.match(MultiEvent.PairValue("one", "go")))
    assertEquals(CaseMatch.NoMatch, bound.match(MultiEvent.PairValue("two", "go")))
    assertEquals(MultiEvent.PairValue("one", "stop"), bound.embed("stop"))
  }

  @Test
  fun `CasePathCatalogBase caches its catalog lazily`() {
    var builds = 0
    val catalog = object : CasePathCatalogBase<ManualEvent>() {
      override fun __kpaths_buildAllCasePaths(): List<PartialCaseKeyPath<ManualEvent>> {
        builds += 1
        return listOf(manualDismissedPath())
      }
    }

    assertEquals(0, builds)
    assertEquals(1, catalog.allCasePaths.size)
    assertEquals(1, builds)
    assertEquals(catalog.allCasePaths, catalog.allCasePaths)
    assertEquals(1, builds)
  }

  @Test
  fun `catalogs classify values and support manual CasePathable conformance`() {
    val catalog = object : CasePathCatalog<ManualEvent> {
      private val dismissed = manualDismissedPath()
      private val selected = caseKeyPath<ManualEvent, ManualEvent.Selected, _>(
        extract = ManualEvent.Selected::name,
        embed = { name -> ManualEvent.Selected(name) },
      )

      override val allCasePaths: List<PartialCaseKeyPath<ManualEvent>> = listOf(dismissed, selected)
    }
    val event = ManuallyCasePathableEvent.Selected("Blob")

    assertEquals(catalog.allCasePaths[1], catalog.caseOf(ManualEvent.Selected("Blob")))
    assertEquals(ManuallyCasePathableEvent.Paths.selected, event.case)
  }

  @Test
  fun `whole case paths independently embed`() {
    val selected = caseKeyPath<ManualEvent, ManualEvent.Selected>()

    assertEquals(ManualEvent.Selected("Jr."), selected.embed(ManualEvent.Selected("Jr.")))
    assertEquals(ManualEvent.Selected("Blob"), ManualEvent.Selected("Blob")[selected])
    assertNull(ManualEvent.Dismissed[selected])
  }

  @Test
  fun `manual factories embed unary pair and triple payloads`() {
    val selected = caseKeyPath<ManualEvent, ManualEvent.Selected, _>(
      extract = ManualEvent.Selected::name,
      embed = { value -> ManualEvent.Selected(value) },
    )
    val pair = caseKeyPath<MultiEvent, MultiEvent.PairValue, _, _>(
      extract1 = MultiEvent.PairValue::id,
      extract2 = MultiEvent.PairValue::action,
      embed = { id, action -> MultiEvent.PairValue(id, action) },
    )

    assertEquals(ManualEvent.Selected("Jr."), selected.embed("Jr."))
    assertEquals(MultiEvent.PairValue("id", "go"), pair.embed("id" to "go"))
  }

  @Test
  fun `case paths compose with independent embedding`() {
    val outer = caseKeyPath<ManualOuter, ManualOuter.Nested>()
    val inner = caseKeyPath<ManualOuter.Nested, ManualOuter.Nested.Selected>()
    val selected = outer.then(inner)

    assertEquals(ManualOuter.Nested.Selected("Jr."), selected.embed(ManualOuter.Nested.Selected("Jr.")))
    assertEquals(ManualOuter.Nested.Selected("Blob"), ManualOuter.Nested.Selected("Blob")[selected])
  }

  @Test
  fun `matched null remains distinguishable`() {
    val nullable = caseKeyPath<NullableEvent, NullableEvent.Selected, _>(
      extract = NullableEvent.Selected::value,
      embed = { value -> NullableEvent.Selected(value) },
    )

    assertEquals(CaseMatch.Matched(null), NullableEvent.Selected(null).match(nullable))
    assertTrue(NullableEvent.Selected(null).caseMatches(nullable))
    assertNull(NullableEvent.Idle[nullable])
  }
}

private sealed interface ManualEvent {
  data object Dismissed : ManualEvent
  data class Selected(val name: String) : ManualEvent
}

private fun manualDismissedPath(): CaseKeyPath<ManualEvent, Unit> = caseKeyPath(
  id = PathId.case(TypeId.of<ManualEvent>(), "Dismissed"),
  valueType = TypeId.of<Unit>(),
  match = { root ->
    if (root === ManualEvent.Dismissed) CaseMatch.Matched(Unit) else CaseMatch.NoMatch
  },
  embed = { ManualEvent.Dismissed },
)

private sealed interface MultiEvent {
  data class PairValue(val id: String, val action: String) : MultiEvent
}

private sealed interface NullableEvent {
  data object Idle : NullableEvent
  data class Selected(val value: String?) : NullableEvent
}

private sealed interface ManualOuter {
  sealed interface Nested : ManualOuter {
    data class Selected(val name: String) : Nested
  }
}

private sealed interface ManuallyCasePathableEvent : CasePathable<ManuallyCasePathableEvent> {
  data object Idle : ManuallyCasePathableEvent
  data class Selected(val name: String) : ManuallyCasePathableEvent

  override val `case`: PartialCaseKeyPath<ManuallyCasePathableEvent>
    get() = Paths.caseOf(this)

  object Paths : CasePathCatalog<ManuallyCasePathableEvent> {
    val idle = caseKeyPath<ManuallyCasePathableEvent, Unit>(
      id = PathId.case(TypeId.of<ManuallyCasePathableEvent>(), "Idle"),
      valueType = TypeId.of<Unit>(),
      match = { root -> if (root === Idle) CaseMatch.Matched(Unit) else CaseMatch.NoMatch },
      embed = { Idle },
    )
    val selected = caseKeyPath<ManuallyCasePathableEvent, Selected, _>(
      extract = Selected::name,
      embed = { name -> Selected(name) },
    )

    override val allCasePaths: List<PartialCaseKeyPath<ManuallyCasePathableEvent>> = listOf(idle, selected)
  }
}
