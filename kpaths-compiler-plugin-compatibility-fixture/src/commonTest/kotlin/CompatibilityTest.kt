package fixture

import dev.kpaths.annotations.Pathable
import dev.kpaths.core.CaseMatch
import kotlin.test.Test
import kotlin.test.assertEquals

@Pathable(copyWritableVals = true)
data class Person(val name: String)

@Pathable
sealed interface Event {
  data class Message(val text: String) : Event
}

class CompatibilityTest {
  @Test
  fun generatedPathsWorkOnNative() {
    val person = Person("before")
    assertEquals(Person("after"), Person.Paths.name.set(person, "after"))
    assertEquals(Person("before"), person)
    assertEquals(CaseMatch.Matched("hello"), Event.Paths.Message.text.match(Event.Message("hello")))
    assertEquals(Event.Message("created"), Event.Paths.Message.text.embed("created"))
  }
}
