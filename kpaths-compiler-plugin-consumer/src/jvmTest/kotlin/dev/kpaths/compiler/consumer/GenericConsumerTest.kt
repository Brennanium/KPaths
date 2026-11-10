package dev.kpaths.compiler.consumer

import dev.kpaths.compiler.fixture.FixtureBox
import dev.kpaths.compiler.fixture.FixtureEvent
import dev.kpaths.core.CaseMatch
import dev.kpaths.core.TypeId
import kotlin.test.Test
import kotlin.test.assertEquals

class GenericConsumerTest {
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
    assertEquals(FixtureEvent.Value("updated"), payload.embed("updated"))
    assertEquals(listOf(FixtureEvent.Paths<String?>().Idle.unit, payload), FixtureEvent.Paths<String?>().allCasePaths)
  }
}
