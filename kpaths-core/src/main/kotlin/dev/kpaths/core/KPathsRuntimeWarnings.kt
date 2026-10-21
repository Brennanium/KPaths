package dev.kpaths.core

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/** A non-fatal runtime diagnostic emitted by KPaths. */
public data class KPathsRuntimeWarning(
  public val message: String,
  public val pathId: PathId,
)

/**
 * Configures delivery of non-fatal KPaths runtime diagnostics.
 *
 * The default handler writes to standard error. Applications may replace [handler] to integrate
 * with their logging system or set it to an empty handler to silence runtime warnings.
 */
public object KPathsRuntimeWarnings {
  @Volatile
  public var handler: (KPathsRuntimeWarning) -> Unit = { warning ->
    System.err.println("[KPaths] warning: ${warning.message}")
  }

  private val reportedPathIds: MutableSet<PathId> = ConcurrentHashMap.newKeySet()

  internal fun warnIfDataClassRoot(rootClass: KClass<*>?, pathId: PathId) {
    if (rootClass?.isData != true || !reportedPathIds.add(pathId)) return

    handler(
      KPathsRuntimeWarning(
        message =
          "ReferenceWritableKeyPath '$pathId' has data-class root '${rootClass.qualifiedName}'. " +
            "It mutates the supplied instance; prefer WritableKeyPath when value semantics are intended.",
        pathId = pathId,
      ),
    )
  }
}
