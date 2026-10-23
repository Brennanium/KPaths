package dev.kpaths.annotations

/**
 * Marks a Kotlin type for generated KeyPaths and CaseKeyPaths.
 *
 * The generator derives KeyPaths from accessible properties and CaseKeyPaths from
 * sealed or enum variants. A sealed type with shared properties can receive both kinds. Supply
 * [name] to choose a generated KeyPath and CaseKeyPath namespace instead of the default `TypeNamePaths`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
public annotation class Pathable(
  public val name: String = "",
)
