package dev.kpaths.core

/** Type-erased metadata common to all property key paths. */
public interface AnyKeyPath {
  public val id: PathId
  public val valueType: String
}

/** A key path with a known root type and an erased focused value. */
public interface PartialKeyPath<Root> : AnyKeyPath {
  public fun matchAny(root: Root): Match<Any?>
}

/** A total, typed read path. */
public interface KeyPath<Root, Value> : PartialKeyPath<Root> {
  public fun get(root: Root): Value

  override fun matchAny(root: Root): Match<Any?> = Match.Found(get(root))
}

/** A typed property path that replaces a focus and returns the resulting root. */
public interface WritableKeyPath<Root, Value> : KeyPath<Root, Value> {
  public fun set(root: Root, value: Value): Root

  public fun modify(root: Root, update: (Value) -> Value): Root =
    set(root, update(get(root)))
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
