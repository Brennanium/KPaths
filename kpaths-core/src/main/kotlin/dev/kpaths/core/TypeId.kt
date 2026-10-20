package dev.kpaths.core

/**
 * A generated, structural description of a Kotlin type.
 *
 * Unlike JVM `Class` tokens, this preserves nullability and generic arguments without requiring
 * reflection. KSP emits these values directly into generated paths.
 */
public class TypeId(
  public val classifier: String,
  arguments: List<TypeArgument> = emptyList(),
  public val nullable: Boolean = false,
) {
  /** A defensive copy keeps this hashable token stable after construction. */
  public val arguments: List<TypeArgument> = arguments.toList()

  init {
    require(classifier.isNotBlank()) { "A type classifier cannot be blank." }
  }

  public companion object {
    public val any: TypeId = TypeId("kotlin.Any")
    public val boolean: TypeId = TypeId("kotlin.Boolean")
    public val int: TypeId = TypeId("kotlin.Int")
    public val long: TypeId = TypeId("kotlin.Long")
    public val string: TypeId = TypeId("kotlin.String")
    public val unit: TypeId = TypeId("kotlin.Unit")

    public fun list(element: TypeId): TypeId =
      TypeId("kotlin.collections.List", listOf(TypeArgument.invariant(element)))

    public fun set(element: TypeId): TypeId =
      TypeId("kotlin.collections.Set", listOf(TypeArgument.invariant(element)))

    public fun map(key: TypeId, value: TypeId): TypeId =
      TypeId(
        "kotlin.collections.Map",
        listOf(TypeArgument.invariant(key), TypeArgument.invariant(value)),
      )
  }

  /** Retains generic arguments while changing only nullability. */
  public fun withNullability(nullable: Boolean): TypeId =
    TypeId(classifier, arguments, nullable)

  override fun equals(other: Any?): Boolean =
    other is TypeId &&
      classifier == other.classifier &&
      arguments == other.arguments &&
      nullable == other.nullable

  override fun hashCode(): Int =
    31 * (31 * classifier.hashCode() + arguments.hashCode()) + nullable.hashCode()

  override fun toString(): String {
    val renderedArguments = arguments.takeIf { it.isNotEmpty() }?.joinToString(prefix = "<", postfix = ">")
      .orEmpty()
    return classifier + renderedArguments + if (nullable) "?" else ""
  }
}

/** A type argument, including its use-site variance or a star projection. */
public data class TypeArgument(
  public val variance: TypeVariance,
  public val type: TypeId?,
) {
  init {
    require((variance == TypeVariance.STAR) == (type == null)) {
      "Only a star projection may omit its type."
    }
  }

  public companion object {
    public val star: TypeArgument = TypeArgument(TypeVariance.STAR, null)

    public fun invariant(type: TypeId): TypeArgument = TypeArgument(TypeVariance.INVARIANT, type)

    public fun `in`(type: TypeId): TypeArgument = TypeArgument(TypeVariance.IN, type)

    public fun out(type: TypeId): TypeArgument = TypeArgument(TypeVariance.OUT, type)
  }
}

/** Kotlin use-site variance as part of a structural [TypeId]. */
public enum class TypeVariance {
  INVARIANT,
  IN,
  OUT,
  STAR,
}
