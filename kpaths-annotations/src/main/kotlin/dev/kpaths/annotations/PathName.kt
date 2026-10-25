package dev.kpaths.annotations

/**
 * Names a property's generated [dev.kpaths.core.KeyPath] member.
 *
 * Apply this to an accessible property of a [Pathable] class or data class. It changes only the
 * generated Kotlin member spelling; the source property name remains part of the path's stable
 * identity.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
public annotation class PathName(
  public val value: String,
)
