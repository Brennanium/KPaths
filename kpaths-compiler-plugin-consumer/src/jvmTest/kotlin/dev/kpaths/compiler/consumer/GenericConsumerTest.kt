package dev.kpaths.compiler.consumer

import dev.kpaths.compiler.fixture.FixtureBox
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
  }
}
