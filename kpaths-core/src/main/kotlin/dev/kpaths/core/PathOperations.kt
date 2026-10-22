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
 * Extracts [caseKeyPath]'s concrete case value from this root.
 *
 * The `operator` modifier also makes `root[caseKeyPath]` available. Generated case paths focus
 * non-null concrete case objects, so `null` unambiguously means that this root is another case.
 */
public operator fun <Root, Value : Any> Root.get(caseKeyPath: CaseKeyPath<Root, Value>): Value? =
  caseKeyPath.extract(this)

/**
 * Exactly matches [casePath] against this root.
 *
 * The `operator` modifier makes `root[casePath]` available. Unlike the [CaseKeyPath] overload,
 * this returns [CaseMatch] so a matched null focus remains distinguishable from another case.
 */
public operator fun <Root, Value> Root.get(casePath: CasePath<Root, Value>): CaseMatch<Value> =
  casePath.match(this)

/** Named spelling of exact case matching. */
public fun <Root, Value> Root.match(casePath: CasePath<Root, Value>): CaseMatch<Value> =
  casePath.match(this)

/** Returns whether [casePath] matched this root, including when its matched focus is null. */
public fun <Root, Value> Root.caseMatches(casePath: CasePath<Root, Value>): Boolean =
  casePath.match(this) is CaseMatch.Matched

/** Intentionally collapses an unmatched root and a matched null focus. */
public fun <Root, Value> Root.getOrNull(casePath: CasePath<Root, Value>): Value? =
  casePath.match(this).valueOrNull

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
