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
  fun `root-scoped factory infers conventional constructor payloads`() {
    val selected = casePathsOf<FactoryEvent>().pathFor(::FactorySelected)
    val routed = casePathsOf<FactoryEvent>().pathFor(::FactoryRoute)

    assertEquals(FactorySelected("Jr."), selected.embed("Jr."))
    assertEquals("Blob", FactorySelected("Blob")[selected])
    assertEquals(FactoryRoute("id", "go"), routed.embed("id" to "go"))
  }

  @Test
  fun `root-scoped factory substitutes generic payload type tokens`() {
    val loaded: CaseKeyPath<GenericFactory<String>, String> =
      casePathsOf<GenericFactory<String>>().pathFor(::GenericLoaded)

    assertEquals(TypeId.of<String>(), loaded.__kpaths_valueType)
    assertEquals(GenericLoaded("Blob"), loaded.embed("Blob"))
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

sealed interface FactoryEvent
data class FactorySelected(val value: String) : FactoryEvent
data class FactoryRoute(val id: String, val action: String) : FactoryEvent

sealed interface GenericFactory<out Value>
data class GenericLoaded<Value>(val value: Value) : GenericFactory<Value>
