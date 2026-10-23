package dev.kpaths.core

import kotlin.reflect.KProperty1

/** Type-erased metadata common to all case paths. */
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
 * A conditional, typed read route through one case of a sealed hierarchy.
 *
 * A case path is also its lossy Kotlin function view, from [Root] to `Value?`. It can therefore
 * be passed directly to APIs such as `mapNotNull`. Use [match] rather than invocation when a
 * matched null must remain distinguishable from a nonmatching root.
 */
public interface CaseKeyPath<Root, Value> : PartialCaseKeyPath<Root>, (Root) -> Value? {
  /** Returns the exact result of attempting to match [root]. */
  public fun match(root: Root): CaseMatch<Value>

  /** A lossy convenience projection; use [match] when a matched null must remain distinguishable. */
  public fun extract(root: Root): Value? = match(root).valueOrNull

  /** Embeds [value] in a newly constructed root, independent of any existing root value. */
  public fun embed(value: Value): Root

  /** Invokes this path as its lossy extraction function. */
  override operator fun invoke(root: Root): Value? = extract(root)

  override fun matchAny(root: Root): CaseMatch<Any?> = match(root)
}

/** Appends two case paths, retaining independent embedding through both cases. */
public fun <Root, Middle, Value> CaseKeyPath<Root, Middle>.appending(
  next: CaseKeyPath<Middle, Value>,
): CaseKeyPath<Root, Value> {
  val outer = this
  return caseKeyPath(
    id = outer.__kpaths_id.appending(next.__kpaths_id),
    valueType = next.__kpaths_valueType,
    match = { root -> outer.match(root).flatMap(next::match) },
    embed = { value -> outer.embed(next.embed(value)) },
  )
}

/** Kotlin-friendly spelling of case-path [appending]. */
public fun <Root, Middle, Value> CaseKeyPath<Root, Middle>.then(
  next: CaseKeyPath<Middle, Value>,
): CaseKeyPath<Root, Value> = appending(next)

/** Base class for generated read-only case paths with structural path identity. */
public abstract class CaseKeyPathBase<Root, Value> : PathBase(), CaseKeyPath<Root, Value>

/** Views a non-null case path as a total, read-only key path whose focus is nullable. */
public fun <Root, Value : Any> CaseKeyPath<Root, Value>.matching(): KeyPath<Root, Value?> {
  val casePath = this
  return object : KeyPathBase<Root, Value?>() {
    override val __kpaths_id: PathId = casePath.__kpaths_id
    override val __kpaths_valueType: TypeId = casePath.__kpaths_valueType.withNullability(true)

    override fun get(root: Root): Value? = casePath.extract(root)
  }
}

/** Creates a read-only exact case path. */
public fun <Root, Value> caseKeyPath(
  id: PathId,
  valueType: TypeId,
  match: (Root) -> CaseMatch<Value>,
  embed: (Value) -> Root,
): CaseKeyPath<Root, Value> {
  val matcher = match
  val embedder = embed
  return object : CaseKeyPathBase<Root, Value>() {
    override val __kpaths_id: PathId = id
    override val __kpaths_valueType: TypeId = valueType

    override fun match(root: Root): CaseMatch<Value> = matcher(root)
    override fun embed(value: Value): Root = embedder(value)
  }
}

/** Creates a read-only exact case path while capturing its complete focus type token. */
public inline fun <reified Root, reified Value> caseKeyPath(
  id: PathId,
  noinline match: (Root) -> CaseMatch<Value>,
  noinline embed: (Value) -> Root,
): CaseKeyPath<Root, Value> = caseKeyPath(id, TypeId.of<Value>(), match, embed)

/** Creates the ordinary writable whole-case path for a concrete subtype of a sealed root. */
public inline fun <reified Root : Any, reified Case : Root> caseKeyPath(): CaseKeyPath<Root, Case> {
  val caseName = requireNotNull(Case::class.simpleName) {
    "Local and anonymous classes cannot form stable case path names. Use a manual writableCaseKeyPath factory."
  }
  return caseKeyPath(
    id = PathId.case(TypeId.of<Root>(), caseName),
    valueType = TypeId.of<Case>(),
    match = { root -> (root as? Case)?.let { CaseMatch.Matched(it) } ?: CaseMatch.NoMatch },
    embed = { case -> case },
  )
}

/** Creates a writable whole-case path for one [Enum] entry, focused as [Unit]. */
public inline fun <reified Root> caseKeyPath(
  case: Root,
): CaseKeyPath<Root, Unit>
  where Root : Enum<Root> {
  val entry = case
  return caseKeyPath(
    id = PathId.case(TypeId.of<Root>(), entry.name),
    valueType = TypeId.of<Unit>(),
    match = { root -> if (root == entry) CaseMatch.Matched(Unit) else CaseMatch.NoMatch },
    embed = { entry },
  )
}

/** Creates an independently embeddable path for one case payload property. */
public inline fun <reified Root : Any, reified Case : Root, reified Value> caseKeyPath(
  extract: KProperty1<Case, Value>,
  noinline embed: (Value) -> Case,
): CaseKeyPath<Root, Value> = caseKeyPath(
  id = PathId.case(TypeId.of<Root>(), requireNotNull(Case::class.simpleName))
    .appending(PathSegment.Property(extract.name)),
  valueType = TypeId.of<Value>(),
  match = { root -> (root as? Case)?.let { CaseMatch.Matched(extract.get(it)) } ?: CaseMatch.NoMatch },
  embed = embed,
)

/** Creates a writable path whose focus is an ordered [Pair] of two case properties. */
public inline fun <reified Root : Any, reified Case : Root, reified First, reified Second> caseKeyPath(
  extract1: KProperty1<Case, First>,
  extract2: KProperty1<Case, Second>,
  noinline embed: (First, Second) -> Case,
): CaseKeyPath<Root, Pair<First, Second>> {
  return caseKeyPath(
    id = PathId.case(TypeId.of<Root>(), requireNotNull(Case::class.simpleName))
      .appending(PathSegment.Property(extract1.name))
      .appending(PathSegment.Property(extract2.name)),
    valueType = TypeId.of<Pair<First, Second>>(),
    match = { root -> (root as? Case)?.let { CaseMatch.Matched(Pair(extract1.get(it), extract2.get(it))) } ?: CaseMatch.NoMatch },
    embed = { value -> embed(value.first, value.second) },
  )
}

/** Creates a writable path whose focus is an ordered [Triple] of three case properties. */
public inline fun <reified Root : Any, reified Case : Root, reified First, reified Second, reified Third> caseKeyPath(
  extract1: KProperty1<Case, First>,
  extract2: KProperty1<Case, Second>,
  extract3: KProperty1<Case, Third>,
  noinline embed: (First, Second, Third) -> Case,
): CaseKeyPath<Root, Triple<First, Second, Third>> {
  return caseKeyPath(
    id = PathId.case(TypeId.of<Root>(), requireNotNull(Case::class.simpleName))
      .appending(PathSegment.Property(extract1.name))
      .appending(PathSegment.Property(extract2.name))
      .appending(PathSegment.Property(extract3.name)),
    valueType = TypeId.of<Triple<First, Second, Third>>(),
    match = { root -> (root as? Case)?.let { CaseMatch.Matched(Triple(extract1.get(it), extract2.get(it), extract3.get(it))) } ?: CaseMatch.NoMatch },
    embed = { value -> embed(value.first, value.second, value.third) },
  )
}
