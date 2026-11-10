package example

import dev.kpaths.annotations.Pathable
import dev.kpaths.core.CaseKeyPath
import dev.kpaths.core.WritableKeyPath

@Pathable
data class Person(var name: String)

@Pathable
sealed interface Event {
  data class Message(val text: String) : Event
}

val personName: WritableKeyPath<Person, String> = Person.Paths.name
val messageText: CaseKeyPath<Event, String> = Event.Paths.Message.text

val directName = Person::name
