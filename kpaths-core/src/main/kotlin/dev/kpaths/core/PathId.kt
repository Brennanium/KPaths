package dev.kpaths.core

/**
 * The structural identity of a generated path.
 *
 * Equality and hashing deliberately describe the route, not a closure or allocation that happens
 * to implement it. Generated paths use stable fully-qualified type names and ordered segments.
 */
public class PathId(
  public val rootType: TypeId,
  segments: List<PathSegment>,
) {
  /** A defensive copy keeps this hashable identity stable after construction. */
  public val segments: List<PathSegment> = segments.toList()

  public companion object {
    /** The identity route that focuses an entire root value. */
    public fun identity(rootType: TypeId): PathId = PathId(rootType, emptyList())

    public fun property(rootType: TypeId, name: String): PathId =
      PathId(rootType, listOf(PathSegment.Property(name)))

    public fun case(rootType: TypeId, name: String): PathId =
      PathId(rootType, listOf(PathSegment.Case(name)))

    /**
     * An explicitly named route for a manual or computed path.
     *
     * Use a stable, library-qualified name when paths may need to compare equal across call
     * sites. A custom route deliberately remains distinct from a stored property of the same
     * spelling.
     */
    public fun custom(rootType: TypeId, name: String): PathId =
      PathId(rootType, listOf(PathSegment.Custom(name)))
  }

  public fun appending(segment: PathSegment): PathId =
    PathId(rootType, segments + segment)

  /**
   * Appends a path rooted at this path's focus.
   *
   * The Kotlin type system proves that the roots line up for the public composition APIs. The
   * result intentionally retains the outer path's root type.
   */
  public fun appending(path: PathId): PathId =
    PathId(rootType, segments + path.segments)

  override fun equals(other: Any?): Boolean =
    other is PathId && rootType == other.rootType && segments == other.segments

  override fun hashCode(): Int = 31 * rootType.hashCode() + segments.hashCode()

  override fun toString(): String = "PathId(rootType=$rootType, segments=$segments)"
}

/** A named step in a path's structural identity. */
public sealed interface PathSegment {
  public data class Property(public val name: String) : PathSegment
  public data class Case(public val name: String) : PathSegment
  public data class Custom(public val name: String) : PathSegment
  public data class Index(public val value: Int) : PathSegment
  public data class Key(public val value: Any?) : PathSegment
}
