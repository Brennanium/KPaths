package dev.kpaths.jvm

import dev.kpaths.annotations.Pathable
import dev.kpaths.core.casePathsOf
import dev.kpaths.core.keyPath
import kotlin.test.Test
import kotlin.test.assertEquals

private sealed interface Event {
}

private data class Message(val text: String) : Event

@Pathable
private data class Account(val name: String)

class KPathsJvmTest {
  @Test
  fun exposesCoreAnnotationsAndReflectionFactories() {
    val name = keyPath(Account::name)
    val message = casePathsOf<Event>().pathFor(::Message)

    assertEquals("Blob", name.get(Account("Blob")))
    assertEquals(Message("Hello"), message.embed("Hello"))
  }
}
