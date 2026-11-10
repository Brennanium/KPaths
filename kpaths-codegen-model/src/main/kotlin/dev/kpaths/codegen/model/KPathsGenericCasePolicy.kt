package dev.kpaths.codegen.model

/** One generic parameter of a sealed root and the variance declared on it. */
public data class KPathsGenericRootParameter(
  public val name: String,
  public val isCovariant: Boolean,
)

/** The case's argument at one position in its direct sealed-root supertype. */
public data class KPathsGenericCaseArgument(
  /** A case type parameter forwarded directly at this position, when present. */
  public val forwardedParameter: String? = null,
  public val isNullable: Boolean = false,
  public val isNothing: Boolean = false,
  /** Frontend-rendered spelling used in a diagnostic if this argument cannot be mapped. */
  public val rendered: String = "<unresolved>",
)

/** Result of matching a generic case's parameters to its root's parameters. */
public sealed interface KPathsGenericCasePlan {
  /** Maps each case parameter name to the root parameter whose type it forwards. */
  public data class Supported(public val substitutions: Map<String, String>) : KPathsGenericCasePlan
  public data class ConflictingParameter(
    public val caseParameter: String,
    public val firstRootParameter: String,
    public val secondRootParameter: String,
  ) : KPathsGenericCasePlan
  public data class UnsupportedArgument(
    public val rootParameter: String,
    public val argument: String,
  ) : KPathsGenericCasePlan
  public data class UnmappedParameters(public val names: List<String>) : KPathsGenericCasePlan
}

/** Plans generic substitutions without depending on a compiler frontend's symbol types. */
public fun planKPathsGenericCase(
  rootParameters: List<KPathsGenericRootParameter>,
  caseParameters: List<String>,
  arguments: List<KPathsGenericCaseArgument>,
): KPathsGenericCasePlan {
  val caseNames = caseParameters.toSet()
  val substitutions = linkedMapOf<String, String>()
  rootParameters.forEachIndexed { index, root ->
    val argument = arguments.getOrNull(index) ?: KPathsGenericCaseArgument()
    val forwarded = argument.forwardedParameter
    if (forwarded != null && forwarded in caseNames && !argument.isNullable) {
      val previous = substitutions[forwarded]
      if (previous != null && previous != root.name) {
        return KPathsGenericCasePlan.ConflictingParameter(forwarded, previous, root.name)
      }
      substitutions[forwarded] = root.name
    } else if (!(root.isCovariant && argument.isNothing)) {
      return KPathsGenericCasePlan.UnsupportedArgument(root.name, argument.rendered)
    }
  }
  val missing = caseParameters.filterNot(substitutions::containsKey)
  return if (missing.isEmpty()) KPathsGenericCasePlan.Supported(substitutions)
  else KPathsGenericCasePlan.UnmappedParameters(missing)
}
