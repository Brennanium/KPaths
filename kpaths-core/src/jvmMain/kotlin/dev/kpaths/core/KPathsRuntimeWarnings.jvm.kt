package dev.kpaths.core

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

public actual object KPathsRuntimeWarnings {
  @Volatile
  public actual var handler: ((KPathsRuntimeWarning) -> Unit)? = { warning ->
    System.err.println("[KPaths] warning: ${warning.message}")
  }

  private val reportedPathIds: MutableSet<PathId> = ConcurrentHashMap.newKeySet()

  internal actual fun warnIfDataClassRoot(rootClass: KClass<*>?, pathId: PathId) {
    val handler = handler ?: return
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
