package dev.kpaths.codegen.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KPathsAdapterPolicyTest {
  private val root = KPathsAdapterType.Parameter("Root")
  private val value = KPathsAdapterType.Parameter("Value")
  private fun type(name: String, vararg args: KPathsAdapterType, nullable: Boolean = false) =
    KPathsAdapterType.Classifier(name, args.toList(), nullable)
  private val shape = KPathsAdapterFactoryShape(
    functionCount = 1, isObjectFactory = true, isAccessible = true, isSuspend = false,
    parameterCount = 1, parameterType = type("dev.kpaths.core.KeyPath", root, type("example.Cell", value)),
    resultType = type("example.CellKeyPath", root, value), typeParameters = setOf("Root", "Value"),
  )

  @Test fun `substitutes generic root and nullable nested focus`() {
    val actualRoot = type("example.Box", KPathsAdapterType.Parameter("T"))
    val focus = type("kotlin.collections.List", type("kotlin.String", nullable = true))
    val plan = assertIs<KPathsAdapterPlan.Supported>(planKPathsAdapter(shape, actualRoot, type("example.Cell", focus)))
    assertEquals(type("example.CellKeyPath", actualRoot, focus), plan.resultType)
  }

  @Test fun `rejects unmatched storage and unmapped result parameters`() {
    assertIs<KPathsAdapterPlan.Invalid>(planKPathsAdapter(shape, type("example.Root"), type("example.Other")))
    assertIs<KPathsAdapterPlan.Invalid>(planKPathsAdapter(
      shape.copy(resultType = type("example.Path", KPathsAdapterType.Parameter("Missing"))),
      type("example.Root"), type("example.Cell", type("kotlin.String")),
    ))
  }

  @Test fun `rejects overloaded and inaccessible factories`() {
    assertIs<KPathsAdapterPlan.Invalid>(planKPathsAdapter(shape.copy(functionCount = 2), root, value))
    assertIs<KPathsAdapterPlan.Invalid>(planKPathsAdapter(shape.copy(isAccessible = false), root, value))
    assertIs<KPathsAdapterPlan.Invalid>(planKPathsAdapter(shape.copy(isExtension = true), root, value))
    assertIs<KPathsAdapterPlan.Invalid>(planKPathsAdapter(shape.copy(hasExplicitBounds = true), root, value))
  }
}
