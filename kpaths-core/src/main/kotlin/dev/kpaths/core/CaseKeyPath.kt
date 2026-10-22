package dev.kpaths.core

import kotlin.reflect.KProperty1

/** Type-erased metadata common to all case key paths. */
public interface AnyCaseKeyPath : AnyPath

/** The exact outcome of attempting to match a case path against a root value. */
public sealed interface CaseMatch<out Value> {
  /** The root was not the case represented by this path. */
  public data object NoMatch : CaseMatch<Nothing>

  /** The root matched, including when [value] itself is `null`. */
  public data class Matched<out Value>(public val value: Value) : CaseMatch<Value>
}

/** Intentionally collapses [CaseMatch.NoMatch] and [CaseMatch.Matched] with a null value. */
public val <Value> CaseMatch<Value>.valueOrNull: Value?
  get() = when (this) {
    CaseMatch.NoMatch -> null
    is CaseMatch.Matched -> value
  }

/** Kotlin-style function spelling of [valueOrNull]. */
public fun <Value> CaseMatch<Value>.getOrNull(): Value? = valueOrNull

/** Transforms a matched value while retaining an unmatched result. */
public inline fun <Value, NewValue> CaseMatch<Value>.map(
  transform: (Value) -> NewValue,
): CaseMatch<NewValue> = when (this) {
  CaseMatch.NoMatch -> CaseMatch.NoMatch
  is CaseMatch.Matched -> CaseMatch.Matched(transform(value))
}

/** Chains a second match operation only after this result matched. */
public inline fun <Value, NewValue> CaseMatch<Value>.flatMap(
  transform: (Value) -> CaseMatch<NewValue>,
): CaseMatch<NewValue> = when (this) {
  CaseMatch.NoMatch -> CaseMatch.NoMatch
  is CaseMatch.Matched -> transform(value)
}

/** Folds the exact matching result into an arbitrary value. */
public inline fun <Value, Result> CaseMatch<Value>.fold(
  onNoMatch: () -> Result,
  onMatched: (Value) -> Result,
): Result = when (this) {
  CaseMatch.NoMatch -> onNoMatch()
  is CaseMatch.Matched -> onMatched(value)
}

/** Intentionally treats a matched null value as another nonmatch. */
public fun <Value : Any> CaseMatch<Value?>.requiringValue(): CaseMatch<Value> = when (this) {
  CaseMatch.NoMatch -> CaseMatch.NoMatch
  is CaseMatch.Matched -> value?.let { CaseMatch.Matched<Value>(it) } ?: CaseMatch.NoMatch
}

/** A type-erased-value case path with a known root. */
public interface PartialCaseKeyPath<Root> : AnyCaseKeyPath {
  /** Matches while retaining the distinction between an unmatched root and a matched null. */
  public fun matchAny(root: Root): CaseMatch<Any?>

  /** Legacy, intentionally lossy extraction for type-erased consumers. */
  public fun extractAny(root: Root): Any? = matchAny(root).valueOrNull

  /** Returns true whenever the represented case matched, including for a matched null payload. */
  public fun matches(root: Root): Boolean = matchAny(root) is CaseMatch.Matched
}

/**
 * The exact, typed route through one variant of a sealed hierarchy.
 *
 * [match] distinguishes a different case from a matching case whose focus is `null`. [embed]
 * always constructs the variant. [replaceIfMatching] deliberately leaves a nonmatching root
 * unchanged; callers choose explicitly between construction and replacement.
 */
public interface CasePath<Root, Value> : PartialCaseKeyPath<Root> {
  public fun match(root: Root): CaseMatch<Value>
  public fun embed(value: Value): Root

  override fun matchAny(root: Root): CaseMatch<Any?> = match(root)

  public fun replaceIfMatching(root: Root, value: Value): Root =
    if (match(root) is CaseMatch.NoMatch) root else embed(value)

  public fun modify(root: Root, update: (Value) -> Value): Root =
    when (val result = match(root)) {
      CaseMatch.NoMatch -> root
      is CaseMatch.Matched -> embed(update(result.value))
    }
}

/**
 * A [CasePath] whose focus can never be null.
 *
 * This convenience specialization supports the familiar nullable [extract] projection, since a
 * `null` result can unambiguously mean that the root did not match the case.
 */
public interface CaseKeyPath<Root, Value : Any> : CasePath<Root, Value> {
  /** Returns this path's concrete case value, or `null` when [root] is another case. */
  public fun extract(root: Root): Value? = match(root).valueOrNull
}

/**
 * Appends exact case paths while retaining `NoMatch` versus `Matched(null)` semantics.
 *
 * [Middle] must be non-null because the next path needs an actual root to inspect. A nullable
 * matched focus can be handled explicitly with [CaseMatch.requiringValue] before continuing.
 */
public fun <Root, Middle : Any, Value> CasePath<Root, Middle>.appending(
  next: CasePath<Middle, Value>,
): CasePath<Root, Value> {
  val outer = this
  return casePath(
    id = outer.__kpaths_id.appending(next.__kpaths_id),
    valueType = next.__kpaths_valueType,
    match = { root -> outer.match(root).flatMap(next::match) },
    embed = { value -> outer.embed(next.embed(value)) },
  )
}

/** Kotlin-friendly spelling of exact [appending]. */
public fun <Root, Middle : Any, Value> CasePath<Root, Middle>.then(
  next: CasePath<Middle, Value>,
): CasePath<Root, Value> = appending(next)

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

/** Base class for generated exact case paths with structural path identity. */
public abstract class CasePathBase<Root, Value> : PathBase(), CasePath<Root, Value>

/** Base class for generated non-null case paths with extraction conveniences. */
public abstract class CaseKeyPathBase<Root, Value : Any> :
  CasePathBase<Root, Value>(),
  CaseKeyPath<Root, Value>

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

/** Creates an exact case path, including when its focus type is nullable. */
public fun <Root, Value> casePath(
  id: PathId,
  valueType: TypeId,
  match: (Root) -> CaseMatch<Value>,
  embed: (Value) -> Root,
): CasePath<Root, Value> {
  val matcher = match
  val embedder = embed
  return object : CasePathBase<Root, Value>() {
    override val __kpaths_id: PathId = id
    override val __kpaths_valueType: TypeId = valueType

    override fun match(root: Root): CaseMatch<Value> = matcher(root)

    override fun embed(value: Value): Root = embedder(value)
  }
}

/** Creates an exact case path while capturing its complete focus type token at the call site. */
public inline fun <reified Root, reified Value> casePath(
  id: PathId,
  noinline match: (Root) -> CaseMatch<Value>,
  noinline embed: (Value) -> Root,
): CasePath<Root, Value> = casePath(id, TypeId.of<Value>(), match, embed)

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

    override fun match(root: Root): CaseMatch<Value> =
      extractor(root)?.let { CaseMatch.Matched<Value>(it) } ?: CaseMatch.NoMatch

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

/** Creates an exact case path from one case payload property and its constructor reference. */
public inline fun <reified Root : Any, reified Case : Root, reified Value> casePath(
  extract: KProperty1<Case, Value>,
  noinline embed: (Value) -> Case,
): CasePath<Root, Value> {
  val outer = caseKeyPath<Root, Case>()
  val property = keyPath(extract)
  val constructCase = embed
  return casePath(
    id = outer.__kpaths_id.appending(property.__kpaths_id),
    valueType = property.__kpaths_valueType,
    match = { root -> outer.match(root).map { case -> property.get(case) } },
    embed = { value -> outer.embed(constructCase(value)) },
  )
}

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
