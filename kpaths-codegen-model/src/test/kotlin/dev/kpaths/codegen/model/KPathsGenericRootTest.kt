package dev.kpaths.codegen.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class KPathsGenericRootTest {
  @Test
  fun `generic root identity retains each ordered parameter`() {
    assertEquals(
      KPathsTypeIdSpec.Declared("example.PairBox", listOf(
        KPathsTypeArgumentSpec.invariant(KPathsTypeIdSpec.Parameter("First")),
        KPathsTypeArgumentSpec.invariant(KPathsTypeIdSpec.Parameter("Second")),
      )),
      KPathsTypeIdSpec.declaredRoot("example.PairBox", listOf("First", "Second")),
    )
    assertNotEquals(
      KPathsTypeIdSpec.declaredRoot("example.Box", emptyList()),
      KPathsTypeIdSpec.declaredRoot("example.Box", listOf("Value")),
    )
  }
}
