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
public interface CaseKeyPath<Root, Value : Any> : PartialCaseKeyPath<Root> {
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
public abstract class CaseKeyPathBase<Root, Value : Any> : PathBase(), CaseKeyPath<Root, Value>

/**
 * Creates a case path with an explicitly supplied structural identity and focus type token.
 *
 * [extract] must return `null` only when the root does not match. A case focus itself is always
 * non-null, which preserves the distinction between a failed match and nullable data held by a
 * matched case object.
 */
public fun <Root, Value : Any> caseKeyPath(
  id: PathId,
  valueType: TypeId,
  extract: (Root) -> Value?,
  embed: (Value) -> Root,
): CaseKeyPath<Root, Value> {
  val extractor = extract
  val embedder = embed
  return object : CaseKeyPathBase<Root, Value>() {
    override val id: PathId = id
    override val valueType: TypeId = valueType

    override fun extract(root: Root): Value? = extractor(root)

    override fun embed(value: Value): Root = embedder(value)
  }
}

/**
 * Creates a case path while capturing its complete focus type token at the call site.
 *
 * Its root type is part of the caller-supplied [PathId].
 */
public inline fun <reified Root, reified Value : Any> caseKeyPath(
  id: PathId,
  noinline extract: (Root) -> Value?,
  noinline embed: (Value) -> Root,
): CaseKeyPath<Root, Value> = caseKeyPath(id, TypeId.of<Value>(), extract, embed)

/**
 * Creates the ordinary case path for a concrete subtype of a sealed root.
 *
 * The subtype itself is the focus, so extraction is a safe cast and embedding is an upcast. This
 * matches generated `@Pathable` case paths for an identically named declared case.
 */
public inline fun <reified Root : Any, reified Case : Root> caseKeyPath(): CaseKeyPath<Root, Case> {
  val caseName = requireNotNull(Case::class.simpleName) {
    "Local and anonymous classes cannot form stable case path names. Use the explicit caseKeyPath factory."
  }
  return caseKeyPath(
    id = PathId.case(TypeId.of<Root>(), caseName),
    valueType = TypeId.of<Case>(),
    extract = { root -> root as? Case },
    embed = { case -> case },
  )
}

/**
 * Creates the case path for one [Enum] entry. The focus is [Unit], because enum entries carry no
 * distinct associated-value type in Kotlin.
 */
public inline fun <reified Root> caseKeyPath(
  case: Root,
): CaseKeyPath<Root, Unit>
  where Root : Enum<Root> {
  val entry = case
  return caseKeyPath(
    id = PathId.case(TypeId.of<Root>(), entry.name),
    valueType = TypeId.of<Unit>(),
    extract = { root -> if (root == entry) Unit else null },
    embed = { entry },
  )
}
