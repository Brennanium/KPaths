package dev.kpaths.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaseKeyPathTest {
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
