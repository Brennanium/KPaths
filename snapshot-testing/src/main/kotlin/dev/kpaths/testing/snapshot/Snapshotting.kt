package dev.kpaths.testing.snapshot

/** Compares snapshots of a particular format. A null message means that they match. */
public class Diffing<Format>(
  public val difference: (expected: Format, actual: Format) -> String?,
)

/** Captures a value in a format that can be compared independently of its origin. */
public class Snapshotting<Value, Format>(
  public val diffing: Diffing<Format>,
  public val snapshot: (Value) -> Format,
) {
  /** Adapts this strategy to a new input while retaining its comparison behavior. */
  public fun <NewValue> pullback(transform: (NewValue) -> Value): Snapshotting<NewValue, Format> =
    Snapshotting(diffing) { value -> snapshot(transform(value)) }
}

public data class SnapshotDifference<Format>(
  val expected: Format,
  val actual: Format,
  val message: String,
)

/** Captures exactly once and returns a structured mismatch, or null when values match. */
public fun <Value, Format> compareSnapshot(
  value: Value,
  expected: Format,
  using: Snapshotting<Value, Format>,
): SnapshotDifference<Format>? {
  val actual = using.snapshot(value)
  val message = using.diffing.difference(expected, actual) ?: return null
  return SnapshotDifference(expected, actual, message)
}

/** Exact text comparison; callers choose how to normalize and render their output. */
public val textSnapshotting: Snapshotting<String, String> = Snapshotting(
  diffing = Diffing { expected, actual -> if (expected == actual) null else "Text snapshots differ" },
  snapshot = { it },
)
