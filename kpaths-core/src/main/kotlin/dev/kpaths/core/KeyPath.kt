package dev.kpaths.core

/** Metadata shared by all generated paths, independent of their focusing capability. */
public interface AnyPath {
  public val id: PathId
  public val valueType: String
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
    other is AnyPath && id == other.id && valueType == other.valueType

  final override fun hashCode(): Int = 31 * id.hashCode() + valueType.hashCode()

  override fun toString(): String = "${this::class.simpleName}(id=$id, valueType=$valueType)"
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
