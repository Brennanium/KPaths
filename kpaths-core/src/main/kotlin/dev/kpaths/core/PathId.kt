package dev.kpaths.core

/**
 * The structural identity of a generated KeyPath or CaseKeyPath.
 *
 * Equality and hashing deliberately describe the KeyPath or CaseKeyPath, not a closure or allocation
 * that happens to implement it. Generated paths use stable fully-qualified type names and ordered segments.
 */
public class PathId(
  public val rootType: TypeId,
  segments: List<PathSegment>,
) {
  /** A defensive copy keeps this hashable identity stable after construction. */
  public val segments: List<PathSegment> = segments.toList()

  public companion object {
    /** The identity KeyPath that focuses an entire root value. */
    public fun identity(rootType: TypeId): PathId = PathId(rootType, emptyList())

    /** Identifies a declared property named [name] on [rootType]. */
    public fun property(rootType: TypeId, name: String): PathId =
      PathId(rootType, listOf(PathSegment.Property(name)))

    /** Identifies a sealed or enum case named [name] on [rootType]. */
    public fun case(rootType: TypeId, name: String): PathId =
      PathId(rootType, listOf(PathSegment.Case(name)))

    /**
     * An explicitly named KeyPath segment for a manual or computed KeyPath.
     *
     * Use a stable, library-qualified name when KeyPaths may need to compare equal across call
     * sites. A custom KeyPath deliberately remains distinct from a stored property of the same
     * spelling.
     */
    public fun custom(rootType: TypeId, name: String): PathId =
      PathId(rootType, listOf(PathSegment.Custom(name)))
  }

  /** Returns this [PathId] with one additional structural [segment]. */
  public fun appending(segment: PathSegment): PathId =
    PathId(rootType, segments + segment)

  /**
   * Appends a [PathId] rooted at this PathId's focus.
   *
   * The Kotlin type system proves that the roots line up for the public composition APIs. The
   * result intentionally retains the outer KeyPath or CaseKeyPath root type.
   */
  public fun appending(path: PathId): PathId =
    PathId(rootType, segments + path.segments)

  override fun equals(other: Any?): Boolean =
    other is PathId && rootType == other.rootType && segments == other.segments

  override fun hashCode(): Int = 31 * rootType.hashCode() + segments.hashCode()

  override fun toString(): String = "PathId(rootType=$rootType, segments=$segments)"
}

/** A named step in a KeyPath or CaseKeyPath structural identity. */
public sealed interface PathSegment {
  /** A declared property KeyPath segment. */
  public data class Property(public val name: String) : PathSegment

  /** A sealed or enum CaseKeyPath segment. */
  public data class Case(public val name: String) : PathSegment

  /** A caller-defined KeyPath segment intentionally distinct from a declared property. */
  public data class Custom(public val name: String) : PathSegment

  /** An indexed collection KeyPath segment. */
  public data class Index(public val value: Int) : PathSegment

  /** A keyed map KeyPath segment. */
  public data class Key(public val value: Any?) : PathSegment

  /** A conditional optional-chaining step. */
  public data object OptionalChain : PathSegment

  /** A force-unwrapping step that fails when its intermediate value is null. */
  public data object ForceUnwrap : PathSegment
}
