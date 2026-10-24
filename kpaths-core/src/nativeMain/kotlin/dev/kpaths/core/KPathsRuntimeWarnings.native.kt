package dev.kpaths.core

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.reflect.KClass

@OptIn(ExperimentalAtomicApi::class)
public actual object KPathsRuntimeWarnings {
  private val handlerReference = AtomicReference<((KPathsRuntimeWarning) -> Unit)?>(
    { warning -> println("[KPaths] warning: ${warning.message}") },
  )

  public actual var handler: ((KPathsRuntimeWarning) -> Unit)?
    get() = handlerReference.load()
    set(value) {
      handlerReference.store(value)
    }

  internal actual fun warnIfDataClassRoot(rootClass: KClass<*>?, pathId: PathId) {
    // Kotlin/Native does not expose KClass.isData, so it cannot reliably diagnose a manually
    // constructed reference-writable path whose root happens to be a data class.
    // Generated data-class paths are copy-based and do not take this code path.
  }
}
