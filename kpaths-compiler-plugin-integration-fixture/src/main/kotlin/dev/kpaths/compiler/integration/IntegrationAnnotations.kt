package dev.kpaths.compiler.integration

import dev.kpaths.annotations.KPathsAdapter
import dev.kpaths.annotations.KPathsAdapterFactory
import dev.kpaths.annotations.KPathsPathable

/** A dependency-owned opt-in annotation with a KPaths generation default. */
@KPathsPathable(copyWritableVals = true)
@Target(AnnotationTarget.CLASS)
annotation class LibraryPathable

/** A dependency-owned generic storage type that participates in a future specialized path adapter. */
@KPathsAdapter(factory = CellPathFactory::class)
class Cell<Value>(var value: Value)

/** Placeholder for the future registered compiler/KSP adapter implementation. */
object CellPathFactory : KPathsAdapterFactory
