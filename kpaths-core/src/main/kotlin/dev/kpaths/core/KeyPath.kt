package dev.kpaths.core

/** Metadata shared by all generated paths, independent of their focusing capability. */
public interface AnyPath {
  /** Library-reserved structural identity metadata. */
  public val __kpaths_id: PathId

  /** Library-reserved focused-value type metadata. */
  public val __kpaths_valueType: TypeId
}

/** Type-erased metadata common to all property key paths. */
public interface AnyKeyPath : AnyPath

/** A key path with a known root type and an erased, but always-present, focused value. */
public interface PartialKeyPath<Root> : AnyKeyPath {
  /** Reads this path while erasing the focused value's static type. */
  public fun getAny(root: Root): Any?
}

/**
 * A total, typed read path.
 *
 * A key path is also a Kotlin function from [Root] to [Value]. This lets a path participate
 * directly in APIs such as `map`, `sortedBy`, and `groupBy` while retaining its path identity and
 * composition capabilities.
 */
public interface KeyPath<Root, Value> : PartialKeyPath<Root>, (Root) -> Value {
  /** Reads the focused value from [root]. */
  public fun get(root: Root): Value

  /** Invokes this path as its total read function. */
  override operator fun invoke(root: Root): Value = get(root)

  override fun getAny(root: Root): Any? = get(root)
}

/**
 * Base class for generated paths.
 *
 * A [PathId] includes property-versus-case segments, so structural identity remains distinct for
 * a property KeyPath and a CaseKeyPath even when their spelling happens to be the same.
 */
public abstract class PathBase : AnyPath {
  final override fun equals(other: Any?): Boolean =
    other is AnyPath &&
      __kpaths_id == other.__kpaths_id &&
      __kpaths_valueType == other.__kpaths_valueType

  final override fun hashCode(): Int =
    31 * __kpaths_id.hashCode() + __kpaths_valueType.hashCode()

  override fun toString(): String =
    "${this::class.simpleName}(id=$__kpaths_id, valueType=$__kpaths_valueType)"
}

/** Base class for generated total property key paths. */
public abstract class KeyPathBase<Root, Value> : PathBase(), KeyPath<Root, Value>

/** A typed property path that replaces a focus and returns the resulting root. */
public interface WritableKeyPath<Root, Value> : KeyPath<Root, Value> {
  /** Replaces the focused value and returns the resulting root. */
  public fun set(root: Root, value: Value): Root

  /** Transforms the focused value and returns the resulting root. */
  public fun modify(root: Root, update: (Value) -> Value): Root =
    set(root, update(get(root)))
}

/** Base class for generated immutable-write key paths. */
public abstract class WritableKeyPathBase<Root, Value> :
  KeyPathBase<Root, Value>(),
  WritableKeyPath<Root, Value>

/**
 * Appends a total read path.
 *
 * This is equivalent to Swift's `appending(path:)`. It is read-only unless both component paths
 * are writable; see the more-specific overload below.
 */
public fun <Root, Middle, Value> KeyPath<Root, Middle>.appending(
  next: KeyPath<Middle, Value>,
): KeyPath<Root, Value> = AppendedKeyPath(this, next)

/** Kotlin-friendly spelling of [appending]. */
public fun <Root, Middle, Value> KeyPath<Root, Middle>.then(
  next: KeyPath<Middle, Value>,
): KeyPath<Root, Value> = appending(next)

/**
 * Optionally appends a read path after a nullable focus.
 *
 * This is the Kotlin equivalent of a read-only optional-chaining key path. A missing [Middle]
 * produces a null focus; otherwise [next] reads the contained value. The result deliberately
 * remains read-only because there is no universal meaning for setting through a missing optional
 * focus or assigning null to the final focus.
 */
public fun <Root, Middle : Any, Value> KeyPath<Root, Middle?>.appendingOptional(
  next: KeyPath<Middle, Value>,
): KeyPath<Root, Value?> = OptionalAppendedKeyPath(this, next)

/** Kotlin-friendly spelling of optional key-path [appendingOptional]. */
public fun <Root, Middle : Any, Value> KeyPath<Root, Middle?>.thenOptional(
  next: KeyPath<Middle, Value>,
): KeyPath<Root, Value?> = appendingOptional(next)

/**
 * Appends a path after force-unwrapping a nullable focus.
 *
 * This is the Kotlin counterpart to a Swift `!` segment in a key path, such as
 * `\\Person.address!.city`. The returned path has a non-null focus, but reading or writing it
 * throws [NullPointerException] when the intermediate [Middle] is absent.
 */
public fun <Root, Middle : Any, Value> KeyPath<Root, Middle?>.appendingForceUnwrapped(
  next: KeyPath<Middle, Value>,
): KeyPath<Root, Value> = ForceUnwrappedAppendedKeyPath(this, next)

/** Kotlin-friendly spelling of [appendingForceUnwrapped]. */
public fun <Root, Middle : Any, Value> KeyPath<Root, Middle?>.thenForceUnwrapped(
  next: KeyPath<Middle, Value>,
): KeyPath<Root, Value> = appendingForceUnwrapped(next)

/** Appends two writable paths, retaining immutable replacement capability. */
public fun <Root, Middle, Value> WritableKeyPath<Root, Middle>.appending(
  next: WritableKeyPath<Middle, Value>,
): WritableKeyPath<Root, Value> = AppendedWritableKeyPath(this, next)

/** Kotlin-friendly spelling of writable [appending]. */
public fun <Root, Middle, Value> WritableKeyPath<Root, Middle>.then(
  next: WritableKeyPath<Middle, Value>,
): WritableKeyPath<Root, Value> = appending(next)

/**
 * Appends a writable path after force-unwrapping a nullable focus.
 *
 * Both component paths must be writable. The resulting setter replaces the nested value and then
 * the outer value; it throws [NullPointerException] rather than silently doing nothing when the
 * nullable focus is absent.
 */
public fun <Root, Middle : Any, Value> WritableKeyPath<Root, Middle?>.appendingForceUnwrapped(
  next: WritableKeyPath<Middle, Value>,
): WritableKeyPath<Root, Value> = ForceUnwrappedAppendedWritableKeyPath(this, next)

/** Kotlin-friendly spelling of writable [appendingForceUnwrapped]. */
public fun <Root, Middle : Any, Value> WritableKeyPath<Root, Middle?>.thenForceUnwrapped(
  next: WritableKeyPath<Middle, Value>,
): WritableKeyPath<Root, Value> = appendingForceUnwrapped(next)

private class AppendedKeyPath<Root, Middle, Value>(
  private val outer: KeyPath<Root, Middle>,
  private val inner: KeyPath<Middle, Value>,
) : KeyPathBase<Root, Value>() {
  override val __kpaths_id: PathId = outer.__kpaths_id.appending(inner.__kpaths_id)
  override val __kpaths_valueType: TypeId = inner.__kpaths_valueType

  override fun get(root: Root): Value = inner.get(outer.get(root))
}

private class OptionalAppendedKeyPath<Root, Middle : Any, Value>(
  private val outer: KeyPath<Root, Middle?>,
  private val inner: KeyPath<Middle, Value>,
) : KeyPathBase<Root, Value?>() {
  override val __kpaths_id: PathId = outer.__kpaths_id
    .appending(PathSegment.OptionalChain)
    .appending(inner.__kpaths_id)
  override val __kpaths_valueType: TypeId = inner.__kpaths_valueType.withNullability(true)

  override fun get(root: Root): Value? = outer.get(root)?.let(inner::get)
}

private class ForceUnwrappedAppendedKeyPath<Root, Middle : Any, Value>(
  private val outer: KeyPath<Root, Middle?>,
  private val inner: KeyPath<Middle, Value>,
) : KeyPathBase<Root, Value>() {
  override val __kpaths_id: PathId = outer.__kpaths_id
    .appending(PathSegment.ForceUnwrap)
    .appending(inner.__kpaths_id)
  override val __kpaths_valueType: TypeId = inner.__kpaths_valueType

  override fun get(root: Root): Value = inner.get(forceUnwrappedValue(outer, root))
}

private class AppendedWritableKeyPath<Root, Middle, Value>(
  private val outer: WritableKeyPath<Root, Middle>,
  private val inner: WritableKeyPath<Middle, Value>,
) : WritableKeyPathBase<Root, Value>() {
  override val __kpaths_id: PathId = outer.__kpaths_id.appending(inner.__kpaths_id)
  override val __kpaths_valueType: TypeId = inner.__kpaths_valueType

  override fun get(root: Root): Value = inner.get(outer.get(root))

  override fun set(root: Root, value: Value): Root =
    outer.set(root, inner.set(outer.get(root), value))
}

private class ForceUnwrappedAppendedWritableKeyPath<Root, Middle : Any, Value>(
  private val outer: WritableKeyPath<Root, Middle?>,
  private val inner: WritableKeyPath<Middle, Value>,
) : WritableKeyPathBase<Root, Value>() {
  override val __kpaths_id: PathId = outer.__kpaths_id
    .appending(PathSegment.ForceUnwrap)
    .appending(inner.__kpaths_id)
  override val __kpaths_valueType: TypeId = inner.__kpaths_valueType

  override fun get(root: Root): Value = inner.get(forceUnwrappedValue(outer, root))

  override fun set(root: Root, value: Value): Root {
    val middle = forceUnwrappedValue(outer, root)
    return outer.set(root, inner.set(middle, value))
  }
}

/**
 * A writable key path whose replacement mutates the same root instance.
 *
 * It intentionally inherits [WritableKeyPath], as Swift's ReferenceWritableKeyPath inherits
 * WritableKeyPath. Reducer frameworks may warn about mutable state, but should not reject this
 * path at the type level.
 */
public interface ReferenceWritableKeyPath<Root : Any, Value> : WritableKeyPath<Root, Value> {
  /** Replaces the focus by mutating [root] itself. */
  public fun setInPlace(root: Root, value: Value)

  override fun set(root: Root, value: Value): Root {
    setInPlace(root, value)
    return root
  }
}

/** Base class for generated paths that mutate a property on the same root instance. */
public abstract class ReferenceWritableKeyPathBase<Root : Any, Value> :
  KeyPathBase<Root, Value>(),
  ReferenceWritableKeyPath<Root, Value>

/**
 * Composition rooted in a reference-writable path remains reference-writable: setting the final
 * focus updates the same outer root instance.
 */
public fun <Root : Any, Middle, Value> ReferenceWritableKeyPath<Root, Middle>.appending(
  next: WritableKeyPath<Middle, Value>,
): ReferenceWritableKeyPath<Root, Value> = AppendedReferenceWritableKeyPath(this, next)

/** Kotlin-friendly spelling of reference-writable [appending]. */
public fun <Root : Any, Middle, Value> ReferenceWritableKeyPath<Root, Middle>.then(
  next: WritableKeyPath<Middle, Value>,
): ReferenceWritableKeyPath<Root, Value> = appending(next)

/**
 * Appends a writable path after force-unwrapping a nullable reference-owned focus.
 *
 * The result preserves reference-writable semantics and throws [NullPointerException] when the
 * intermediate focus is absent.
 */
public fun <Root : Any, Middle : Any, Value> ReferenceWritableKeyPath<Root, Middle?>.appendingForceUnwrapped(
  next: WritableKeyPath<Middle, Value>,
): ReferenceWritableKeyPath<Root, Value> = ForceUnwrappedAppendedReferenceWritableKeyPath(this, next)

/** Kotlin-friendly spelling of reference-writable [appendingForceUnwrapped]. */
public fun <Root : Any, Middle : Any, Value> ReferenceWritableKeyPath<Root, Middle?>.thenForceUnwrapped(
  next: WritableKeyPath<Middle, Value>,
): ReferenceWritableKeyPath<Root, Value> = appendingForceUnwrapped(next)

private class AppendedReferenceWritableKeyPath<Root : Any, Middle, Value>(
  private val outer: ReferenceWritableKeyPath<Root, Middle>,
  private val inner: WritableKeyPath<Middle, Value>,
) : ReferenceWritableKeyPathBase<Root, Value>() {
  override val __kpaths_id: PathId = outer.__kpaths_id.appending(inner.__kpaths_id)
  override val __kpaths_valueType: TypeId = inner.__kpaths_valueType

  override fun get(root: Root): Value = inner.get(outer.get(root))

  override fun setInPlace(root: Root, value: Value) {
    outer.setInPlace(root, inner.set(outer.get(root), value))
  }
}

private class ForceUnwrappedAppendedReferenceWritableKeyPath<Root : Any, Middle : Any, Value>(
  private val outer: ReferenceWritableKeyPath<Root, Middle?>,
  private val inner: WritableKeyPath<Middle, Value>,
) : ReferenceWritableKeyPathBase<Root, Value>() {
  override val __kpaths_id: PathId = outer.__kpaths_id
    .appending(PathSegment.ForceUnwrap)
    .appending(inner.__kpaths_id)
  override val __kpaths_valueType: TypeId = inner.__kpaths_valueType

  override fun get(root: Root): Value = inner.get(forceUnwrappedValue(outer, root))

  override fun setInPlace(root: Root, value: Value) {
    val middle = forceUnwrappedValue(outer, root)
    outer.setInPlace(root, inner.set(middle, value))
  }
}

private fun <Root, Value : Any> forceUnwrappedValue(
  path: KeyPath<Root, Value?>,
  root: Root,
): Value = path.get(root) ?: throw NullPointerException(
  "KPaths force-unwrapped a null intermediate value at ${path.__kpaths_id}.",
)
