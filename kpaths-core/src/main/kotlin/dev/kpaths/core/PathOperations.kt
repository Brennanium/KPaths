package dev.kpaths.core

/**
 * Reads [keyPath] from this root.
 *
 * The `operator` modifier also makes `root[keyPath]` available. The named form is useful when
 * mirroring Swift's explicit `keyPath:` call-site label:
 * `root.get(keyPath = MyTypePaths.property)`.
 */
public operator fun <Root, Value> Root.get(keyPath: KeyPath<Root, Value>): Value =
  keyPath.get(this)

/**
 * Replaces [keyPath]'s focus and returns the resulting root.
 *
 * For a value-style data-class path, the result is a copy and must be assigned by the caller.
 * For a reference-writable path, the returned value is the same, now-mutated instance.
 */
public fun <Root, Value> Root.set(
  keyPath: WritableKeyPath<Root, Value>,
  value: Value,
): Root = keyPath.set(this, value)

/** Kotlin-friendly semantic spelling of [set] for value-style updates. */
public fun <Root, Value> Root.setting(
  keyPath: WritableKeyPath<Root, Value>,
  value: Value,
): Root = set(keyPath = keyPath, value = value)

/** Transforms [keyPath]'s focus and returns the resulting root. */
public fun <Root, Value> Root.modifying(
  keyPath: WritableKeyPath<Root, Value>,
  update: (Value) -> Value,
): Root = keyPath.modify(this, update)

/**
 * In-place assignment shorthand for a reference-writable path.
 *
 * Its parameter is deliberately named `path`, rather than `keyPath`: an explicit
 * `root.set(keyPath = ..., value = ...)` always selects the copy-returning [set] overload,
 * whereas `root[referencePath] = value` selects this Kotlin operator convention.
 */
public operator fun <Root : Any, Value> Root.set(
  path: ReferenceWritableKeyPath<Root, Value>,
  value: Value,
) {
  path.setInPlace(this, value)
}
