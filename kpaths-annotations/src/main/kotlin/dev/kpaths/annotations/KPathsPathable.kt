package dev.kpaths.annotations

/**
 * Marks an annotation class as an opt-in to KPaths catalog generation.
 *
 * A library can annotate one of its own class annotations with `@KPathsPathable`. When the KPaths
 * compiler plugin is active, a source type annotated with that library annotation receives the
 * same generated catalog as a directly [Pathable] type. This lets an integration establish useful
 * defaults without requiring its users to repeat `@Pathable`.
 *
 * This annotation has binary retention because the annotation class normally lives in a dependency
 * of the project being compiled. The compiler plugin must be able to discover this opt-in through
 * Kotlin metadata from that dependency.
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class KPathsPathable(
  /** Whether primary-constructor `val` properties receive copy-based writable paths by default. */
  public val copyWritableVals: Boolean = false,
)
