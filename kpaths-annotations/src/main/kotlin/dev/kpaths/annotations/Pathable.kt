package dev.kpaths.annotations

/**
 * Marks a Kotlin type for generated KeyPaths and CaseKeyPaths.
 *
 * The generator derives KeyPaths from accessible properties and CaseKeyPaths from sealed or enum
 * variants. A sealed type with shared properties can receive both kinds. Every valid annotated type
 * gets the top-level `TypeNamePaths` catalog (or the [name] you choose); adding a visible companion
 * object also enables the more discoverable `Type.Paths` facade when supported. Generic models
 * require an explicitly named companion for that facade.
 * Supply [name] to choose the top-level KeyPath and CaseKeyPath catalog name instead of the default
 * `TypeNamePaths`. Set [copyWritableVals] when primary-constructor `val` properties of a data class
 * should also receive copy-based `WritableKeyPath`s.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
public annotation class Pathable(
  public val name: String = "",
  public val copyWritableVals: Boolean = false,
)
