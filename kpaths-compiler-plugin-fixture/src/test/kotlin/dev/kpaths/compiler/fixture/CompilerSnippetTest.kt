@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package dev.kpaths.compiler.fixture

import com.tschuchort.compiletesting.KotlinCompilation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class CompilerSnippetTest {
  @Test
  fun `direct sealed interface variants receive the shared unsupported case diagnostic`() {
    val result = compileSnippet("UnsupportedVariant.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      typealias EventAlias = Event
      @Pathable sealed interface Event {
        sealed interface Child : EventAlias
        interface Unrelated
        data object Idle : Event
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] Sealed variant 'Child' must be a class or object.")
    assertTrue(!result.messages.contains("Sealed variant 'Unrelated'"), result.messages)
  }

  @Test
  fun `sealed cases expose property paths with copy semantics`() {
    val result = compileSnippet("CaseProperties.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.annotations.PathName
      import dev.kpaths.annotations.PathsName
      import dev.kpaths.core.keyPath
      import dev.kpaths.core.setting
      @Pathable sealed interface Event {
        @PathsName("MessageFields") data class Message(@PathName("body") var text: String, val id: Int) : Event
      }
      fun run(): Boolean {
        val original = Event.Message("old", 1)
        return Event.Paths.MessageFields.body == keyPath(Event.Message::text) &&
          original.setting(Event.Paths.MessageFields.body, "new") == Event.Message("new", 1) &&
          original.text == "old" && Event.Paths.MessageFields.id(original) == 1
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.CasePropertiesKt").getMethod("run").invoke(null))
  }

  @Test
  fun `non-data sealed case property paths use reference semantics`() {
    val result = compileSnippet("ReferenceCase.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.ReferenceWritableKeyPath
      @Pathable sealed interface Event {
        class Message(var text: String) : Event
      }
      fun run(): Boolean {
        val original = Event.Message("old")
        val path: ReferenceWritableKeyPath<Event.Message, String> = Event.Paths.MessagePaths.text
        path.setInPlace(original, "new")
        return original.text == "new"
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.ReferenceCaseKt").getMethod("run").invoke(null))
  }

  @Test
  fun `multi property cases expose named bound payload paths`() {
    val result = compileSnippet("BoundCase.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.CaseMatch
      @Pathable sealed interface Event {
        data class Message(val id: Int, val text: String) : Event
      }
      fun run(): Boolean {
        val text = Event.Paths.Message.textFor(id = 1)
        return text.match(Event.Message(1, "hi")) == CaseMatch.Matched("hi") &&
          text.match(Event.Message(2, "hi")) == CaseMatch.NoMatch &&
          text.embed("new") == Event.Message(1, "new") &&
          text == Event.Paths.Message.textFor(id = 1) && text != Event.Paths.Message.textFor(id = 2)
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.BoundCaseKt").getMethod("run").invoke(null))
  }

  @Test
  fun `triple payload factories preserve nullable matches and bound order`() {
    val result = compileSnippet("BoundTriple.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.CaseMatch
      import dev.kpaths.core.PathId
      import dev.kpaths.core.PathSegment
      import dev.kpaths.core.TypeId
      @Pathable sealed interface Event {
        data class Item(val id: Int, val text: String?, val selected: Boolean) : Event
        data object Other : Event
      }
      fun run(): Boolean {
        val text = Event.Paths.Item.textFor(id = 7, selected = true)
        val id = Event.Paths.Item.idFor(text = null, selected = true)
        val selected = Event.Paths.Item.selectedFor(id = 7, text = null)
        val expected = PathId.case(TypeId.of<Event>(), "Item")
          .appending(PathId.property(TypeId.of<Event.Item>(), "text"))
          .appending(PathSegment.Key(7)).appending(PathSegment.Key(true))
        return text.match(Event.Item(7, null, true)) == CaseMatch.Matched(null) &&
          text.match(Event.Item(7, null, false)) == CaseMatch.NoMatch &&
          text.match(Event.Other) == CaseMatch.NoMatch &&
          text.embed("new") == Event.Item(7, "new", true) &&
          id.embed(3) == Event.Item(3, null, true) &&
          selected.embed(false) == Event.Item(7, null, false) &&
          text.__kpaths_id == expected && text.__kpaths_valueType == TypeId.of<String?>()
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.BoundTripleKt").getMethod("run").invoke(null))
  }

  @Test
  fun `case property catalog names are validated and collisions diagnosed`() {
    val result = compileSnippet("CaseCatalogCollision.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.annotations.PathsName
      @Pathable sealed interface Event {
        @PathsName("Shared") data class A(val value: String) : Event
        @PathsName("Shared") data class B(val value: String) : Event
        @PathsName("bad name") data class C(val value: String) : Event
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] Cannot generate paths because 'Shared' would name both")
    assertContains(result.messages, "[KPaths] @PathsName value 'bad name'")
  }

  @Test
  fun `CaseName preserves sealed case identity and payload catalog`() {
    val result = compileSnippet("RenamedCase.kt" to """
      package example
      import dev.kpaths.annotations.CaseName
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.caseKeyPath
      @Pathable sealed interface Event {
        @CaseName("MessageCase") data class Message(val text: String) : Event
        @CaseName("RefreshCase") data object Refresh : Event
      }
      fun run(): Boolean = Event.Paths.MessageCase == caseKeyPath<Event, Event.Message>() &&
        Event.Paths.MessageCase.text.embed("hello") == Event.Message("hello") &&
        Event.Paths.allCasePaths == listOf(Event.Paths.MessageCase.text, Event.Paths.RefreshCase.unit) &&
        Event.Message("hello").case == Event.Paths.MessageCase.text &&
        Event.Refresh.case == Event.Paths.RefreshCase.unit
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.RenamedCaseKt").getMethod("run").invoke(null))
  }

  @Test
  fun `CaseName renames enum cases while preserving identity`() {
    val result = compileSnippet("RenamedEnum.kt" to """
      package example
      import dev.kpaths.annotations.CaseName
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.caseKeyPath
      @Pathable enum class Status { @CaseName("Available") Online, Offline }
      fun run(): Boolean = Status.Paths.Available == caseKeyPath(Status.Online) &&
        Status.Paths.Available.embed(Unit) == Status.Online &&
        Status.Paths.allCasePaths == listOf(Status.Paths.Available, Status.Paths.Offline) &&
        Status.Online.case == Status.Paths.Available
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.RenamedEnumKt").getMethod("run").invoke(null))
  }

  @Test
  fun `invalid and colliding CaseName values are diagnosed`() {
    val result = compileSnippet("InvalidCaseNames.kt" to """
      package example
      import dev.kpaths.annotations.CaseName
      import dev.kpaths.annotations.Pathable
      @Pathable sealed interface Event {
        @CaseName("bad name") data object Bad : Event
        @CaseName("Same") data object First : Event
        @CaseName("Same") data object Second : Event
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] @CaseName value 'bad name' must be a single Kotlin identifier")
    assertContains(result.messages, "[KPaths] Cannot generate paths because 'Same' would name both")
  }

  @Test
  fun `CaseName supports escaped Kotlin keywords`() {
    val result = compileSnippet("KeywordCase.kt" to """
      package example
      import dev.kpaths.annotations.CaseName
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.caseKeyPath
      @Pathable sealed interface Event {
        @CaseName("when") data object Refresh : Event
      }
      fun run(): Boolean = Event.Paths.`when` == caseKeyPath<Event, Event.Refresh>() &&
        Event.Refresh.case == Event.Paths.`when`.unit
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.KeywordCaseKt").getMethod("run").invoke(null))
  }

  @Test
  fun `PathName changes generated property spelling without changing identity`() {
    val result = compileSnippet("RenamedProperty.kt" to """
      package example
      import dev.kpaths.annotations.PathName
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.keyPath
      import dev.kpaths.core.setting
      @Pathable data class Person(@PathName("display") var name: String)
      fun run(): Boolean = Person.Paths.display == keyPath(Person::name) &&
        Person("before").setting(Person.Paths.display, "after") == Person("after")
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.RenamedPropertyKt").getMethod("run").invoke(null))
    val bytes = checkNotNull(result.classLoader.getResourceAsStream("example/Person\$Paths\$__KPaths_name_Path.class"))
      .use { it.readBytes() }
    val calls = mutableListOf<String>()
    ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
      override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?) =
        object : MethodVisitor(Opcodes.ASM9) {
          override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
            calls += owner
          }
        }
    }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    assertTrue(calls.none { it.startsWith("kotlin/reflect/") }, calls.toString())
  }

  @Test
  fun `invalid and colliding PathName values are diagnosed`() {
    val result = compileSnippet("InvalidPathNames.kt" to """
      package example
      import dev.kpaths.annotations.PathName
      import dev.kpaths.annotations.Pathable
      @Pathable data class Person(
        @PathName("not a name") val invalid: String,
        @PathName("name") val display: String,
        val name: String,
      )
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] @PathName value 'not a name' must be a single Kotlin identifier")
    assertContains(result.messages, "[KPaths] Cannot generate paths because 'name' would name both")
  }

  @Test
  fun `PathName permits escaped Kotlin keywords`() {
    val result = compileSnippet("KeywordPathName.kt" to """
      package example
      import dev.kpaths.annotations.PathName
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.keyPath
      @Pathable data class Person(@PathName("when") val name: String)
      fun run(): Boolean = Person.Paths.`when` == keyPath(Person::name)
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.KeywordPathNameKt").getMethod("run").invoke(null))
  }

  @Test
  fun `existing nested Paths receives a KPaths diagnostic`() {
    val result = compileSnippet("CatalogCollision.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable data class Person(val name: String) {
        object Paths
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] Cannot generate 'Person.Paths'")
    assertContains(result.messages, "already declares a nested type named 'Paths'")
  }

  @Test
  fun `named companion Paths receives a KPaths diagnostic`() {
    val result = compileSnippet("CompanionCollision.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable data class Person(val name: String) {
        companion object Paths
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] Cannot generate 'Person.Paths'")
  }

  @Test
  fun `a regular companion and an unrelated nested type do not collide`() {
    val result = compileSnippet("CatalogNoCollision.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable data class Person(val name: String) {
        companion object
        object Helpers
      }
      val name = Person.Paths.name
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `unrelated nested classes are not treated as sealed cases`() {
    val result = compileSnippet("Unrelated.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable sealed interface Event {
        data object Idle : Event
        data class Metadata(val title: String)
      }
      fun run(): Boolean = Event.Paths.allCasePaths == listOf(Event.Paths.Idle.unit)
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.UnrelatedKt").getMethod("run").invoke(null))
  }

  @Test
  fun `nested sealed roots resolve case supertypes`() {
    val result = compileSnippet("Nested.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      class Feature {
        @Pathable sealed interface Event {
          data object Idle : Event
          data class Metadata(val title: String)
        }
      }
      fun run(): Boolean = Feature.Event.Paths.allCasePaths == listOf(Feature.Event.Paths.Idle.unit)
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.NestedKt").getMethod("run").invoke(null))
  }

  @Test
  fun `aliased and qualified supertypes identify cases`() {
    val result = compileSnippet("Aliased.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      typealias Alias = Event
      @Pathable sealed interface Event {
        data object Aliased : Alias
        data object Qualified : example.Event
      }
      fun run(): Boolean = Event.Paths.allCasePaths == listOf(Event.Paths.Aliased.unit, Event.Paths.Qualified.unit)
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.AliasedKt").getMethod("run").invoke(null))
  }

  @Test
  fun `unrelated reserved nested names do not trigger case diagnostics`() {
    val result = compileSnippet("UnrelatedReserved.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable sealed interface Event {
        data object Idle : Event
        data class __kpaths_Metadata(val __kpaths_name: String)
      }
      fun run(): Boolean = Event.Paths.allCasePaths == listOf(Event.Paths.Idle.unit)
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `nested case names do not resolve against an unrelated same named type`() {
    val result = compileSnippet(
      "Other.kt" to "package other\ninterface Event",
      "Shadow.kt" to """
        package example
        import dev.kpaths.annotations.Pathable
        import other.Event as OtherEvent
        class Feature {
          @Pathable sealed interface Event {
            data object Idle : Event
            data class Unrelated(val value: Int) : OtherEvent
          }
        }
        fun run(): Boolean = Feature.Event.Paths.allCasePaths == listOf(Feature.Event.Paths.Idle.unit)
      """,
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `case discovery resolves aliases declared in another source file`() {
    val result = compileSnippet(
      "Event.kt" to """
        package example
        import dev.kpaths.annotations.Pathable
        import example.aliases.EventAlias
        @Pathable sealed interface Event {
          data object Idle : EventAlias
        }
        fun run(): Boolean = Event.Paths.allCasePaths == listOf(Event.Paths.Idle.unit)
      """,
      "Alias.kt" to "package example.aliases\ntypealias EventAlias = example.Event",
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `case discovery resolves a generic alias`() {
    val result = compileSnippet("GenericAlias.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      typealias Alias<T> = Event
      @Pathable sealed interface Event {
        data object Idle : Alias<String>
      }
      fun run(): Boolean = Event.Paths.allCasePaths == listOf(Event.Paths.Idle.unit)
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `generic alias declared in another file resolves in its own import scope`() {
    val result = compileSnippet(
      "GenericAlias.kt" to """
        package example
        import dev.kpaths.annotations.Pathable
        import aliases.EventAlias
        @Pathable sealed interface Event {
          data object Idle : EventAlias<String>
        }
        fun run(): Boolean = Event.Paths.allCasePaths == listOf(Event.Paths.Idle.unit)
      """,
      "Alias.kt" to """
        package aliases
        import example.Event
        typealias EventAlias<T> = Event
      """,
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `inaccessible setters and reserved names use shared diagnostics`() {
    val result = compileSnippet("Policy.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable data class Item(val id: Int) {
        var count: Int = 0
          private set
      }
      @Pathable data class Reserved(val __kpaths_rootType: String)
      @Pathable sealed interface Event {
        data object __kpaths_Changed : Event
        data class Changed(val id: Int) : Event {
          var version = 0
            private set
        }
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] Data-class property 'count' must be declared in the primary constructor or have an accessible setter")
    assertContains(result.messages, "[KPaths] Cannot generate paths because property '__kpaths_rootType' uses the reserved '__kpaths_' prefix")
    assertContains(result.messages, "[KPaths] Cannot generate paths because case '__kpaths_Changed' uses the reserved '__kpaths_' prefix")
    assertContains(result.messages, "[KPaths] Data-class property 'version' must be declared in the primary constructor or have an accessible setter")
  }

  @Test
  fun `private class setter generates read only path`() {
    val result = compileSnippet("PrivateSetter.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.KeyPath
      import dev.kpaths.core.ReferenceWritableKeyPath
      @Pathable class Item {
        var count: Int = 0
          private set
      }
      fun run(): Boolean {
        val path: KeyPath<Item, Int> = Item.Paths.count
        return path !is ReferenceWritableKeyPath<*, *>
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.PrivateSetterKt").getMethod("run").invoke(null))
  }

  @Test
  fun `property and case collision uses shared diagnostic`() {
    val result = compileSnippet("Collision.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable sealed class Connection {
        abstract val Connected: String
        data class Connected(override val Connected: String) : Connection()
      }
    """)
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] Cannot generate paths because 'Connected' would name both")
    assertContains(result.messages, "the key path for property 'Connected' and the case path for 'Connected'")
  }

  @Test
  fun `case catalog baseline omits unsupported payloads without substituting whole case paths`() {
    val result = compileSnippet(
      "CatalogPolicy.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      @Pathable sealed interface Event {
        data object Idle : Event
        data class Many(val a: Int, val b: Int, val c: Int, val d: Int) : Event
        class Reference(val value: Int) : Event
      }
      @Pathable sealed interface UnsupportedEvent {
        data class Many(val a: Int, val b: Int, val c: Int, val d: Int) : UnsupportedEvent
      }
      fun run(): Boolean {
        val many = Event.Many(1, 2, 3, 4)
        return Event.Paths.allCasePaths == listOf(Event.Paths.Idle.unit) &&
          runCatching { Event.Paths.caseOf(many) }.exceptionOrNull() is IllegalStateException &&
          runCatching { Event.Paths.caseOf(Event.Reference(1)) }.exceptionOrNull() is IllegalStateException &&
          Event.Paths.Many.embed(many) == many &&
          UnsupportedEvent.Paths.allCasePaths.isEmpty() &&
          runCatching { UnsupportedEvent.Paths.caseOf(UnsupportedEvent.Many(1, 2, 3, 4)) }.exceptionOrNull() is IllegalStateException
      }
      """,
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertContains(result.messages, "[KPaths] The generated case catalog omits 'Many', 'Reference'")
    assertContains(result.messages, "allCasePaths is incomplete")
    assertEquals(true, result.classLoader.loadClass("example.CatalogPolicyKt").getMethod("run").invoke(null))
  }

  @Test
  fun `multi-value case payloads embed match and appear in catalogs`() {
    val result = compileSnippet(
      "Payloads.kt" to """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.CaseKeyPath
      import dev.kpaths.core.CaseMatch
      import dev.kpaths.core.caseKeyPath
      @Pathable sealed interface Request {
        data object Cancel : Request
        data class Open(val id: String, val animated: Boolean) : Request
        data class Edit(val id: String, val count: Int?, val selected: Boolean) : Request
      }
      val open: CaseKeyPath<Request, Pair<String, Boolean>> = Request.Paths.Open.payload
      val edit: CaseKeyPath<Request, Triple<String, Int?, Boolean>> = Request.Paths.Edit.payload
      val manual = caseKeyPath<Request, Request.Open, _, _>(
        extract1 = Request.Open::id, extract2 = Request.Open::animated, embed = Request::Open,
      )
      fun run(): Boolean =
        open.embed("screen" to true) == Request.Open("screen", true) &&
        open.match(Request.Open("screen", false)) == CaseMatch.Matched("screen" to false) &&
        open.match(Request.Cancel) == CaseMatch.NoMatch &&
        edit.embed(Triple("edit", null, true)) == Request.Edit("edit", null, true) &&
        edit.match(Request.Edit("edit", null, true)) == CaseMatch.Matched(Triple("edit", null, true)) &&
        open == manual && open.hashCode() == manual.hashCode() &&
        Request.Open("screen", false).case == open && Request.Edit("edit", null, true).case == edit &&
        Request.Paths.allCasePaths == listOf(Request.Paths.Cancel.unit, open, edit)
      """,
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.PayloadsKt").getMethod("run").invoke(null))
    val bytes = checkNotNull(
      result.classLoader.getResourceAsStream("example/Request\$Paths\$Open\$__KPaths_payload_CasePath.class"),
    ).use { it.readBytes() }
    val calls = mutableListOf<String>()
    ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
      override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?) =
        object : MethodVisitor(Opcodes.ASM9) {
          override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
            calls += owner
          }
        }
    }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    assertTrue(calls.none { it.startsWith("kotlin/reflect/") || it == "dev/kpaths/core/CaseKeyPathKt" }, calls.toString())
  }

  @Test
  fun `enum conformance survives compilation and dispatches case`() {
    val result = compileSnippet(
      "Snippet.kt" to
      """
      package example
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.CasePathable

      @Pathable enum class Signal { Red, Green }
      fun run(): Boolean {
        val value: CasePathable<Signal> = Signal.Green
        return value.case == Signal.Paths.Green
      }
      """,
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertEquals(true, result.classLoader.loadClass("example.SnippetKt").getMethod("run").invoke(null))
    assertTrue(result.classLoader.loadClass("example.Signal").interfaces.any { it.name == "dev.kpaths.core.CasePathable" })
  }

  @Test
  fun `snippet helper reports compiler errors`() {
    val result = compileSnippet("Broken.kt" to "val number: Int = \"wrong\"")
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertTrue(result.messages.contains("type mismatch", ignoreCase = true), result.messages)
  }
}
