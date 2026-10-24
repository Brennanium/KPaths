package dev.kpaths.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class KPathsNativeTest {
  @Test
  fun typeIdsRetainGenericArgumentsAndCollectionMutability() {
    assertEquals(
      TypeId(
        classifier = "kotlin.collections.List",
        arguments = listOf(TypeArgument.invariant(TypeId("kotlin.String", nullable = true))),
      ),
      TypeId.of<List<String?>>(),
    )
    assertNotEquals(TypeId.of<List<String>>(), TypeId.of<MutableList<String>>())
  }

  @Test
  fun propertyFactoriesReadAndCopyWrite() {
    val name = keyPath(NativePerson::name)
    val city = writableKeyPath(
      get = NativePerson::city,
      set = { person, value -> person.copy(city = value) },
    )
    val person = NativePerson("Blob", "Toronto")

    assertEquals("Blob", person[name])
    assertEquals(NativePerson("Blob", "Paris"), person.setting(city, "Paris"))
    assertEquals(NativePerson("Blob", "Toronto"), person)
  }

  @Test
  fun casePathsComposeAndEmbedOnNative() {
    val message = caseKeyPath<NativeEvent, NativeEvent.Message, String>(
      extract = NativeEvent.Message::text,
      embed = ::nativeMessage,
    )

    assertEquals("Hello", NativeEvent.Message("Hello")[message])
    assertNull(NativeEvent.Idle[message])
    assertEquals(NativeEvent.Message("Updated"), message.embed("Updated"))
  }
}

private data class NativePerson(val name: String, val city: String)

private sealed interface NativeEvent {
  data object Idle : NativeEvent
  data class Message(val text: String) : NativeEvent
}

private fun nativeMessage(text: String): NativeEvent.Message = NativeEvent.Message(text)
