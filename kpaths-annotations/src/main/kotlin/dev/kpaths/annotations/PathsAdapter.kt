package dev.kpaths.annotations

import kotlin.reflect.KClass

/**
 * Identifies an integration-owned KPaths adapter family.
 *
 * The marker gives [PathsAdapter.factory] a type-safe identity shared by runtime and codegen
 * artifacts. A declarative factory may also expose a `make` function for generated paths.
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
 * The KSP generator supports an accessible object with one non-suspending `make` function of the
 * form `fun <Root, Value> make(path: KeyPath<Root, Storage<Value>>): SpecializedPath<Root, Value>`.
 * It uses the return type for the generated member and emits a call to the factory. The compiler
 * plugin's automatic declarative support is under development. Integrations may also use their
 * respective lower-level code-generation APIs.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class PathsAdapter(
  public val factory: KClass<out KPathsAdapterFactory>,
)
