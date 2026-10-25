package dev.kpaths.annotations

/**
 * Names a sealed variant or enum entry's generated [dev.kpaths.core.CaseKeyPath] member.
 *
 * Apply this to a direct case of an enclosing [Pathable] sealed type or enum. It changes only the
 * generated Kotlin member spelling; the source case name remains part of the path's stable identity.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.SOURCE)
public annotation class CaseName(
  public val value: String,
)
