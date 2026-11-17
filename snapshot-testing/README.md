# Snapshot testing foundation

This unpublished module is a small foundation for sharing snapshot comparison between compiler and
editor tests. Its working module name is `snapshot-testing`; no separate library or final name has
been chosen.

`Snapshotting<Value, Format>` captures a value and delegates comparison to `Diffing<Format>`.
`pullback` adapts a strategy to a new value type. `compareSnapshot` returns a structured mismatch
without depending on JUnit or a particular test runner. The text strategy compares exact strings;
formatting and normalization are the responsibility of each integration.

The compiler fixture uses this foundation for inline expectation comparison and recording decisions;
the existing compiler-specific recorder is unchanged. Editor assertions use it for opt-in text
snapshots of captured diagnostics while retaining semantic reference assertions. Inline recording,
version selection, richer diffs, annotations, and a general snapshot assertion are not provided by
this module yet.

The strategy and inline-testing separation is inspired by Point-Free's
[SnapshotTesting](https://github.com/pointfreeco/swift-snapshot-testing) and
[MacroTesting](https://github.com/pointfreeco/swift-macro-testing).
