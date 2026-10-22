package dev.kpaths.core

import kotlin.reflect.KProperty1

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

/**
 * Appends two case paths, retaining full extract-and-embed behavior.
 *
 * This is the prism equivalent of Swift CasePaths' nested case-path composition: the inner path
 * constructs its intermediate case, which the outer path can in turn construct into the root.
 */
public fun <Root, Middle : Any, Value : Any> CaseKeyPath<Root, Middle>.appending(
  next: CaseKeyPath<Middle, Value>,
): CaseKeyPath<Root, Value> = caseKeyPath(
  id = __kpaths_id.appending(next.__kpaths_id),
  valueType = next.__kpaths_valueType,
  extract = { root -> extract(root)?.let(next::extract) },
  embed = { value -> embed(next.embed(value)) },
)

/** Kotlin-friendly spelling of [appending]. */
public fun <Root, Middle : Any, Value : Any> CaseKeyPath<Root, Middle>.then(
  next: CaseKeyPath<Middle, Value>,
): CaseKeyPath<Root, Value> = appending(next)

/**
 * Appends a property to a case path while supplying the inverse construction for that property.
 *
 * A [KeyPath] alone can read a value from a matched case, but cannot generally reconstruct that
 * case from a replacement value. [embed] supplies that missing construction, which keeps the
 * resulting route a full [CaseKeyPath].
 */
public fun <Root, Case : Any, Value : Any> CaseKeyPath<Root, Case>.appending(
  next: KeyPath<Case, Value>,
  embed: (Value) -> Case,
): CaseKeyPath<Root, Value> {
  val outer = this
  val constructCase = embed
  return caseKeyPath(
    id = outer.__kpaths_id.appending(next.__kpaths_id),
    valueType = next.__kpaths_valueType,
    extract = { root -> outer.extract(root)?.let(next::get) },
    embed = { value -> outer.embed(constructCase(value)) },
  )
}

/** Kotlin-friendly spelling of property [appending]. */
public fun <Root, Case : Any, Value : Any> CaseKeyPath<Root, Case>.then(
  next: KeyPath<Case, Value>,
  embed: (Value) -> Case,
): CaseKeyPath<Root, Value> = appending(next, embed)

/** Base class for generated case paths with the same structural identity rules as key paths. */
public abstract class CaseKeyPathBase<Root, Value : Any> : PathBase(), CaseKeyPath<Root, Value>

/**
 * Views this case path as a total, read-only key path whose focus is nullable.
 *
 * The resulting path returns the matched case value when [Root] has this case and `null` when it
 * does not. It deliberately has no writable counterpart: assigning `null` cannot have a universal
 * meaning for a case path.
 */
public fun <Root, Value : Any> CaseKeyPath<Root, Value>.matching(): KeyPath<Root, Value?> {
  val casePath = this
  return object : KeyPathBase<Root, Value?>() {
    override val __kpaths_id: PathId = casePath.__kpaths_id
    override val __kpaths_valueType: TypeId = casePath.__kpaths_valueType.withNullability(true)

    override fun get(root: Root): Value? = casePath.extract(root)
  }
}

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
    override val __kpaths_id: PathId = id
    override val __kpaths_valueType: TypeId = valueType

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
 * Creates a case path from one case payload property and its constructor reference.
 *
 * [extract] reads the concrete case's payload, while [embed] reconstructs the concrete case
 * before it is embedded into [Root].
 */
public inline fun <reified Root : Any, reified Case : Root, reified Value : Any> caseKeyPath(
  extract: KProperty1<Case, Value>,
  noinline embed: (Value) -> Case,
): CaseKeyPath<Root, Value> = caseKeyPath<Root, Case>().then(keyPath(extract), embed)

/**
 * Creates a lossless case path for a two-value case payload.
 *
 * The focus is an ordered [Pair], whose first and second values are read by [extract1] and
 * [extract2]. [embed] receives those values in the same order to reconstruct the case.
 */
public inline fun <reified Root : Any, reified Case : Root, reified First, reified Second> caseKeyPath(
  extract1: KProperty1<Case, First>,
  extract2: KProperty1<Case, Second>,
  noinline embed: (First, Second) -> Case,
): CaseKeyPath<Root, Pair<First, Second>> {
  val casePath = caseKeyPath<Root, Case>()
  val firstPath = keyPath(extract1)
  val secondPath = keyPath(extract2)
  val constructCase = embed
  return caseKeyPath(
    id = casePath.__kpaths_id.appending(firstPath.__kpaths_id).appending(secondPath.__kpaths_id),
    valueType = TypeId.of<Pair<First, Second>>(),
    extract = { root ->
      casePath.extract(root)?.let { case -> Pair(firstPath.get(case), secondPath.get(case)) }
    },
    embed = { values -> casePath.embed(constructCase(values.first, values.second)) },
  )
}

/**
 * Creates a lossless case path for a three-value case payload.
 *
 * The focus is an ordered [Triple], whose values are read by [extract1], [extract2], and
 * [extract3]. [embed] receives those values in the same order to reconstruct the case.
 */
public inline fun <reified Root : Any, reified Case : Root, reified First, reified Second, reified Third> caseKeyPath(
  extract1: KProperty1<Case, First>,
  extract2: KProperty1<Case, Second>,
  extract3: KProperty1<Case, Third>,
  noinline embed: (First, Second, Third) -> Case,
): CaseKeyPath<Root, Triple<First, Second, Third>> {
  val casePath = caseKeyPath<Root, Case>()
  val firstPath = keyPath(extract1)
  val secondPath = keyPath(extract2)
  val thirdPath = keyPath(extract3)
  val constructCase = embed
  return caseKeyPath(
    id = casePath.__kpaths_id
      .appending(firstPath.__kpaths_id)
      .appending(secondPath.__kpaths_id)
      .appending(thirdPath.__kpaths_id),
    valueType = TypeId.of<Triple<First, Second, Third>>(),
    extract = { root ->
      casePath.extract(root)?.let { case ->
        Triple(firstPath.get(case), secondPath.get(case), thirdPath.get(case))
      }
    },
    embed = { values ->
      casePath.embed(constructCase(values.first, values.second, values.third))
    },
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
