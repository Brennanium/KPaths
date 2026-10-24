package dev.kpaths.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CasePathFactoryTest {
  @Test
  fun `root-scoped factory infers conventional constructor payloads`() {
    val selected = casePathsOf<FactoryEvent>().pathFor(::FactorySelected)
    val routed = casePathsOf<FactoryEvent>().pathFor(::FactoryRoute)

    assertEquals(FactorySelected("Jr."), selected.embed("Jr."))
    assertEquals("Blob", FactorySelected("Blob")[selected])
    assertEquals(FactoryRoute("id", "go"), routed.embed("id" to "go"))
  }

  @Test
  fun `root-scoped factory substitutes generic payload type tokens`() {
    val loaded: CaseKeyPath<GenericFactory<String>, String> =
      casePathsOf<GenericFactory<String>>().pathFor(::GenericLoaded)

    assertEquals(TypeId.of<String>(), loaded.__kpaths_valueType)
    assertEquals(GenericLoaded("Blob"), loaded.embed("Blob"))
  }
}

sealed interface FactoryEvent
data class FactorySelected(val value: String) : FactoryEvent
data class FactoryRoute(val id: String, val action: String) : FactoryEvent

sealed interface GenericFactory<out Value>
data class GenericLoaded<Value>(val value: Value) : GenericFactory<Value>
