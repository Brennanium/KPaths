package dev.kpaths.sample

import dev.kpaths.annotations.Pathable

/** Exercises a catalog containing a case whose payload is beyond the current tuple limit. */
@Pathable
sealed interface CatalogPolicyExample {
  data object Idle : CatalogPolicyExample
  data class Many(val a: Int, val b: Int, val c: Int, val d: Int) : CatalogPolicyExample
  class Reference(val value: Int) : CatalogPolicyExample
}

@Pathable
sealed interface UnsupportedCatalogPolicyExample {
  data class Many(val a: Int, val b: Int, val c: Int, val d: Int) : UnsupportedCatalogPolicyExample
}
