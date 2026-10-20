package dev.kpaths.core

/** Type-erased metadata common to all case key paths. */
public interface AnyCaseKeyPath {
  public val id: PathId
  public val valueType: String
}

/** A type-erased-value case path with a known root. */
public interface PartialCaseKeyPath<Root> : AnyCaseKeyPath {
  public fun matches(root: Root): Boolean
}

/**
 * A stable, typed route through one variant of a sealed hierarchy.
 *
 * [embed] always constructs the variant. [replaceIfMatching] deliberately leaves a nonmatching
 * root unchanged; callers choose explicitly between construction and replacement.
 */
public interface CaseKeyPath<Root, Value> : PartialCaseKeyPath<Root> {
  public fun extract(root: Root): Match<Value>
  public fun embed(value: Value): Root

  override fun matches(root: Root): Boolean = extract(root) is Match.Found

  public fun replaceIfMatching(root: Root, value: Value): Root =
    when (extract(root)) {
      Match.Missing -> root
      is Match.Found -> embed(value)
    }

  public fun modify(root: Root, update: (Value) -> Value): Root =
    when (val match = extract(root)) {
      Match.Missing -> root
      is Match.Found -> embed(update(match.value))
    }
}
