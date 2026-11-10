package dev.kpaths.compiler.consumer

import dev.kpaths.compiler.fixture.FixtureBox
import dev.kpaths.compiler.fixture.FixtureEvent
import dev.kpaths.compiler.fixture.sampleAdaptedDocument
import dev.kpaths.compiler.fixture.SyntheticOwner
import dev.kpaths.compiler.fixture.TrackedRecord
import dev.kpaths.compiler.fixture.RecordAddress
import dev.kpaths.compiler.fixture.DraftAddress
import dev.kpaths.compiler.fixture.draftWriteCount
import dev.kpaths.core.CaseMatch
import dev.kpaths.core.TypeId
import dev.kpaths.core.keyPath
import dev.kpaths.core.PathId
import kotlin.test.Test
import kotlin.test.assertEquals

class GenericConsumerTest {
  @Test
  fun `generated draft setters track writes in a plugin-free consumer`() {
    val original = TrackedRecord("Original", RecordAddress("First"))
    assertEquals("Original", original.name)
    assertEquals(TrackedRecord("Copy", original.address), originalTrackedName.set(original, "Copy"))
    assertEquals("Original", original.name)

    val draft = TrackedRecord.Draft()
    draftTrackedName.setInPlace(draft, "Revised")
    draftProjectedAddress.setInPlace(draft, DraftAddress("Second"))
    assertEquals("Revised", draftTrackedName.get(draft))
    assertEquals(DraftAddress("Second"), draftProjectedAddress.get(draft))
    assertEquals(2, draftWriteCount(draft))
    assertEquals(TypeId.of<DraftAddress>(), draftProjectedAddress.__kpaths_valueType)
  }

  @Test
  fun `externally synthesized catalogs work without either compiler plugin`() {
    assertEquals(listOf("generated", null), syntheticPathFromAnotherModule.get(SyntheticOwner.Snapshot))
    assertEquals(TypeId.of<List<String?>>(), syntheticPathFromAnotherModule.__kpaths_valueType)
    assertEquals(PathId.property(TypeId.of<SyntheticOwner.Snapshot>(), "title"), syntheticPathFromAnotherModule.__kpaths_id)
    assertEquals(null, syntheticGenericPathFromAnotherModule.get(SyntheticOwner.GenericSnapshot<String?>()))
    assertEquals(TypeId.of<String?>(), syntheticGenericPathFromAnotherModule.__kpaths_valueType)
    assertEquals(keyPath(SyntheticOwner.GenericSnapshot<String?>::title).__kpaths_id, syntheticGenericPathFromAnotherModule.__kpaths_id)
  }

  @Test
  fun `specialized adapter path is visible without the compiler plugin`() {
    assertEquals("from dependency", adaptedPathFromAnotherModule.get(sampleAdaptedDocument()))
    assertEquals(TypeId.of<String>(), adaptedPathFromAnotherModule.__kpaths_valueType)
  }

  @Test
  fun `generic generated path works without the compiler plugin`() {
    val original = FixtureBox<String?>(null)
    assertEquals(TypeId.of<String?>(), genericPathFromAnotherModule.__kpaths_valueType)
    assertEquals(FixtureBox<String?>("updated"), genericPathFromAnotherModule.set(original, "updated"))
    assertEquals(FixtureBox<String?>(null), original)
    assertEquals(FixtureBox("updated"), FixtureBox.Paths.of(String::class).value.set(FixtureBox("old"), "updated"))
    assertEquals(genericPathFromAnotherModule, FixtureBox.Paths.of<String?>(TypeId.of<String?>()).value)
  }

  @Test
  fun `generic sealed case works without the compiler plugin`() {
    val path = FixtureEvent.Paths<String?>().Value
    val value = FixtureEvent.Value<String?>(null)
    assertEquals(CaseMatch.Matched(value), path.match(value))
    assertEquals(value, path.embed(value))
    assertEquals(TypeId.of<FixtureEvent.Value<String?>>(), path.__kpaths_valueType)
    val payload = FixtureEvent.Paths<String?>().Value.value
    assertEquals(CaseMatch.Matched(null), payload.match(value))
    assertEquals(null, FixtureEvent.Paths<String?>().ValuePaths.value(value))
    assertEquals(FixtureEvent.Value("updated"), payload.embed("updated"))
    assertEquals(listOf(FixtureEvent.Paths<String?>().Idle.unit, payload), FixtureEvent.Paths<String?>().allCasePaths)
  }
}
