package dev.kpaths.core

/**
 * The structural identity of a generated path.
 *
 * Equality and hashing deliberately describe the route, not a closure or allocation that happens
 * to implement it. Generated paths use stable fully-qualified type names and ordered segments.
 */
public data class PathId(
  public val rootType: String,
  public val segments: List<PathSegment>,
) {
  public companion object {
    public fun property(rootType: String, name: String): PathId =
      PathId(rootType, listOf(PathSegment.Property(name)))

    public fun case(rootType: String, name: String): PathId =
      PathId(rootType, listOf(PathSegment.Case(name)))
  }

  public fun appending(segment: PathSegment): PathId =
    copy(segments = segments + segment)
}

/** A named step in a path's structural identity. */
public sealed interface PathSegment {
  public data class Property(public val name: String) : PathSegment
  public data class Case(public val name: String) : PathSegment
  public data class Index(public val value: Int) : PathSegment
  public data class Key(public val value: Any?) : PathSegment
}
