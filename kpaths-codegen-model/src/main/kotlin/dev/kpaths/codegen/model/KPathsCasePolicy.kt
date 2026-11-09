package dev.kpaths.codegen.model

/** The source-level form of a case candidate considered for a generated case catalog. */
public enum class KPathsCaseKind {
  /** A direct class or object subtype of a sealed root. */
  SEALED_VARIANT,

  /** One entry of a Kotlin enum class. */
  ENUM_ENTRY,
}

/** Frontend-neutral facts needed to decide whether a candidate can form a generated case path. */
public data class KPathsCaseShape(
  public val kind: KPathsCaseKind,
  public val isClassOrObject: Boolean = true,
  public val hasQualifiedName: Boolean = true,
)

/** The result of validating one potential generated case path. */
public sealed interface KPathsCasePolicyResult {
  /** The candidate can participate in a generated case catalog. */
  public data object Supported : KPathsCasePolicyResult

  /** A sealed root has a direct subtype that cannot be represented as a Kotlin case type. */
  public data object SealedVariantMustBeClassOrObject : KPathsCasePolicyResult

  /** A stable structural identity cannot be formed for a local or anonymous sealed subtype. */
  public data object SealedVariantRequiresQualifiedName : KPathsCasePolicyResult
}

/**
 * Shared eligibility policy for generated CaseKeyPath candidates.
 *
 * KSP and compiler-plugin frontends perform their own symbol discovery, then pass only these
 * facts into this policy. This keeps supported case shapes and user-facing diagnostics aligned.
 */
public fun KPathsCaseShape.classifyForKPaths(): KPathsCasePolicyResult = when (kind) {
  KPathsCaseKind.ENUM_ENTRY -> KPathsCasePolicyResult.Supported
  KPathsCaseKind.SEALED_VARIANT -> when {
    !isClassOrObject -> KPathsCasePolicyResult.SealedVariantMustBeClassOrObject
    !hasQualifiedName -> KPathsCasePolicyResult.SealedVariantRequiresQualifiedName
    else -> KPathsCasePolicyResult.Supported
  }
}

/** Shared, frontend-independent wording for KPaths generation diagnostics. */
public object KPathsDiagnostics {
  public fun nestedCatalogCollision(rootName: String, catalogName: String): String =
    "Cannot generate '$rootName.$catalogName' because '$rootName' already declares a nested type named " +
      "'$catalogName'. Rename the existing type."

  public fun reservedSourceName(kind: String, name: String): String =
    "Cannot generate paths because $kind '$name' uses the reserved " +
      "'$KPATHS_INTERNAL_PREFIX' prefix for KPaths-generated implementation details."

  public fun generatedMemberCollision(collision: KPathsGeneratedMemberCollision): String =
    "Cannot generate paths because '${collision.name}' would name both " +
      collision.members.joinToString(" and ") { it.description } + ". Rename one of the model members."

  public fun incompleteCaseCatalog(caseNames: List<String>): String =
    "The generated case catalog omits ${caseNames.joinToString { "'$it'" }} because no canonical " +
      "payload path can be generated. allCasePaths is incomplete, and caseOf (or case) cannot " +
      "identify those cases. Whole-case paths remain available."

  public fun sealedVariantMustBeClassOrObject(name: String): String =
    "Sealed variant '$name' must be a class or object."

  public fun sealedVariantRequiresQualifiedName(): String =
    "Sealed variants must have qualified names."

  public fun inaccessibleDataClassSetter(name: String): String =
    "Data-class property '$name' must be declared in the primary constructor or have an accessible " +
      "setter to receive a copy-based writable path."
}
