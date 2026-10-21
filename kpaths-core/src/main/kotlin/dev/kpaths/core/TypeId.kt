package dev.kpaths.core

import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.KVariance
import kotlin.reflect.typeOf

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
    /** Creates a non-generic token from a Kotlin class reference such as `Int::class`. */
    public fun from(type: KClass<*>): TypeId =
      TypeId(type.qualifiedName ?: error("Local and anonymous classes cannot form stable TypeIds."))

    /**
     * Captures a complete Kotlin type at an inline call site, including generic arguments and
     * nullability. This powers generated syntax such as `BoxPaths<List<String>>()`.
     */
    @OptIn(ExperimentalStdlibApi::class)
    public inline fun <reified T> of(): TypeId = from(typeOf<T>())

    /** Converts Kotlin reflection metadata into the stable representation used by paths. */
    public fun from(type: KType): TypeId {
      val classifier = when (val classifier = type.classifier) {
        is KClass<*> -> classifier.qualifiedName
        is KTypeParameter -> classifier.name
        else -> null
      } ?: error("Local and anonymous types cannot form stable TypeIds.")
      val arguments = type.arguments.map { argument ->
        val argumentType = argument.type
        when (argument.variance) {
          null -> TypeArgument.star
          KVariance.INVARIANT -> TypeArgument.invariant(from(requireNotNull(argumentType)))
          KVariance.IN -> TypeArgument.`in`(from(requireNotNull(argumentType)))
          KVariance.OUT -> TypeArgument.out(from(requireNotNull(argumentType)))
        }
      }
      return TypeId(classifier, arguments, type.isMarkedNullable)
    }
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
