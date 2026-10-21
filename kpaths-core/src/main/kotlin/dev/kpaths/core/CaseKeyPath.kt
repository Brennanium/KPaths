package dev.kpaths.core

/** Type-erased metadata common to all case key paths. */
public interface AnyCaseKeyPath : AnyPath

/** A type-erased-value case path with a known root. */
public interface PartialCaseKeyPath<Root> : AnyCaseKeyPath {
  public fun extractAny(root: Root): Any?

  public fun matches(root: Root): Boolean = extractAny(root) != null
}

/**
 * A stable, typed route through one variant of a sealed hierarchy.
 *
 * [embed] always constructs the variant. [replaceIfMatching] deliberately leaves a nonmatching
 * root unchanged; callers choose explicitly between construction and replacement.
 */
public interface CaseKeyPath<Root, Value> : PartialCaseKeyPath<Root> {
  /** Returns this path's concrete case value, or `null` when [root] is another case. */
  public fun extract(root: Root): Value?
  public fun embed(value: Value): Root

  override fun extractAny(root: Root): Any? = extract(root)

  public fun replaceIfMatching(root: Root, value: Value): Root =
    if (extract(root) == null) root else embed(value)

  public fun modify(root: Root, update: (Value) -> Value): Root =
    extract(root)?.let { embed(update(it)) } ?: root
}

/** Base class for generated case paths with the same structural identity rules as key paths. */
public abstract class CaseKeyPathBase<Root, Value> : PathBase(), CaseKeyPath<Root, Value>
