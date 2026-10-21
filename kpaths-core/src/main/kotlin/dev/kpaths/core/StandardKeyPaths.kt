package dev.kpaths.core

import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.KClass

/** Creates a total read path with an explicitly supplied structural identity and type token. */
public fun <Root, Value> keyPath(
  id: PathId,
  valueType: TypeId,
  get: (Root) -> Value,
): KeyPath<Root, Value> {
  val getter = get
  return object : KeyPathBase<Root, Value>() {
    override val __kpaths_id: PathId = id
    override val __kpaths_valueType: TypeId = valueType

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

/**
 * Creates a total read path through Kotlin's indexed `get` operation at [at].
 *
 * For example, `keyPath(get = List<String>::get, at = 1)` produces the same structural path as
 * `ListPaths<String>().at(1)`.
 */
public inline fun <reified Root, reified Value> keyPath(
  noinline get: (Root, Int) -> Value,
  at: Int,
): KeyPath<Root, Value> = keyPath(
  id = PathId(TypeId.of<Root>(), listOf(PathSegment.Index(at))),
  valueType = TypeId.of<Value>(),
  get = { root -> get(root, at) },
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
    override val __kpaths_id: PathId = id
    override val __kpaths_valueType: TypeId = valueType

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

/**
 * Adapts a property reference into a copy-based writable path with an explicitly supplied
 * reconstruction operation.
 *
 * [get] provides the readable property route, while [set] defines how a replacement value forms
 * a new root. This supports computed read-only properties when the caller can supply an inverse.
 */
public fun <Root, Value> writableKeyPath(
  get: KProperty1<Root, Value>,
  id: PathId,
  valueType: TypeId,
  set: (Root, Value) -> Root,
): WritableKeyPath<Root, Value> = writableKeyPath(
  id = id,
  valueType = valueType,
  get = get::get,
  set = set,
)

/**
 * Adapts a property reference into a copy-based writable path while inferring its identity and
 * complete type tokens.
 */
public inline fun <reified Root, reified Value> writableKeyPath(
  get: KProperty1<Root, Value>,
  noinline set: (Root, Value) -> Root,
): WritableKeyPath<Root, Value> = writableKeyPath(
  get = get,
  id = PathId.property(TypeId.of<Root>(), get.name),
  valueType = TypeId.of<Value>(),
  set = set,
)

/**
 * Adapts a mutable property reference into a copy-based writable path.
 *
 * [copy] must return a distinct replacement root when value semantics are required. The mutable
 * property setter, including a computed setter, is then invoked on that copied root.
 */
public fun <Root : Any, Value> writableKeyPath(
  property: KMutableProperty1<Root, Value>,
  id: PathId,
  valueType: TypeId,
  copy: (Root) -> Root,
): WritableKeyPath<Root, Value> = writableKeyPath(
  get = property,
  id = id,
  valueType = valueType,
  set = { root, value ->
    copy(root).also { copied -> property.set(copied, value) }
  },
)

/** Adapts a mutable property reference while inferring its identity and complete type tokens. */
public inline fun <reified Root : Any, reified Value> writableKeyPath(
  property: KMutableProperty1<Root, Value>,
  noinline copy: (Root) -> Root,
): WritableKeyPath<Root, Value> = writableKeyPath(
  property = property,
  id = PathId.property(TypeId.of<Root>(), property.name),
  valueType = TypeId.of<Value>(),
  copy = copy,
)

/** Creates a path whose replacement mutates the supplied root instance. */
public fun <Root : Any, Value> referenceWritableKeyPath(
  id: PathId,
  valueType: TypeId,
  get: (Root) -> Value,
  set: (Root, Value) -> Unit,
  rootClass: KClass<*>? = null,
): ReferenceWritableKeyPath<Root, Value> {
  KPathsRuntimeWarnings.warnIfDataClassRoot(rootClass, id)
  val getter = get
  val setter = set
  return object : ReferenceWritableKeyPathBase<Root, Value>() {
    override val __kpaths_id: PathId = id
    override val __kpaths_valueType: TypeId = valueType

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
  referenceWritableKeyPath(id, TypeId.of<Value>(), get, set, Root::class)

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
  rootClass = Root::class,
)

/**
 * Creates an in-place mutable path through Kotlin's indexed `get` and `set` operations at [at].
 *
 * For example, `referenceWritableKeyPath(get = MutableList<String>::get, set =
 * MutableList<String>::set, at = 1)` produces the same structural path as
 * `MutableListPaths<String>().at(1)`.
 */
public inline fun <reified Root : Any, reified Value> referenceWritableKeyPath(
  noinline get: (Root, Int) -> Value,
  noinline set: (Root, Int, Value) -> Unit,
  at: Int,
): ReferenceWritableKeyPath<Root, Value> = referenceWritableKeyPath(
  id = PathId(TypeId.of<Root>(), listOf(PathSegment.Index(at))),
  valueType = TypeId.of<Value>(),
  get = { root -> get(root, at) },
  set = { root, value -> set(root, at, value) },
  rootClass = Root::class,
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
  rootClass: KClass<*>? = null,
): ReferenceWritableKeyPath<Root, Value> =
  referenceWritableKeyPath(id, valueType, property::get, property::set, rootClass)

/**
 * Adapts a mutable property reference while capturing its complete root and focus type tokens.
 */
public inline fun <reified Root : Any, reified Value> referenceWritableKeyPath(
  property: KMutableProperty1<Root, Value>,
): ReferenceWritableKeyPath<Root, Value> = referenceWritableKeyPath(
  property = property,
  id = PathId.property(TypeId.of<Root>(), property.name),
  valueType = TypeId.of<Value>(),
  rootClass = Root::class,
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
    override val __kpaths_id: PathId = PathId.identity(rootType)
    override val __kpaths_valueType: TypeId = rootType

    override fun get(root: Root): Root = root

    override fun set(root: Root, value: Root): Root = value
  }

/** Captures [Root]'s complete type token when called from a reified Kotlin context. */
public inline fun <reified Root> identityKeyPath(): WritableKeyPath<Root, Root> =
  identityKeyPath(TypeId.of<Root>())

private fun <Root, Value> indexedKeyPath(
  rootType: TypeId,
  valueType: TypeId,
  index: Int,
  get: (Root, Int) -> Value,
): KeyPath<Root, Value> = keyPath(
  id = PathId(rootType, listOf(PathSegment.Index(index))),
  valueType = valueType,
  get = { root -> get(root, index) },
)

private fun <Root : Any, Value> indexedReferenceWritableKeyPath(
  rootType: TypeId,
  valueType: TypeId,
  index: Int,
  get: (Root, Int) -> Value,
  set: (Root, Int, Value) -> Unit,
): ReferenceWritableKeyPath<Root, Value> = referenceWritableKeyPath(
  id = PathId(rootType, listOf(PathSegment.Index(index))),
  valueType = valueType,
  get = { root -> get(root, index) },
  set = { root, value -> set(root, index, value) },
)

/** Standard read-only paths for [CharSequence]. */
public object CharSequencePaths {
  public val length: KeyPath<CharSequence, Int> = keyPath(
    id = PathId.property(TypeId.of<CharSequence>(), "length"),
    valueType = TypeId.of<Int>(),
  ) { sequence -> sequence.length }

  /** Returns the [Char] at [index], using Kotlin's ordinary UTF-16 indexing semantics. */
  public fun at(index: Int): KeyPath<CharSequence, Char> = indexedKeyPath(
    rootType = TypeId.of<CharSequence>(),
    valueType = TypeId.of<Char>(),
    index = index,
    get = CharSequence::get,
  )
}

/** Standard read-only paths for [String]. */
public object StringPaths {
  public val length: KeyPath<String, Int> = keyPath(
    id = PathId.property(TypeId.of<String>(), "length"),
    valueType = TypeId.of<Int>(),
  ) { string -> string.length }

  /** Returns the [Char] at [index], using Kotlin's ordinary UTF-16 indexing semantics. */
  public fun at(index: Int): KeyPath<String, Char> = indexedKeyPath(
    rootType = TypeId.of<String>(),
    valueType = TypeId.of<Char>(),
    index = index,
    get = String::get,
  )
}

/** Standard paths for an immutable [Map]. Setting a focused `null` removes its key. */
public class MapPaths<Key, Value> private constructor(
  private val rootType: TypeId,
  private val valueType: TypeId,
) {
  public val size: KeyPath<Map<Key, Value>, Int> = keyPath(
    id = PathId.property(rootType, "size"),
    valueType = TypeId.of<Int>(),
  ) { map -> map.size }

  /**
   * Returns a copy-based path through [key]. A missing key reads as `null`; setting `null`
   * removes the key, even when [Value] itself is nullable.
   */
  public fun at(key: Key): WritableKeyPath<Map<Key, Value>, Value?> = writableKeyPath(
    id = PathId(rootType, listOf(PathSegment.Key(key))),
    valueType = valueType.withNullability(true),
    get = { map -> map[key] },
    set = { map, value ->
      if (value == null) map - key else map + (key to value)
    },
  )

  public companion object {
    public fun <Key, Value> of(keyType: TypeId, valueType: TypeId): MapPaths<Key, Value> =
      MapPaths(
        rootType = declaredType("kotlin.collections.Map", keyType, valueType),
        valueType = valueType,
      )
  }
}

/** Captures a [Map]'s complete key and value type tokens from a reified Kotlin context. */
public inline fun <reified Key, reified Value> MapPaths(): MapPaths<Key, Value> =
  MapPaths.of(TypeId.of<Key>(), TypeId.of<Value>())

/** Standard paths for a [MutableMap]. Setting a focused `null` removes its key in place. */
public class MutableMapPaths<Key, Value> private constructor(
  private val rootType: TypeId,
  private val valueType: TypeId,
) {
  public val size: KeyPath<MutableMap<Key, Value>, Int> = keyPath(
    id = PathId.property(rootType, "size"),
    valueType = TypeId.of<Int>(),
  ) { map -> map.size }

  /**
   * Returns an in-place mutable path through [key]. A missing key reads as `null`; setting
   * `null` removes the key, even when [Value] itself is nullable.
   */
  public fun at(key: Key): ReferenceWritableKeyPath<MutableMap<Key, Value>, Value?> =
    referenceWritableKeyPath(
      id = PathId(rootType, listOf(PathSegment.Key(key))),
      valueType = valueType.withNullability(true),
      get = { map -> map[key] },
      set = { map, value ->
        if (value == null) map.remove(key) else map[key] = value
      },
    )

  public companion object {
    public fun <Key, Value> of(keyType: TypeId, valueType: TypeId): MutableMapPaths<Key, Value> =
      MutableMapPaths(
        rootType = declaredType("kotlin.collections.MutableMap", keyType, valueType),
        valueType = valueType,
      )
  }
}

/** Captures a [MutableMap]'s complete key and value type tokens from a reified Kotlin context. */
public inline fun <reified Key, reified Value> MutableMapPaths(): MutableMapPaths<Key, Value> =
  MutableMapPaths.of(TypeId.of<Key>(), TypeId.of<Value>())

private fun <Root : Any, Value> primitiveArrayPath(
  rootType: TypeId,
  valueType: TypeId,
  index: Int,
  get: (Root, Int) -> Value,
  set: (Root, Int, Value) -> Unit,
): ReferenceWritableKeyPath<Root, Value> = indexedReferenceWritableKeyPath(
  rootType = rootType,
  valueType = valueType,
  index = index,
  get = get,
  set = set,
)

/** Standard paths for [BooleanArray]. */
public object BooleanArrayPaths {
  public val size: KeyPath<BooleanArray, Int> = keyPath(PathId.property(TypeId.of<BooleanArray>(), "size"), TypeId.of<Int>()) { it.size }
  public fun at(index: Int): ReferenceWritableKeyPath<BooleanArray, Boolean> = primitiveArrayPath(TypeId.of<BooleanArray>(), TypeId.of<Boolean>(), index, BooleanArray::get, BooleanArray::set)
}

/** Standard paths for [ByteArray]. */
public object ByteArrayPaths {
  public val size: KeyPath<ByteArray, Int> = keyPath(PathId.property(TypeId.of<ByteArray>(), "size"), TypeId.of<Int>()) { it.size }
  public fun at(index: Int): ReferenceWritableKeyPath<ByteArray, Byte> = primitiveArrayPath(TypeId.of<ByteArray>(), TypeId.of<Byte>(), index, ByteArray::get, ByteArray::set)
}

/** Standard paths for [CharArray]. */
public object CharArrayPaths {
  public val size: KeyPath<CharArray, Int> = keyPath(PathId.property(TypeId.of<CharArray>(), "size"), TypeId.of<Int>()) { it.size }
  public fun at(index: Int): ReferenceWritableKeyPath<CharArray, Char> = primitiveArrayPath(TypeId.of<CharArray>(), TypeId.of<Char>(), index, CharArray::get, CharArray::set)
}

/** Standard paths for [ShortArray]. */
public object ShortArrayPaths {
  public val size: KeyPath<ShortArray, Int> = keyPath(PathId.property(TypeId.of<ShortArray>(), "size"), TypeId.of<Int>()) { it.size }
  public fun at(index: Int): ReferenceWritableKeyPath<ShortArray, Short> = primitiveArrayPath(TypeId.of<ShortArray>(), TypeId.of<Short>(), index, ShortArray::get, ShortArray::set)
}

/** Standard paths for [IntArray]. */
public object IntArrayPaths {
  public val size: KeyPath<IntArray, Int> = keyPath(PathId.property(TypeId.of<IntArray>(), "size"), TypeId.of<Int>()) { it.size }
  public fun at(index: Int): ReferenceWritableKeyPath<IntArray, Int> = primitiveArrayPath(TypeId.of<IntArray>(), TypeId.of<Int>(), index, IntArray::get, IntArray::set)
}

/** Standard paths for [LongArray]. */
public object LongArrayPaths {
  public val size: KeyPath<LongArray, Int> = keyPath(PathId.property(TypeId.of<LongArray>(), "size"), TypeId.of<Int>()) { it.size }
  public fun at(index: Int): ReferenceWritableKeyPath<LongArray, Long> = primitiveArrayPath(TypeId.of<LongArray>(), TypeId.of<Long>(), index, LongArray::get, LongArray::set)
}

/** Standard paths for [FloatArray]. */
public object FloatArrayPaths {
  public val size: KeyPath<FloatArray, Int> = keyPath(PathId.property(TypeId.of<FloatArray>(), "size"), TypeId.of<Int>()) { it.size }
  public fun at(index: Int): ReferenceWritableKeyPath<FloatArray, Float> = primitiveArrayPath(TypeId.of<FloatArray>(), TypeId.of<Float>(), index, FloatArray::get, FloatArray::set)
}

/** Standard paths for [DoubleArray]. */
public object DoubleArrayPaths {
  public val size: KeyPath<DoubleArray, Int> = keyPath(PathId.property(TypeId.of<DoubleArray>(), "size"), TypeId.of<Int>()) { it.size }
  public fun at(index: Int): ReferenceWritableKeyPath<DoubleArray, Double> = primitiveArrayPath(TypeId.of<DoubleArray>(), TypeId.of<Double>(), index, DoubleArray::get, DoubleArray::set)
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
