package dev.kpaths.compiler.fixture

import dev.kpaths.core.PathId
import dev.kpaths.core.TypeId
import dev.kpaths.core.CaseMatch
import dev.kpaths.compiler.integration.BinaryCell
import dev.kpaths.compiler.integration.BinaryReadable
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeGeneratedPathsTest {
  @Test
  fun generatedDraftPathsUseTrackingSettersAndCopyOriginalValues() {
    val original = TrackedRecord("Original", RecordAddress("First"))
    val copied = nativeOriginalNamePath.set(original, "Copy")
    assertEquals("Original", original.name)
    assertEquals(TrackedRecord("Copy", original.address), copied)

    val draft = TrackedRecord.Draft()
    nativeDraftNamePath.setInPlace(draft, "Revised")
    nativeDraftAddressPath.setInPlace(draft, DraftAddress("Second"))
    assertEquals("Revised", nativeDraftNamePath.get(draft))
    assertEquals(DraftAddress("Second"), nativeDraftAddressPath.get(draft))
    assertEquals(2, draftWriteCount(draft))
    assertEquals(TypeId.of<DraftAddress>(), nativeDraftAddressPath.__kpaths_valueType)
    assertEquals(PathId.property(TypeId.of<TrackedRecord.Draft>(), "address"), nativeDraftAddressPath.__kpaths_id)
  }

  @Test
  fun synthesizedGenericAndAdaptedPathsRetainTheirIdentity() {
    assertEquals(listOf("generated", null), nativeSynthesizedTitlePath.get(SyntheticOwner.Snapshot))
    assertEquals(TypeId.of<List<String?>>(), nativeSynthesizedTitlePath.__kpaths_valueType)
    assertEquals(null, nativeSynthesizedGenericPath.get(SyntheticOwner.GenericSnapshot<String?>()))
    assertEquals(TypeId.of<String?>(), nativeSynthesizedGenericPath.__kpaths_valueType)
    assertEquals(
      PathId.property(TypeId.of<SyntheticOwner.GenericSnapshot<String?>>(), "title"),
      nativeSynthesizedGenericPath.__kpaths_id,
    )
  }

  @Test
  fun declarativeAdaptersAndCasePathsWorkAtRuntime() {
    assertEquals(null, nativeDeclarativePath.get(NativeBinaryDocument(BinaryCell(null))))
    assertEquals(TypeId.of<String?>(), nativeDeclarativePath.__kpaths_valueType)
    assertEquals("reader", nativeReaderPath.get(NativeReaderDocument(BinaryReadable("reader"))))
    assertEquals(TypeId.of<String?>(), nativeReaderPath.__kpaths_valueType)

    val payload = NativeEvent.Paths<String?>().Value.value
    assertEquals(CaseMatch.Matched(null), payload.match(NativeEvent.Value(null)))
    assertEquals(CaseMatch.NoMatch, payload.match(NativeEvent.Idle))
    assertEquals(NativeEvent.Value("updated"), payload.embed("updated"))
  }
}
