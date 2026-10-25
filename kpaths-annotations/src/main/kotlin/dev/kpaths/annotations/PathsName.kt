package dev.kpaths.annotations

/**
 * Names a sealed data case's generated nested property-path catalog.
 *
 * Apply this to a direct case with generated property paths of an enclosing [Pathable] sealed type. It changes the
 * `CasePaths` portion of the generated API without changing the whole-case [CaseName] member or
 * path identity.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
public annotation class PathsName(
  public val value: String,
)
