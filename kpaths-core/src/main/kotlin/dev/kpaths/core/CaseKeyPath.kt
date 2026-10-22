package dev.kpaths.core

import kotlin.reflect.KMutableProperty1
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

/** A conditional, typed read route through one case of a sealed hierarchy. */
public interface CaseKeyPath<Root, Value> : PartialCaseKeyPath<Root> {
  /** Returns the exact result of attempting to match [root]. */
  public fun match(root: Root): CaseMatch<Value>

  /** A lossy convenience projection; use [match] when a matched null must remain distinguishable. */
  public fun extract(root: Root): Value? = match(root).valueOrNull

  override fun matchAny(root: Root): CaseMatch<Any?> = match(root)
}

/** A [CaseKeyPath] that can conditionally replace its matched focus. */
public interface WritableCaseKeyPath<Root, Value> : CaseKeyPath<Root, Value> {
  /** Replaces a matching focus and otherwise returns [root] unchanged. */
  public fun embed(root: Root, value: Value): Root

  /** Updates a matching focus and otherwise returns [root] unchanged. */
  public fun modify(root: Root, update: (Value) -> Value): Root =
    when (val result = match(root)) {
      CaseMatch.NoMatch -> root
      is CaseMatch.Matched -> embed(root, update(result.value))
    }
}

/** Appends two writable case paths. */
public fun <Root, Middle : Any, Value> WritableCaseKeyPath<Root, Middle>.appending(
  next: WritableCaseKeyPath<Middle, Value>,
): WritableCaseKeyPath<Root, Value> {
  val outer = this
  return writableCaseKeyPath(
    id = outer.__kpaths_id.appending(next.__kpaths_id),
    valueType = next.__kpaths_valueType,
    match = { root -> outer.match(root).flatMap(next::match) },
    embed = { root, value ->
      when (val outerResult = outer.match(root)) {
        CaseMatch.NoMatch -> root
        is CaseMatch.Matched -> when (next.match(outerResult.value)) {
          CaseMatch.NoMatch -> root
          is CaseMatch.Matched -> outer.embed(root, next.embed(outerResult.value, value))
        }
      }
    },
  )
}

/** Kotlin-friendly spelling of writable case-path [appending]. */
public fun <Root, Middle : Any, Value> WritableCaseKeyPath<Root, Middle>.then(
  next: WritableCaseKeyPath<Middle, Value>,
): WritableCaseKeyPath<Root, Value> = appending(next)

/** Follows a matched concrete case through a value- or reference-writable property path. */
public fun <Root, Case : Any, Value> WritableCaseKeyPath<Root, Case>.appending(
  next: WritableKeyPath<Case, Value>,
): WritableCaseKeyPath<Root, Value> {
  val outer = this
  return writableCaseKeyPath(
    id = outer.__kpaths_id.appending(next.__kpaths_id),
    valueType = next.__kpaths_valueType,
    match = { root -> outer.match(root).map(next::get) },
    embed = { root, value ->
      when (val result = outer.match(root)) {
        CaseMatch.NoMatch -> root
        is CaseMatch.Matched -> outer.embed(root, next.set(result.value, value))
      }
    },
  )
}

/** Kotlin-friendly spelling of writable-property [appending]. */
public fun <Root, Case : Any, Value> WritableCaseKeyPath<Root, Case>.then(
  next: WritableKeyPath<Case, Value>,
): WritableCaseKeyPath<Root, Value> = appending(next)

/**
 * Follows a matched case through a mutable Kotlin property while preserving value semantics.
 *
 * [copy] must return an independent replacement case. The property is updated on that copy, and
 * the outer path then conditionally puts the replacement case back into the root.
 */
public inline fun <Root, reified Case : Any, reified Value> WritableCaseKeyPath<Root, Case>.then(
  property: KMutableProperty1<Case, Value>,
  noinline copy: (Case) -> Case,
): WritableCaseKeyPath<Root, Value> = then(
  get = property,
  set = { case, value -> copy(case).also { replacement -> property.set(replacement, value) } },
)

/**
 * Follows a matched case through a property while supplying its value-semantic replacement.
 *
 * This supports a `val` property: [set] returns a revised concrete case, normally through
 * `copy`, and the outer case path puts that case back into the root.
 */
public inline fun <Root, reified Case : Any, reified Value> WritableCaseKeyPath<Root, Case>.then(
  get: KProperty1<Case, Value>,
  noinline set: (Case, Value) -> Case,
): WritableCaseKeyPath<Root, Value> = then(writableKeyPath(get, set))

/** Base class for generated read-only case paths with structural path identity. */
public abstract class CaseKeyPathBase<Root, Value> : PathBase(), CaseKeyPath<Root, Value>

/** Base class for generated root-aware writable case paths. */
public abstract class WritableCaseKeyPathBase<Root, Value> :
  CaseKeyPathBase<Root, Value>(),
  WritableCaseKeyPath<Root, Value>

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
): CaseKeyPath<Root, Value> {
  val matcher = match
  return object : CaseKeyPathBase<Root, Value>() {
    override val __kpaths_id: PathId = id
    override val __kpaths_valueType: TypeId = valueType

    override fun match(root: Root): CaseMatch<Value> = matcher(root)
  }
}

/** Creates a read-only exact case path while capturing its complete focus type token. */
public inline fun <reified Root, reified Value> caseKeyPath(
  id: PathId,
  noinline match: (Root) -> CaseMatch<Value>,
): CaseKeyPath<Root, Value> = caseKeyPath(id, TypeId.of<Value>(), match)

/** Creates a root-aware writable case path. */
public fun <Root, Value> writableCaseKeyPath(
  id: PathId,
  valueType: TypeId,
  match: (Root) -> CaseMatch<Value>,
  embed: (Root, Value) -> Root,
): WritableCaseKeyPath<Root, Value> {
  val matcher = match
  val embedder = embed
  return object : WritableCaseKeyPathBase<Root, Value>() {
    override val __kpaths_id: PathId = id
    override val __kpaths_valueType: TypeId = valueType

    override fun match(root: Root): CaseMatch<Value> = matcher(root)

    override fun embed(root: Root, value: Value): Root =
      if (matcher(root) is CaseMatch.NoMatch) root else embedder(root, value)
  }
}

/** Creates a root-aware writable case path while capturing its complete focus type token. */
public inline fun <reified Root, reified Value> writableCaseKeyPath(
  id: PathId,
  noinline match: (Root) -> CaseMatch<Value>,
  noinline embed: (Root, Value) -> Root,
): WritableCaseKeyPath<Root, Value> = writableCaseKeyPath(id, TypeId.of<Value>(), match, embed)

/** Creates the ordinary writable whole-case path for a concrete subtype of a sealed root. */
public inline fun <reified Root : Any, reified Case : Root> caseKeyPath(): WritableCaseKeyPath<Root, Case> {
  val caseName = requireNotNull(Case::class.simpleName) {
    "Local and anonymous classes cannot form stable case path names. Use a manual writableCaseKeyPath factory."
  }
  return writableCaseKeyPath(
    id = PathId.case(TypeId.of<Root>(), caseName),
    valueType = TypeId.of<Case>(),
    match = { root -> (root as? Case)?.let { CaseMatch.Matched(it) } ?: CaseMatch.NoMatch },
    embed = { _, case -> case },
  )
}

/** Creates a writable whole-case path for one [Enum] entry, focused as [Unit]. */
public inline fun <reified Root> caseKeyPath(
  case: Root,
): WritableCaseKeyPath<Root, Unit>
  where Root : Enum<Root> {
  val entry = case
  return writableCaseKeyPath(
    id = PathId.case(TypeId.of<Root>(), entry.name),
    valueType = TypeId.of<Unit>(),
    match = { root -> if (root == entry) CaseMatch.Matched(Unit) else CaseMatch.NoMatch },
    embed = { _, _ -> entry },
  )
}

/**
 * Creates a writable path for one case property and a constructor reference.
 *
 * Prefer a whole-case path followed by [then] when a property update should retain other fields.
 */
public inline fun <reified Root : Any, reified Case : Root, reified Value> caseKeyPath(
  extract: KProperty1<Case, Value>,
  noinline embed: (Value) -> Case,
): WritableCaseKeyPath<Root, Value> = caseKeyPath<Root, Case>().then(
  get = extract,
  set = { _, value -> embed(value) },
)

/** Creates a writable path whose focus is an ordered [Pair] of two case properties. */
public inline fun <reified Root : Any, reified Case : Root, reified First, reified Second> caseKeyPath(
  extract1: KProperty1<Case, First>,
  extract2: KProperty1<Case, Second>,
  noinline embed: (First, Second) -> Case,
): WritableCaseKeyPath<Root, Pair<First, Second>> {
  val outer = caseKeyPath<Root, Case>()
  val first = keyPath(extract1)
  val second = keyPath(extract2)
  return writableCaseKeyPath(
    id = outer.__kpaths_id.appending(first.__kpaths_id).appending(second.__kpaths_id),
    valueType = TypeId.of<Pair<First, Second>>(),
    match = { root -> outer.match(root).map { case -> Pair(first.get(case), second.get(case)) } },
    embed = { root, value -> outer.embed(root, embed(value.first, value.second)) },
  )
}

/** Creates a writable path whose focus is an ordered [Triple] of three case properties. */
public inline fun <reified Root : Any, reified Case : Root, reified First, reified Second, reified Third> caseKeyPath(
  extract1: KProperty1<Case, First>,
  extract2: KProperty1<Case, Second>,
  extract3: KProperty1<Case, Third>,
  noinline embed: (First, Second, Third) -> Case,
): WritableCaseKeyPath<Root, Triple<First, Second, Third>> {
  val outer = caseKeyPath<Root, Case>()
  val first = keyPath(extract1)
  val second = keyPath(extract2)
  val third = keyPath(extract3)
  return writableCaseKeyPath(
    id = outer.__kpaths_id
      .appending(first.__kpaths_id)
      .appending(second.__kpaths_id)
      .appending(third.__kpaths_id),
    valueType = TypeId.of<Triple<First, Second, Third>>(),
    match = { root -> outer.match(root).map { case -> Triple(first.get(case), second.get(case), third.get(case)) } },
    embed = { root, value -> outer.embed(root, embed(value.first, value.second, value.third)) },
  )
}
