package dev.kpaths.codegen.model

import kotlin.test.Test
import kotlin.test.assertEquals

class KPathsGenericCasePolicyTest {
  @Test
  fun `maps reordered parameters and covariant Nothing`() {
    val result = planKPathsGenericCase(
      listOf(KPathsGenericRootParameter("A", false), KPathsGenericRootParameter("B", false)),
      listOf("X", "Y"),
      listOf(KPathsGenericCaseArgument("Y"), KPathsGenericCaseArgument("X")),
    )
    assertEquals(KPathsGenericCasePlan.Supported(mapOf("Y" to "A", "X" to "B")), result)
    assertEquals(
      KPathsGenericCasePlan.Supported(emptyMap()),
      planKPathsGenericCase(listOf(KPathsGenericRootParameter("T", true)), emptyList(),
        listOf(KPathsGenericCaseArgument(isNothing = true, rendered = "kotlin.Nothing"))),
    )
  }

  @Test
  fun `rejects repeated unmapped nullable and specialized arguments`() {
    val root = listOf(KPathsGenericRootParameter("A", false), KPathsGenericRootParameter("B", false))
    assertEquals(KPathsGenericCasePlan.ConflictingParameter("X", "A", "B"),
      planKPathsGenericCase(root, listOf("X"), listOf(KPathsGenericCaseArgument("X"), KPathsGenericCaseArgument("X"))))
    assertEquals(KPathsGenericCasePlan.UnmappedParameters(listOf("Y")),
      planKPathsGenericCase(root.take(1), listOf("X", "Y"), listOf(KPathsGenericCaseArgument("X"))))
    assertEquals(KPathsGenericCasePlan.UnsupportedArgument("A", "X?"),
      planKPathsGenericCase(root.take(1), listOf("X"),
        listOf(KPathsGenericCaseArgument("X", isNullable = true, rendered = "X?"))))
    assertEquals(KPathsGenericCasePlan.UnsupportedArgument("A", "kotlin.String"),
      planKPathsGenericCase(root.take(1), emptyList(), listOf(KPathsGenericCaseArgument(rendered = "kotlin.String"))))
    assertEquals(KPathsGenericCasePlan.UnsupportedArgument("A", "kotlin.Nothing"),
      planKPathsGenericCase(root.take(1), emptyList(),
        listOf(KPathsGenericCaseArgument(isNothing = true, rendered = "kotlin.Nothing"))))
  }
}
