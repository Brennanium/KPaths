package dev.kpaths.codegen.model

/** Frontend-neutral facts needed to construct a case's canonical associated-value path. */
public data class KPathsCasePayloadShape(
  public val isDataClass: Boolean,
  public val hasPrimaryConstructor: Boolean,
  public val isConstructorAccessible: Boolean,
  public val propertyNames: List<String>,
  public val allParametersAreAccessibleProperties: Boolean,
)

/** Why a case has no generated canonical data-class payload path. */
public enum class KPathsCasePayloadUnavailableReason {
  NOT_DATA_CLASS,
  NO_PRIMARY_CONSTRUCTOR,
  INACCESSIBLE_CONSTRUCTOR,
  UNSUPPORTED_ARITY,
  UNAVAILABLE_PROPERTY,
}

public sealed interface KPathsCasePayloadDecision {
  public data class Available(public val payload: KPathsCasePayload) : KPathsCasePayloadDecision
  public data class Unavailable(public val reason: KPathsCasePayloadUnavailableReason) : KPathsCasePayloadDecision
}

/** Applies KSP's existing payload eligibility rules without deciding how to report omissions. */
public fun KPathsCasePayloadShape.classifyForKPaths(): KPathsCasePayloadDecision {
  val reason = when {
    !isDataClass -> KPathsCasePayloadUnavailableReason.NOT_DATA_CLASS
    !hasPrimaryConstructor -> KPathsCasePayloadUnavailableReason.NO_PRIMARY_CONSTRUCTOR
    !isConstructorAccessible -> KPathsCasePayloadUnavailableReason.INACCESSIBLE_CONSTRUCTOR
    propertyNames.size !in 1..3 -> KPathsCasePayloadUnavailableReason.UNSUPPORTED_ARITY
    !allParametersAreAccessibleProperties -> KPathsCasePayloadUnavailableReason.UNAVAILABLE_PROPERTY
    else -> null
  }
  return if (reason != null) KPathsCasePayloadDecision.Unavailable(reason)
  else KPathsCasePayloadDecision.Available(requireNotNull(KPathsCasePayload.fromProperties(propertyNames)))
}
