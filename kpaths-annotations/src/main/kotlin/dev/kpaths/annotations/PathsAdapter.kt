package dev.kpaths.annotations

import kotlin.reflect.KClass

/**
 * Identifies an integration-owned KPaths adapter family.
 *
 * The marker deliberately has no code-generation methods: those belong in the integration's KSP
 * processor or Kotlin compiler plugin, where compiler symbols are available. It gives
 * [PathsAdapter.factory] a type-safe, stable identity shared by runtime and codegen artifacts.
 */
public interface KPathsAdapterFactory

/**
 * Marks a storage type as participating in a specialized KPaths representation.
 *
 * [factory] identifies the integration-owned adapter contract. The annotation intentionally does
 * not prescribe a generic-argument index or focused value type; those are properties of the
 * factory's registered adapter, not of the storage type itself. An adapter can focus any part of
 * a generic type, a non-generic type, several values, or expose a specialized path subtype with
 * entirely different semantics.
 *
 * An integration compiler plugin registers the implementation with KPaths. This binary-visible
 * marker lets that integration recognize its storage types across module boundaries without
 * making ordinary KPaths users depend on compiler-plugin APIs.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class PathsAdapter(
  public val factory: KClass<out KPathsAdapterFactory>,
)
