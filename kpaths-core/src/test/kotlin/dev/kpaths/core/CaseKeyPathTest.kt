package dev.kpaths.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CaseKeyPathTest {
  @Test
  fun `whole case factory extracts and conditionally replaces`() {
    val selected = caseKeyPath<ManualEvent, ManualEvent.Selected>()
    val original: ManualEvent = ManualEvent.Selected("Blob")

    assertEquals(ManualEvent.Selected("Blob"), original[selected])
    assertNull(ManualEvent.Dismissed[selected])
    assertEquals(ManualEvent.Selected("Jr."), selected.embed(original, ManualEvent.Selected("Jr.")))
    assertEquals(ManualEvent.Dismissed, selected.embed(ManualEvent.Dismissed, ManualEvent.Selected("Jr.")))
    assertEquals(PathId.case(TypeId.of<ManualEvent>(), "Selected"), selected.__kpaths_id)
  }

  @Test
  fun `read only case paths retain matched null information`() {
    val selected: CaseKeyPath<ManualNullableEvent, String?> = caseKeyPath(
      id = PathId.custom(TypeId.of<ManualNullableEvent>(), "test.ManualNullableEvent.selected"),
      valueType = TypeId.of<String?>(),
      match = { event ->
        when (event) {
          ManualNullableEvent.Idle -> CaseMatch.NoMatch
          is ManualNullableEvent.Selected -> CaseMatch.Matched(event.value)
        }
      },
    )

    assertEquals(CaseMatch.Matched(null), ManualNullableEvent.Selected(null).match(selected))
    assertEquals(CaseMatch.NoMatch, ManualNullableEvent.Idle.match(selected))
    assertNull(ManualNullableEvent.Selected(null)[selected])
    assertTrue(ManualNullableEvent.Selected(null).caseMatches(selected))
    assertFalse(ManualNullableEvent.Idle.caseMatches(selected))
  }

  @Test
  fun `case paths compose only through writable property routes`() {
    val message = caseKeyPath<ManualEvent, ManualEvent.Selected>()
    val name = message.then(
      get = ManualEvent.Selected::name,
      set = { selected, value -> selected.copy(name = value) },
    )

    assertEquals("Blob", ManualEvent.Selected("Blob")[name])
    assertEquals(
      ManualEvent.Selected("Jr."),
      name.embed(ManualEvent.Selected("Blob"), "Jr."),
    )
    assertEquals(ManualEvent.Dismissed, name.embed(ManualEvent.Dismissed, "Jr."))
  }

  @Test
  fun `a mutable property composes to a reference writable case path`() {
    val message = caseKeyPath<MutableEvent, MutableEvent.Message>()
    val text: ReferenceWritableCaseKeyPath<MutableEvent, String> = message.then(MutableEvent.Message::text)
    val root: MutableEvent = MutableEvent.Message("Old")

    assertSame(root, text.embed(root, "New"))
    assertEquals("New", (root as MutableEvent.Message).text)
    assertEquals(MutableEvent.Other, text.embed(MutableEvent.Other, "Ignored"))
  }

  @Test
  fun `writable whole case paths compose`() {
    val outer = caseKeyPath<ManualOuter, ManualOuter.Nested>()
    val inner = caseKeyPath<ManualOuter.Nested, ManualOuter.Nested.Selected>()
    val selected = outer.then(inner)

    assertEquals(ManualOuter.Nested.Selected("Blob"), ManualOuter.Nested.Selected("Blob")[selected])
    assertEquals(
      ManualOuter.Nested.Selected("Jr."),
      selected.embed(ManualOuter.Nested.Selected("Blob"), ManualOuter.Nested.Selected("Jr.")),
    )
    assertEquals(ManualOuter.Nested.Dismissed, selected.embed(ManualOuter.Nested.Dismissed, ManualOuter.Nested.Selected("Jr.")))
  }

  @Test
  fun `constructor reference factories remain root aware`() {
    val selected = caseKeyPath<ManualEvent, ManualSelected, _>(
      extract = ManualSelected::name,
      embed = ::ManualSelected,
    )

    assertEquals(ManualSelected("Jr."), selected.embed(ManualSelected("Blob"), "Jr."))
    assertEquals(ManualEvent.Dismissed, selected.embed(ManualEvent.Dismissed, "Jr."))
  }

  @Test
  fun `matching exposes a non-null case as a nullable key path`() {
    val selected = caseKeyPath<ManualEvent, ManualEvent.Selected>()
    val matching = selected.matching()

    assertEquals(ManualEvent.Selected("Blob"), matching.get(ManualEvent.Selected("Blob")))
    assertNull(matching.get(ManualEvent.Dismissed))
    assertEquals(selected.__kpaths_id, matching.__kpaths_id)
  }
}

private sealed interface ManualEvent {
  data object Dismissed : ManualEvent
  data class Selected(val name: String) : ManualEvent
}

private sealed interface ManualNullableEvent {
  data object Idle : ManualNullableEvent
  data class Selected(val value: String?) : ManualNullableEvent
}

private data class ManualSelected(val name: String) : ManualEvent

private sealed interface MutableEvent {
  data object Other : MutableEvent
  class Message(var text: String) : MutableEvent
}

private sealed interface ManualOuter {
  sealed interface Nested : ManualOuter {
    data object Dismissed : Nested
    data class Selected(val name: String) : Nested
  }
}
