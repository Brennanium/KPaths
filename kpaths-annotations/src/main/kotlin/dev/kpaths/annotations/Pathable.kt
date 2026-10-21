package dev.kpaths.annotations

/**
 * Marks a Kotlin type for generated paths.
 *
 * The generator derives property key paths from accessible properties and case key paths from
 * sealed variants. A sealed type with shared properties can receive both kinds.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
public annotation class Pathable
