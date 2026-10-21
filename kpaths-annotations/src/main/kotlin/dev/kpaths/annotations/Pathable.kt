package dev.kpaths.annotations

/**
 * Marks a Kotlin type for generated paths.
 *
 * The generator derives property key paths from accessible properties and case key paths from
 * sealed or enum variants. A sealed type with shared properties can receive both kinds. Supply
 * [name] to choose a generated paths namespace instead of the default `TypeNamePaths`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
public annotation class Pathable(
  public val name: String = "",
)
