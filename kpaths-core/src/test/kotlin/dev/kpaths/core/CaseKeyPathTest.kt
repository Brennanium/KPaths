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
}

private sealed interface ManualEvent {
  data object Dismissed : ManualEvent
  data class Selected(val name: String) : ManualEvent
}

private enum class ManualDirection {
  NORTH,
  SOUTH,
}
