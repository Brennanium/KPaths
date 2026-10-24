package dev.kpaths.core

import kotlin.reflect.KClass

/** A non-fatal runtime diagnostic emitted by KPaths. */
public data class KPathsRuntimeWarning(
  public val message: String,
  public val pathId: PathId,
)

/**
 * Configures delivery of non-fatal KPaths runtime diagnostics.
 *
 * On the JVM, the default handler writes to standard error. Applications may replace [handler]
 * to integrate with their logging system, or set it to `null` to disable runtime-warning work
 * entirely.
 *
 * Kotlin/Native does not expose the runtime metadata necessary to identify data classes, so the
 * data-class warning emitted for manually constructed [ReferenceWritableKeyPath] instances is
 * inactive there. The [handler] property remains available so cross-platform application code
 * does not need a platform branch.
 */
public expect object KPathsRuntimeWarnings {
  public var handler: ((KPathsRuntimeWarning) -> Unit)?

  internal fun warnIfDataClassRoot(rootClass: KClass<*>?, pathId: PathId)
}
