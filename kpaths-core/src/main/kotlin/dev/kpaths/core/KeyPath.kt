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
  public fun getAny(root: Root): Any?
}

/** A total, typed read path. */
public interface KeyPath<Root, Value> : PartialKeyPath<Root> {
  public fun get(root: Root): Value

  override fun getAny(root: Root): Any? = get(root)
}

/**
 * Base class for generated paths.
 *
 * A [PathId] includes property-versus-case segments, so structural identity remains distinct for
 * a property route and a case route even when their spelling happens to be the same.
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
  public fun set(root: Root, value: Value): Root

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

/** Appends two writable paths, retaining immutable replacement capability. */
public fun <Root, Middle, Value> WritableKeyPath<Root, Middle>.appending(
  next: WritableKeyPath<Middle, Value>,
): WritableKeyPath<Root, Value> = AppendedWritableKeyPath(this, next)

/** Kotlin-friendly spelling of writable [appending]. */
public fun <Root, Middle, Value> WritableKeyPath<Root, Middle>.then(
  next: WritableKeyPath<Middle, Value>,
): WritableKeyPath<Root, Value> = appending(next)

private class AppendedKeyPath<Root, Middle, Value>(
  private val outer: KeyPath<Root, Middle>,
  private val inner: KeyPath<Middle, Value>,
) : KeyPathBase<Root, Value>() {
  override val __kpaths_id: PathId = outer.__kpaths_id.appending(inner.__kpaths_id)
  override val __kpaths_valueType: TypeId = inner.__kpaths_valueType

  override fun get(root: Root): Value = inner.get(outer.get(root))
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

/**
 * A writable key path whose replacement mutates the same root instance.
 *
 * It intentionally inherits [WritableKeyPath], as Swift's ReferenceWritableKeyPath inherits
 * WritableKeyPath. Reducer frameworks may warn about mutable state, but should not reject this
 * path at the type level.
 */
public interface ReferenceWritableKeyPath<Root : Any, Value> : WritableKeyPath<Root, Value> {
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
