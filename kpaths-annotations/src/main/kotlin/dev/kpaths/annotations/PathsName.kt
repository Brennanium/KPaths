package dev.kpaths.annotations

/**
 * Names a generated path namespace.
 *
 * Apply this to a direct sealed case with generated property paths to change its nested property-path
 * catalog without changing its whole-case [CaseName] member or path identity. Apply it to a
 * companion object to rename the generated root facade, such as `Person.KPaths` instead of
 * `Person.Paths`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
public annotation class PathsName(
  public val value: String,
)
