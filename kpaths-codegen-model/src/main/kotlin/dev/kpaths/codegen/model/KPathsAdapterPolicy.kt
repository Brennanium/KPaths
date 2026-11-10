package dev.kpaths.codegen.model

/** A frontend-neutral type used to match declarative adapter factory signatures. */
public sealed interface KPathsAdapterType {
  /** A type parameter belonging to the factory function. */
  public data class Parameter(public val name: String) : KPathsAdapterType

  /** A classifier and its invariant type arguments. */
  public data class Classifier(
    public val name: String,
    public val arguments: List<KPathsAdapterType> = emptyList(),
    public val nullable: Boolean = false,
  ) : KPathsAdapterType
}

/** A deliberately narrow, predictable factory contract shared by both frontends. */
public data class KPathsAdapterFactoryShape(
  public val functionCount: Int,
  public val isObjectFactory: Boolean,
  public val isAccessible: Boolean,
  public val isSuspend: Boolean,
  public val isExtension: Boolean = false,
  public val hasExplicitBounds: Boolean = false,
  public val parameterCount: Int,
  public val parameterType: KPathsAdapterType?,
  public val resultType: KPathsAdapterType?,
  public val typeParameters: Set<String>,
)

/** Result of validating and specializing a declarative factory. */
public sealed interface KPathsAdapterPlan {
  public data class Supported(
    public val substitutions: Map<String, KPathsAdapterType>,
    public val resultType: KPathsAdapterType,
  ) : KPathsAdapterPlan
  public data class Invalid(public val reason: String) : KPathsAdapterPlan
}

/**
 * Match `make(KeyPath<Root, Storage>): Result` against an actual root and storage type.
 * Unification only accepts structurally identical invariant types; frontends must diagnose
 * unsupported projections, aliases they cannot expand, and bounds they cannot validate.
 */
public fun planKPathsAdapter(
  shape: KPathsAdapterFactoryShape,
  root: KPathsAdapterType,
  storage: KPathsAdapterType,
): KPathsAdapterPlan {
  fun invalid(reason: String): KPathsAdapterPlan = KPathsAdapterPlan.Invalid(reason)
  if (!shape.isObjectFactory) return invalid("the adapter factory must be an object")
  if (shape.functionCount != 1) return invalid("the adapter factory must declare exactly one make function")
  if (!shape.isAccessible || shape.isSuspend || shape.parameterCount != 1) {
    return invalid("make must be accessible, non-suspending, and take exactly one parameter")
  }
  if (shape.isExtension) return invalid("make must not be an extension function")
  if (shape.hasExplicitBounds) return invalid("make type parameters must not have explicit bounds")
  val parameter = shape.parameterType as? KPathsAdapterType.Classifier
    ?: return invalid("make must accept KeyPath<Root, Storage>")
  if (parameter.name != "dev.kpaths.core.KeyPath" || parameter.nullable || parameter.arguments.size != 2) {
    return invalid("make must accept KeyPath<Root, Storage>")
  }
  val bindings = linkedMapOf<String, KPathsAdapterType>()
  fun match(pattern: KPathsAdapterType, actual: KPathsAdapterType): Boolean = when (pattern) {
    is KPathsAdapterType.Parameter -> {
      if (pattern.name !in shape.typeParameters) false
      else bindings[pattern.name]?.let { it == actual } ?: run { bindings[pattern.name] = actual; true }
    }
    is KPathsAdapterType.Classifier -> actual is KPathsAdapterType.Classifier &&
      pattern.name == actual.name && pattern.nullable == actual.nullable &&
      pattern.arguments.size == actual.arguments.size &&
      pattern.arguments.zip(actual.arguments).all { (p, a) -> match(p, a) }
  }
  if (!match(parameter.arguments[0], root) || !match(parameter.arguments[1], storage)) {
    return invalid("make's KeyPath parameter does not match the generated root and storage types")
  }
  if (shape.typeParameters.any { it !in bindings }) {
    return invalid("all make type parameters must be inferred from the KeyPath parameter")
  }
  fun substitute(type: KPathsAdapterType): KPathsAdapterType? {
    return when (type) {
      is KPathsAdapterType.Parameter -> bindings[type.name]
      is KPathsAdapterType.Classifier -> {
        val args = type.arguments.map { substitute(it) ?: return null }
        type.copy(arguments = args)
      }
    }
  }
  val result = shape.resultType?.let(::substitute)
    ?: return invalid("make's return type must be explicit and use only inferred type parameters")
  return KPathsAdapterPlan.Supported(bindings.toMap(), result)
}
