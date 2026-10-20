package dev.kpaths.sample

import kotlin.test.Test
import kotlin.test.assertEquals

class GeneratedPathsTest {
  @Test
  fun `KSP generates metadata for annotated types`() {
    assertEquals("dev.kpaths.sample.CounterState", dev_kpaths_sample_CounterState__KPaths.sourceType)
    assertEquals(1, dev_kpaths_sample_CounterState__KPaths.declaredPropertyCount)
    assertEquals("dev.kpaths.sample.CounterAction", dev_kpaths_sample_CounterAction__KPaths.sourceType)
  }
}
