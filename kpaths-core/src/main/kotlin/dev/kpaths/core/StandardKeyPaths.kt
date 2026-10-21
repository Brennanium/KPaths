package dev.kpaths.core

import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1

/** Creates a total read path with an explicitly supplied structural identity and type token. */
public fun <Root, Value> keyPath(
  id: PathId,
  valueType: TypeId,
  get: (Root) -> Value,
): KeyPath<Root, Value> {
  val getter = get
  return object : KeyPathBase<Root, Value>() {
    override val id: PathId = id
    override val valueType: TypeId = valueType

    override fun get(root: Root): Value = getter(root)
  }
}

/**
 * Creates a total read path while capturing its complete focus type token at the call site.
 */
public inline fun <reified Root, reified Value> keyPath(
  id: PathId,
  noinline get: (Root) -> Value,
): KeyPath<Root, Value> = keyPath(id, TypeId.of<Value>(), get)

/**
 * Creates a custom read path with a root-local stable [name].
 *
 * The root's complete type token is captured automatically, so two paths with the same [name]
 * but different root specializations remain distinct. Use the [PathId]-taking overload only when
 * a custom multi-segment identity is necessary.
 */
public inline fun <reified Root, reified Value> keyPath(
  name: String,
  noinline get: (Root) -> Value,
): KeyPath<Root, Value> = keyPath(
  id = PathId.custom(TypeId.of<Root>(), name),
  valueType = TypeId.of<Value>(),
  get = get,
)

/** Creates a copy-based writable path with an explicitly supplied structural identity and type token. */
public fun <Root, Value> writableKeyPath(
  id: PathId,
  valueType: TypeId,
  get: (Root) -> Value,
  set: (Root, Value) -> Root,
): WritableKeyPath<Root, Value> {
  val getter = get
  val setter = set
  return object : WritableKeyPathBase<Root, Value>() {
    override val id: PathId = id
    override val valueType: TypeId = valueType

    override fun get(root: Root): Value = getter(root)

    override fun set(root: Root, value: Value): Root = setter(root, value)
  }
}

/**
 * Creates a copy-based writable path while capturing its complete focus type token at the call
 * site. Its root type is part of the caller-supplied [PathId].
 */
public inline fun <reified Root, reified Value> writableKeyPath(
  id: PathId,
  noinline get: (Root) -> Value,
  noinline set: (Root, Value) -> Root,
): WritableKeyPath<Root, Value> = writableKeyPath(id, TypeId.of<Value>(), get, set)

/**
 * Creates a custom copy-based writable path with a root-local stable [name].
 *
 * The root's complete type token is captured automatically. Use the [PathId]-taking overload
 * only when a custom multi-segment identity is necessary.
 */
public inline fun <reified Root, reified Value> writableKeyPath(
  name: String,
  noinline get: (Root) -> Value,
  noinline set: (Root, Value) -> Root,
): WritableKeyPath<Root, Value> = writableKeyPath(
  id = PathId.custom(TypeId.of<Root>(), name),
  valueType = TypeId.of<Value>(),
  get = get,
  set = set,
)

/** Creates a path whose replacement mutates the supplied root instance. */
public fun <Root : Any, Value> referenceWritableKeyPath(
  id: PathId,
  valueType: TypeId,
  get: (Root) -> Value,
  set: (Root, Value) -> Unit,
): ReferenceWritableKeyPath<Root, Value> {
  val getter = get
  val setter = set
  return object : ReferenceWritableKeyPathBase<Root, Value>() {
    override val id: PathId = id
    override val valueType: TypeId = valueType

    override fun get(root: Root): Value = getter(root)

    override fun setInPlace(root: Root, value: Value) {
      setter(root, value)
    }
  }
}

/**
 * Creates a reference-writable path while capturing its complete focus type token at the call
 * site.
 */
public inline fun <reified Root : Any, reified Value> referenceWritableKeyPath(
  id: PathId,
  noinline get: (Root) -> Value,
  noinline set: (Root, Value) -> Unit,
): ReferenceWritableKeyPath<Root, Value> =
  referenceWritableKeyPath(id, TypeId.of<Value>(), get, set)

/**
 * Creates a custom in-place writable path with a root-local stable [name].
 *
 * The root's complete type token is captured automatically. Use the [PathId]-taking overload
 * only when a custom multi-segment identity is necessary.
 */
public inline fun <reified Root : Any, reified Value> referenceWritableKeyPath(
  name: String,
  noinline get: (Root) -> Value,
  noinline set: (Root, Value) -> Unit,
): ReferenceWritableKeyPath<Root, Value> = referenceWritableKeyPath(
  id = PathId.custom(TypeId.of<Root>(), name),
  valueType = TypeId.of<Value>(),
  get = get,
  set = set,
)

/**
 * Adapts a Kotlin property reference into a read-only path.
 *
 * This intentionally remains read-only even when [property] happens to be a mutable property.
 * Copy-based writes need an explicit replacement function, and in-place writes require the
 * explicit [referenceWritableKeyPath] adapter below.
 */
public fun <Root, Value> keyPath(
  property: KProperty1<Root, Value>,
  id: PathId,
  valueType: TypeId,
): KeyPath<Root, Value> = keyPath(id, valueType, property::get)

/** Adapts a property reference while capturing its complete root and focus type tokens. */
public inline fun <reified Root, reified Value> keyPath(
  property: KProperty1<Root, Value>,
): KeyPath<Root, Value> = keyPath(
  property = property,
  id = PathId.property(TypeId.of<Root>(), property.name),
  valueType = TypeId.of<Value>(),
)

/** Kotlin-friendly property-reference spelling of [keyPath]. */
public inline fun <reified Root, reified Value> KProperty1<Root, Value>.asKeyPath(): KeyPath<Root, Value> =
  keyPath(this)

/** Adapts a mutable Kotlin property reference into an in-place mutable path. */
public fun <Root : Any, Value> referenceWritableKeyPath(
  property: KMutableProperty1<Root, Value>,
  id: PathId,
  valueType: TypeId,
): ReferenceWritableKeyPath<Root, Value> =
  referenceWritableKeyPath(id, valueType, property::get, property::set)

/**
 * Adapts a mutable property reference while capturing its complete root and focus type tokens.
 */
public inline fun <reified Root : Any, reified Value> referenceWritableKeyPath(
  property: KMutableProperty1<Root, Value>,
): ReferenceWritableKeyPath<Root, Value> = referenceWritableKeyPath(
  property = property,
  id = PathId.property(TypeId.of<Root>(), property.name),
  valueType = TypeId.of<Value>(),
)

/** Kotlin-friendly property-reference spelling of [referenceWritableKeyPath]. */
public inline fun <reified Root : Any, reified Value> KMutableProperty1<Root, Value>.asReferenceWritableKeyPath(): ReferenceWritableKeyPath<Root, Value> =
  referenceWritableKeyPath(this)

/**
 * Returns the path that focuses the whole root.
 *
 * Replacing this path's focus is a functional whole-root replacement, so it is always a
 * [WritableKeyPath], including for roots whose individual properties are read-only.
 */
public fun <Root> identityKeyPath(rootType: TypeId): WritableKeyPath<Root, Root> =
  object : WritableKeyPathBase<Root, Root>() {
    override val id: PathId = PathId.identity(rootType)
    override val valueType: TypeId = rootType

    override fun get(root: Root): Root = root

    override fun set(root: Root, value: Root): Root = value
  }

/** Captures [Root]'s complete type token when called from a reified Kotlin context. */
public inline fun <reified Root> identityKeyPath(): WritableKeyPath<Root, Root> =
  identityKeyPath(TypeId.of<Root>())

/** Standard read-only paths for [String]. */
public object StringPaths {
  public val length: KeyPath<String, Int> = keyPath(
    id = PathId.property(TypeId.of<String>(), "length"),
    valueType = TypeId.of<Int>(),
  ) { string -> string.length }
}

/** Standard read-only paths for a [Pair]. */
public class PairPaths<First, Second> private constructor(
  rootType: TypeId,
  firstType: TypeId,
  secondType: TypeId,
) {
  public val first: KeyPath<Pair<First, Second>, First> = keyPath(
    id = PathId.property(rootType, "first"),
    valueType = firstType,
  ) { pair -> pair.first }

  public val second: KeyPath<Pair<First, Second>, Second> = keyPath(
    id = PathId.property(rootType, "second"),
    valueType = secondType,
  ) { pair -> pair.second }

  public companion object {
    public fun <First, Second> of(
      firstType: TypeId,
      secondType: TypeId,
    ): PairPaths<First, Second> = PairPaths(
      rootType = declaredType("kotlin.Pair", firstType, secondType),
      firstType = firstType,
      secondType = secondType,
    )
  }
}

/** Captures a [Pair]'s complete component type tokens from a reified Kotlin context. */
public inline fun <reified First, reified Second> PairPaths(): PairPaths<First, Second> =
  PairPaths.of(TypeId.of<First>(), TypeId.of<Second>())

/** Standard read-only paths for a [Triple]. */
public class TriplePaths<First, Second, Third> private constructor(
  rootType: TypeId,
  firstType: TypeId,
  secondType: TypeId,
  thirdType: TypeId,
) {
  public val first: KeyPath<Triple<First, Second, Third>, First> = keyPath(
    id = PathId.property(rootType, "first"),
    valueType = firstType,
  ) { triple -> triple.first }

  public val second: KeyPath<Triple<First, Second, Third>, Second> = keyPath(
    id = PathId.property(rootType, "second"),
    valueType = secondType,
  ) { triple -> triple.second }

  public val third: KeyPath<Triple<First, Second, Third>, Third> = keyPath(
    id = PathId.property(rootType, "third"),
    valueType = thirdType,
  ) { triple -> triple.third }

  public companion object {
    public fun <First, Second, Third> of(
      firstType: TypeId,
      secondType: TypeId,
      thirdType: TypeId,
    ): TriplePaths<First, Second, Third> = TriplePaths(
      rootType = declaredType("kotlin.Triple", firstType, secondType, thirdType),
      firstType = firstType,
      secondType = secondType,
      thirdType = thirdType,
    )
  }
}

/** Captures a [Triple]'s complete component type tokens from a reified Kotlin context. */
public inline fun <reified First, reified Second, reified Third> TriplePaths(): TriplePaths<First, Second, Third> =
  TriplePaths.of(TypeId.of<First>(), TypeId.of<Second>(), TypeId.of<Third>())

/** Standard read-only paths for an immutable [List]. */
public class ListPaths<Element> private constructor(
  private val rootType: TypeId,
  private val elementType: TypeId,
) {
  public val size: KeyPath<List<Element>, Int> = keyPath(
    id = PathId.property(rootType, "size"),
    valueType = TypeId.of<Int>(),
  ) { list -> list.size }

  /**
   * Returns a path through [index]. Like Kotlin's indexed list access, reading the path throws
   * [IndexOutOfBoundsException] when the root has no element at that index.
   */
  public fun at(index: Int): KeyPath<List<Element>, Element> = keyPath(
    id = PathId(rootType, listOf(PathSegment.Index(index))),
    valueType = elementType,
  ) { list -> list[index] }

  public companion object {
    public fun <Element> of(elementType: TypeId): ListPaths<Element> = ListPaths(
      rootType = declaredType("kotlin.collections.List", elementType),
      elementType = elementType,
    )
  }
}

/** Captures a [List]'s complete element type token from a reified Kotlin context. */
public inline fun <reified Element> ListPaths(): ListPaths<Element> =
  ListPaths.of(TypeId.of<Element>())

/** Standard paths for a mutable [Array]. Indexed paths mutate the same array instance. */
public class ArrayPaths<Element> private constructor(
  private val rootType: TypeId,
  private val elementType: TypeId,
) {
  public val size: KeyPath<Array<Element>, Int> = keyPath(
    id = PathId.property(rootType, "size"),
    valueType = TypeId.of<Int>(),
  ) { array -> array.size }

  /**
   * Returns an in-place mutable path through [index]. Like Kotlin's indexed array access, reads
   * and writes throw [ArrayIndexOutOfBoundsException] when the index is outside the array.
   */
  public fun at(index: Int): ReferenceWritableKeyPath<Array<Element>, Element> = referenceWritableKeyPath(
    id = PathId(rootType, listOf(PathSegment.Index(index))),
    valueType = elementType,
    get = { array -> array[index] },
    set = { array, value -> array[index] = value },
  )

  public companion object {
    public fun <Element> of(elementType: TypeId): ArrayPaths<Element> = ArrayPaths(
      rootType = declaredType("kotlin.Array", elementType),
      elementType = elementType,
    )
  }
}

/** Captures an [Array]'s complete element type token from a reified Kotlin context. */
public inline fun <reified Element> ArrayPaths(): ArrayPaths<Element> =
  ArrayPaths.of(TypeId.of<Element>())

/** Standard paths for a [MutableList]. Indexed paths mutate the same list instance. */
public class MutableListPaths<Element> private constructor(
  private val rootType: TypeId,
  private val elementType: TypeId,
) {
  public val size: KeyPath<MutableList<Element>, Int> = keyPath(
    id = PathId.property(rootType, "size"),
    valueType = TypeId.of<Int>(),
  ) { list -> list.size }

  /**
   * Returns an in-place mutable path through [index]. Like Kotlin's indexed mutable-list access,
   * reads and writes throw [IndexOutOfBoundsException] when the root has no element at that index.
   */
  public fun at(index: Int): ReferenceWritableKeyPath<MutableList<Element>, Element> = referenceWritableKeyPath(
    id = PathId(rootType, listOf(PathSegment.Index(index))),
    valueType = elementType,
    get = { list -> list[index] },
    set = { list, value -> list[index] = value },
  )

  public companion object {
    public fun <Element> of(elementType: TypeId): MutableListPaths<Element> = MutableListPaths(
      rootType = declaredType("kotlin.collections.MutableList", elementType),
      elementType = elementType,
    )
  }
}

/** Captures a [MutableList]'s complete element type token from a reified Kotlin context. */
public inline fun <reified Element> MutableListPaths(): MutableListPaths<Element> =
  MutableListPaths.of(TypeId.of<Element>())

private fun declaredType(classifier: String, vararg arguments: TypeId): TypeId =
  TypeId(classifier, arguments.map(TypeArgument::invariant))
