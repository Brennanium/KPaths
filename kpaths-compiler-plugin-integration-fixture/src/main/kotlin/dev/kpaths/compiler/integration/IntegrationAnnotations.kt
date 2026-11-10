package dev.kpaths.compiler.integration

import dev.kpaths.annotations.PathsAdapter
import dev.kpaths.annotations.KPathsAdapterFactory
import dev.kpaths.annotations.KPathsPathable
import dev.kpaths.core.KeyPath
import dev.kpaths.core.KeyPathBase

/** A dependency-owned opt-in annotation with a KPaths generation default. */
@KPathsPathable(copyWritableVals = true)
@Target(AnnotationTarget.CLASS)
annotation class LibraryPathable

/** A dependency-owned generic storage type that participates in a future specialized path adapter. */
@PathsAdapter(factory = CellPathFactory::class)
class Cell<Value>(var value: Value)

/** Placeholder for the future registered compiler/KSP adapter implementation. */
object CellPathFactory : KPathsAdapterFactory {
  fun <Root, Value> make(path: KeyPath<Root, Cell<Value>>): KeyPath<Root, Cell<Value>> = path
}

/** Also exercise a non-generic storage type and a nested adapter factory. */
@PathsAdapter(factory = Adapters.ScalarFactory::class)
class Scalar(val value: Int)

object Adapters {
  object ScalarFactory : KPathsAdapterFactory
}

class PlainStorage

@PathsAdapter(factory = BinaryFactory::class)
class BinaryCell<Value>(val value: Value)

object BinaryFactory : KPathsAdapterFactory {
  fun <Root, Value> make(path: KeyPath<Root, BinaryCell<Value>>): BinaryPath<Root, Value> = BinaryPath(path)
}

class BinaryPath<Root, Value>(private val path: KeyPath<Root, BinaryCell<Value>>) : KeyPathBase<Root, Value>() {
  override val __kpaths_id get() = path.__kpaths_id
  override val __kpaths_valueType get() = requireNotNull(path.__kpaths_valueType.arguments.single().type)
  override fun get(root: Root): Value = path.get(root).value
}
