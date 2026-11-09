package dev.kpaths.codegen.model

/**
 * Renderer-neutral description of the complete type metadata stored by a generated path.
 *
 * KSP and the K2 compiler plugin each translate their own symbol model into this shape, then
 * render it as Kotlin source or IR respectively. Keeping the structure here ensures both
 * backends agree on generic arguments, variance, nullability, and path identity.
 */
public sealed interface KPathsTypeIdSpec {
  /** A concrete, named Kotlin type such as `kotlin.collections.List<String?>`. */
  public data class Declared(
    public val classifier: String,
    public val arguments: List<KPathsTypeArgumentSpec> = emptyList(),
    public val nullable: Boolean = false,
  ) : KPathsTypeIdSpec {
    init {
      require(classifier.isNotBlank()) { "A KPaths type classifier cannot be blank." }
    }
  }

  /** A source type parameter whose concrete `TypeId` must be supplied by the enclosing catalog. */
  public data class Parameter(
    public val name: String,
    public val nullable: Boolean = false,
  ) : KPathsTypeIdSpec {
    init {
      require(name.isNotBlank()) { "A KPaths type parameter name cannot be blank." }
    }
  }
}

/** One generic argument in [KPathsTypeIdSpec.Declared]. */
public data class KPathsTypeArgumentSpec(
  public val variance: KPathsTypeVariance,
  public val type: KPathsTypeIdSpec? = null,
) {
  init {
    require((variance == KPathsTypeVariance.STAR) == (type == null)) {
      "Only a star-projected KPaths type argument may omit its type."
    }
  }

  public companion object {
    public val star: KPathsTypeArgumentSpec = KPathsTypeArgumentSpec(KPathsTypeVariance.STAR)
    public fun invariant(type: KPathsTypeIdSpec): KPathsTypeArgumentSpec =
      KPathsTypeArgumentSpec(KPathsTypeVariance.INVARIANT, type)
    public fun `in`(type: KPathsTypeIdSpec): KPathsTypeArgumentSpec =
      KPathsTypeArgumentSpec(KPathsTypeVariance.IN, type)
    public fun out(type: KPathsTypeIdSpec): KPathsTypeArgumentSpec =
      KPathsTypeArgumentSpec(KPathsTypeVariance.OUT, type)
  }
}

/** Use-site variance preserved in generated [dev.kpaths.core.TypeId] metadata. */
public enum class KPathsTypeVariance { INVARIANT, IN, OUT, STAR }

/** A renderer-neutral structural identity for a generated property or case path. */
public data class KPathsPathIdSpec(
  public val rootType: KPathsTypeIdSpec,
  public val segments: List<KPathsPathSegmentSpec>,
) {
  public companion object {
    public fun property(rootType: KPathsTypeIdSpec, name: String): KPathsPathIdSpec =
      KPathsPathIdSpec(rootType, listOf(KPathsPathSegmentSpec.Property(name)))

    public fun case(rootType: KPathsTypeIdSpec, name: String): KPathsPathIdSpec =
      KPathsPathIdSpec(rootType, listOf(KPathsPathSegmentSpec.Case(name)))
  }
}

/** Segments supported by generated catalog paths. */
public sealed interface KPathsPathSegmentSpec {
  public data class Property(public val name: String) : KPathsPathSegmentSpec
  public data class Case(public val name: String) : KPathsPathSegmentSpec
}
