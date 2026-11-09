package dev.kpaths.codegen.model

/** The implementation capability selected for one generated property path. */
public enum class KPathsPropertyPathKind {
  READ_ONLY,
  COPY_WRITABLE,
  COPY_THEN_MUTATE,
  REFERENCE_WRITABLE,
}

/** Frontend-neutral facts needed to classify one declared property. */
public data class KPathsPropertyShape(
  public val ownerIsDataClass: Boolean,
  public val isMutable: Boolean,
  public val isPrimaryConstructorProperty: Boolean,
  public val hasAccessibleSetter: Boolean,
  public val copyWritableVals: Boolean,
  public val isExcludedFromCopyWritableVals: Boolean,
)

/** The result of property-path capability policy, including a diagnosable unsupported mutation. */
public sealed interface KPathsPropertyPolicyResult {
  public data class Supported(
    public val kind: KPathsPropertyPathKind,
  ) : KPathsPropertyPolicyResult

  /** A data-class `var` cannot be safely represented with the configured value semantics. */
  public data object InaccessibleDataClassSetter : KPathsPropertyPolicyResult
}

/**
 * Shared KPaths policy for classifying declared stored properties.
 *
 * Frontends remain responsible for source visibility, delegated/computed-property eligibility,
 * diagnostics locations, and generating the actual getter/setter bodies. This policy makes the
 * value-versus-reference capability decision identical across generators.
 */
public fun KPathsPropertyShape.classifyForKPaths(): KPathsPropertyPolicyResult {
  if (!ownerIsDataClass) {
    return KPathsPropertyPolicyResult.Supported(
      if (isMutable && hasAccessibleSetter) {
        KPathsPropertyPathKind.REFERENCE_WRITABLE
      } else {
        KPathsPropertyPathKind.READ_ONLY
      },
    )
  }

  if (
    (isMutable || (copyWritableVals && !isExcludedFromCopyWritableVals)) &&
    isPrimaryConstructorProperty
  ) {
    return KPathsPropertyPolicyResult.Supported(KPathsPropertyPathKind.COPY_WRITABLE)
  }
  if (isMutable && hasAccessibleSetter) {
    return KPathsPropertyPolicyResult.Supported(KPathsPropertyPathKind.COPY_THEN_MUTATE)
  }
  if (isMutable) return KPathsPropertyPolicyResult.InaccessibleDataClassSetter
  return KPathsPropertyPolicyResult.Supported(KPathsPropertyPathKind.READ_ONLY)
}
