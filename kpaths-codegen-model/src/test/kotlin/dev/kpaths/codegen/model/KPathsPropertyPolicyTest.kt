package dev.kpaths.codegen.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KPathsPropertyPolicyTest {
  @Test
  fun `generic variants require generic roots`() {
    kotlin.test.assertTrue(isKPathsGenericVariantOfConcreteRoot(false, true))
    kotlin.test.assertFalse(isKPathsGenericVariantOfConcreteRoot(false, false))
    kotlin.test.assertFalse(isKPathsGenericVariantOfConcreteRoot(true, true))
    kotlin.test.assertFalse(isKPathsGenericVariantOfConcreteRoot(true, false))
  }

  @Test
  fun `matches KPaths value and reference property semantics`() {
    assertEquals(
      KPathsPropertyPolicyResult.Supported(KPathsPropertyPathKind.READ_ONLY),
      KPathsPropertyShape(false, false, false, false, false, false).classifyForKPaths(),
    )
    assertEquals(
      KPathsPropertyPolicyResult.Supported(KPathsPropertyPathKind.REFERENCE_WRITABLE),
      KPathsPropertyShape(false, true, false, true, false, false).classifyForKPaths(),
    )
    assertEquals(
      KPathsPropertyPolicyResult.Supported(KPathsPropertyPathKind.COPY_WRITABLE),
      KPathsPropertyShape(true, true, true, true, false, false).classifyForKPaths(),
    )
    assertEquals(
      KPathsPropertyPolicyResult.Supported(KPathsPropertyPathKind.COPY_WRITABLE),
      KPathsPropertyShape(true, false, true, false, true, false).classifyForKPaths(),
    )
    assertEquals(
      KPathsPropertyPolicyResult.InaccessibleDataClassSetter,
      KPathsPropertyShape(true, true, false, false, false, false).classifyForKPaths(),
    )
  }
}

class KPathsPathModelTest {
  @Test
  fun `preserves nested generic nullable type structure`() {
    val string = KPathsTypeIdSpec.Declared("kotlin.String", nullable = true)
    val list = KPathsTypeIdSpec.Declared(
      "kotlin.collections.List",
      listOf(KPathsTypeArgumentSpec.out(string)),
    )
    val cell = KPathsTypeIdSpec.Declared(
      "fixture.Cell",
      listOf(KPathsTypeArgumentSpec.invariant(list)),
      nullable = true,
    )

    assertEquals(
      KPathsPathIdSpec.property(KPathsTypeIdSpec.Declared("fixture.Document"), "title"),
      KPathsPathIdSpec.property(KPathsTypeIdSpec.Declared("fixture.Document"), "title"),
    )
    assertEquals(true, cell.nullable)
    assertEquals(KPathsTypeVariance.OUT, list.arguments.single().variance)
  }

  @Test
  fun `rejects malformed star projections`() {
    assertFailsWith<IllegalArgumentException> {
      KPathsTypeArgumentSpec(KPathsTypeVariance.STAR, KPathsTypeIdSpec.Declared("kotlin.String"))
    }
  }
}

class KPathsCasePolicyTest {
  @Test
  fun `bound payload plan preserves focus and constructor order`() {
    assertEquals(emptyList(), planKPathsBoundPayloadMembers(listOf("value")))
    assertEquals(emptyList(), planKPathsBoundPayloadMembers(listOf("a", "b", "c", "d")))
    assertEquals(
      listOf(
        KPathsBoundPayloadMember(0, "id", listOf("text", "flag")),
        KPathsBoundPayloadMember(1, "text", listOf("id", "flag")),
        KPathsBoundPayloadMember(2, "flag", listOf("id", "text")),
      ),
      planKPathsBoundPayloadMembers(listOf("id", "text", "flag")),
    )
  }

  @Test
  fun `payload eligibility records the reason a canonical path is unavailable`() {
    val shape = KPathsCasePayloadShape(true, true, true, listOf("id", "value"), true)
    assertEquals(KPathsCasePayloadDecision.Available(KPathsCasePayload.Pair("id", "value")), shape.classifyForKPaths())
    assertEquals(KPathsCasePayloadDecision.Unavailable(KPathsCasePayloadUnavailableReason.NOT_DATA_CLASS), shape.copy(isDataClass = false).classifyForKPaths())
    assertEquals(KPathsCasePayloadDecision.Unavailable(KPathsCasePayloadUnavailableReason.NO_PRIMARY_CONSTRUCTOR), shape.copy(hasPrimaryConstructor = false).classifyForKPaths())
    assertEquals(KPathsCasePayloadDecision.Unavailable(KPathsCasePayloadUnavailableReason.INACCESSIBLE_CONSTRUCTOR), shape.copy(isConstructorAccessible = false).classifyForKPaths())
    assertEquals(KPathsCasePayloadDecision.Unavailable(KPathsCasePayloadUnavailableReason.UNSUPPORTED_ARITY), shape.copy(propertyNames = listOf("a", "b", "c", "d")).classifyForKPaths())
    assertEquals(KPathsCasePayloadDecision.Unavailable(KPathsCasePayloadUnavailableReason.UNAVAILABLE_PROPERTY), shape.copy(allParametersAreAccessibleProperties = false).classifyForKPaths())
  }
  @Test
  fun `accepts named sealed classes objects and enum entries`() {
    assertEquals(
      KPathsCasePolicyResult.Supported,
      KPathsCaseShape(KPathsCaseKind.SEALED_VARIANT).classifyForKPaths(),
    )
    assertEquals(
      KPathsCasePolicyResult.Supported,
      KPathsCaseShape(KPathsCaseKind.ENUM_ENTRY).classifyForKPaths(),
    )
  }

  @Test
  fun `rejects unsupported and unstable sealed variants`() {
    assertEquals(
      KPathsCasePolicyResult.SealedVariantMustBeClassOrObject,
      KPathsCaseShape(
        kind = KPathsCaseKind.SEALED_VARIANT,
        isClassOrObject = false,
      ).classifyForKPaths(),
    )
    assertEquals(
      KPathsCasePolicyResult.SealedVariantRequiresQualifiedName,
      KPathsCaseShape(
        kind = KPathsCaseKind.SEALED_VARIANT,
        hasQualifiedName = false,
      ).classifyForKPaths(),
    )
  }

  @Test
  fun `models canonical catalog access as associated-value paths`() {
    val message = KPathsCasePathModel(
      sourceName = "Message",
      generatedName = "Message",
      kind = KPathsCaseKind.SEALED_VARIANT,
      payload = KPathsCasePayload.Single("text"),
    )
    val refresh = KPathsCasePathModel(
      sourceName = "Refresh",
      generatedName = "Refresh",
      kind = KPathsCaseKind.SEALED_VARIANT,
      payload = KPathsCasePayload.Unit,
    )
    val online = KPathsCasePathModel(
      sourceName = "Online",
      generatedName = "Online",
      kind = KPathsCaseKind.ENUM_ENTRY,
      payload = KPathsCasePayload.Unit,
    )

    assertEquals(
      KPathsCaseCatalogAccess.Payload(KPathsCasePayload.Single("text")),
      message.catalogAccess(),
    )
    assertEquals(
      KPathsCaseCatalogAccess.Payload(KPathsCasePayload.Unit),
      refresh.catalogAccess(),
    )
    assertEquals(KPathsCaseCatalogAccess.WholeCase, online.catalogAccess())
  }

  @Test
  fun `models one two and three property payloads`() {
    assertEquals(KPathsCasePayload.Single("value"), KPathsCasePayload.fromProperties(listOf("value")))
    assertEquals(
      KPathsCasePayload.Pair("id", "action"),
      KPathsCasePayload.fromProperties(listOf("id", "action")),
    )
    assertEquals(
      KPathsCasePayload.Triple("x", "y", "z"),
      KPathsCasePayload.fromProperties(listOf("x", "y", "z")),
    )
    assertEquals(null, KPathsCasePayload.fromProperties(emptyList()))
    assertEquals(null, KPathsCasePayload.fromProperties(listOf("a", "b", "c", "d")))
  }

  @Test
  fun `catalog plan preserves payload paths and identifies unavailable cases`() {
    val idle = KPathsCasePathModel("Idle", "Idle", KPathsCaseKind.SEALED_VARIANT, KPathsCasePayload.Unit)
    val details = KPathsCasePathModel("Details", "Details", KPathsCaseKind.SEALED_VARIANT)
    val selected = KPathsCasePathModel("Selected", "Selected", KPathsCaseKind.SEALED_VARIANT, KPathsCasePayload.Single("value"))
    val plan = planKPathsCaseCatalog(listOf(idle, details, selected))

    assertEquals(listOf(idle, selected), plan.entries.map { it.case })
    assertEquals(listOf(details), plan.unavailableCases)
    assertEquals(false, plan.isComplete)
    assertEquals(true, planKPathsCaseCatalog(listOf(idle, selected)).isComplete)
  }
}
