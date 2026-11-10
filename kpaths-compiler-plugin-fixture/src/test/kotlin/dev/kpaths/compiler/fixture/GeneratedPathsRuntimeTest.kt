package dev.kpaths.compiler.fixture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import dev.kpaths.core.TypeId
import dev.kpaths.core.TypeArgument
import dev.kpaths.core.CaseMatch
import dev.kpaths.core.CaseKeyPathBase
import dev.kpaths.core.KeyPathBase
import dev.kpaths.core.PathId
import dev.kpaths.core.PathSegment
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class GeneratedPathsRuntimeTest {
  @Test
  fun declarativeAdapterFactoryWorksFromDependency() {
    val path = BinaryDocument.Paths.text
    assertEquals(null, path.get(BinaryDocument(dev.kpaths.compiler.integration.BinaryCell(null))))
    assertEquals(TypeId.of<String?>(), path.__kpaths_valueType)
    assertEquals(PathId.property(TypeId.of<BinaryDocument>(), "text"), path.__kpaths_id)
    val reader = BinaryReaderDocument.Paths<List<String?>>().reader
    val document = BinaryReaderDocument(dev.kpaths.compiler.integration.BinaryReadable(listOf("value", null)))
    assertEquals(listOf("value", null), reader.get(document))
    assertEquals(TypeId.of<List<String?>>(), reader.__kpaths_valueType)
    assertEquals(reader.__kpaths_id, reader.storage.__kpaths_id)
  }

  @Test
  fun contributedAdapterEmitsASpecializedPath() {
    val path: CellTextPath = AdaptedDocument.GeneratedPaths.title
    val document = AdaptedDocument(dev.kpaths.compiler.integration.Cell("hello"))
    assertEquals("hello", path.get(document))
    assertTrue(path.hasWritableStorage)
    assertEquals(TypeId.of<String>(), path.__kpaths_valueType)
    assertEquals(PathId.property(TypeId.of<AdaptedDocument>(), "title"), path.__kpaths_id)
    assertSame(path, AdaptedDocument.GeneratedPaths.title)
    val generic: GenericCellPath<String?> = AdaptedBox.GeneratedPaths<String?>().title
    assertEquals(null, generic.get(AdaptedBox(dev.kpaths.compiler.integration.Cell(null))))
    assertEquals(TypeId.of<String?>(), generic.__kpaths_valueType)
    assertEquals(PathId.property(TypeId.of<AdaptedBox<String?>>(), "title"), generic.__kpaths_id)
    val caseProperty: EventCellPath = AdaptedEvent.GeneratedPaths.ItemPaths.title
    assertEquals("case", caseProperty.get(AdaptedEvent.Item(dev.kpaths.compiler.integration.Cell("case"))))
    assertEquals(PathId.property(TypeId.of<AdaptedEvent.Item>(), "title"), caseProperty.__kpaths_id)
  }

  @Test
  fun generatedPropertyPathReadsAndIsCanonical() {
    val first = Person.Paths.name
    val second = Person.Paths.name

    assertSame(first, second)
    assertEquals("Ada", first(Person("Ada")))
  }

  @Test
  fun generatedWholeCasePathsMatchAndEmbedNestedSealedCases() {
    assertEquals(
      CaseMatch.Matched(Delivery.Message("Hello")),
      Delivery.Paths.Message.match(Delivery.Message("Hello")),
    )
    assertEquals(CaseMatch.NoMatch, Delivery.Paths.Message.match(Delivery.Refresh))
    assertEquals(Delivery.Refresh, Delivery.Paths.Refresh.embed(Delivery.Refresh))
    assertEquals(Delivery.Message("Updated"), Delivery.Paths.Message.embed(Delivery.Message("Updated")))
    assertEquals(
      TypeId("dev.kpaths.compiler.fixture.Delivery"),
      Delivery.Paths.Message.__kpaths_id.rootType,
    )
  }

  @Test
  fun generatedCaseObjectProvidesAWorkingDirectPayloadPath() {
    val path = Delivery.Paths.Message.text

    assertSame(path, Delivery.Paths.Message.text)
    assertEquals(CaseMatch.Matched("Hello"), path.match(Delivery.Message("Hello")))
    assertEquals(CaseMatch.NoMatch, path.match(Delivery.Refresh))
    assertEquals(Delivery.Message("Updated"), path.embed("Updated"))
    assertEquals(
      PathId(
        TypeId("dev.kpaths.compiler.fixture.Delivery"),
        listOf(PathSegment.Case("Message"), PathSegment.Property("text")),
      ),
      path.__kpaths_id,
    )
  }

  @Test
  fun generatedCatalogListsItsCanonicalCasePaths() {
    assertEquals(
      listOf(Delivery.Paths.Refresh.unit, Delivery.Paths.Message.text),
      Delivery.Paths.allCasePaths,
    )
    assertSame(Delivery.Paths.allCasePaths, Delivery.Paths.allCasePaths)
    assertEquals(Delivery.Paths.Message.text, Delivery.Message("Hello").case)
    assertEquals(Delivery.Paths.Refresh.unit, Delivery.Refresh.case)
  }

  @Test
  fun generatedEnumCatalogListsItsUnitCasePaths() {
    val asCasePathable: dev.kpaths.core.CasePathable<ConnectionStatus> = ConnectionStatus.Online
    assertEquals(ConnectionStatus.Paths.Online, asCasePathable.case)
    assertEquals(1, ConnectionStatus::class.java.interfaces.count { it == dev.kpaths.core.CasePathable::class.java })
    assertEquals(
      1,
      ManuallyCasePathableConnectionStatus::class.java.interfaces.count { it == dev.kpaths.core.CasePathable::class.java },
    )
    assertEquals(
      listOf(
        ConnectionStatus.Paths.Offline,
        ConnectionStatus.Paths.Connecting,
        ConnectionStatus.Paths.Online,
      ),
      ConnectionStatus.Paths.allCasePaths,
    )
    assertEquals(ConnectionStatus.Paths.Online, ConnectionStatus.Online.case)
  }

  @Test
  fun generatedEnumEntryPathsMatchAndEmbedUnit() {
    assertEquals(CaseMatch.Matched(Unit), ConnectionStatus.Paths.Online.match(ConnectionStatus.Online))
    assertEquals(CaseMatch.NoMatch, ConnectionStatus.Paths.Online.match(ConnectionStatus.Offline))
    assertEquals(ConnectionStatus.Online, ConnectionStatus.Paths.Online.embed(Unit))
    assertEquals(
      TypeId("dev.kpaths.compiler.fixture.ConnectionStatus"),
      ConnectionStatus.Paths.Online.__kpaths_id.rootType,
    )
  }

  /**
   * Replaces the former manual `javap` inspection with a checked bytecode contract.
   *
   * Behavior tests alone could still pass if a future compiler-plugin change silently fell back
   * to `KProperty1`, a lambda factory, or runtime case-path factory. These paths are deliberately
   * emitted as private subclasses with direct IR bodies, so inspect their actual JVM instructions.
   */
  @Test
  fun generatedConcretePathsUseDirectImplementationsWithoutReflectionOrFactories() {
    val person = assertDirectGeneratedImplementation(Person.Paths.name, KeyPathBase::class.java)
    assertTrue(
      person.method("get", "(Ldev/kpaths/compiler/fixture/Person;)Ljava/lang/String;")
        .methodCalls
        .contains(MethodCall("dev/kpaths/compiler/fixture/Person", "getName")),
    )

    val message = assertDirectGeneratedImplementation(Delivery.Paths.Message, CaseKeyPathBase::class.java)
    assertTrue(
      message.method("match", "(Ldev/kpaths/compiler/fixture/Delivery;)Ldev/kpaths/core/CaseMatch;")
        .typeInstructions
        .contains(TypeInstruction(Opcodes.INSTANCEOF, "dev/kpaths/compiler/fixture/Delivery\$Message")),
    )

    val messageText = assertDirectGeneratedImplementation(
      Delivery.Paths.Message.text,
      CaseKeyPathBase::class.java,
    )
    assertTrue(
      messageText.method(
        "match",
        "(Ldev/kpaths/compiler/fixture/Delivery;)Ldev/kpaths/core/CaseMatch;",
      ).typeInstructions.contains(
        TypeInstruction(Opcodes.INSTANCEOF, "dev/kpaths/compiler/fixture/Delivery\$Message"),
      ),
    )
    assertTrue(
      messageText.method(
        "embed",
        "(Ljava/lang/String;)Ldev/kpaths/compiler/fixture/Delivery;",
      ).methodCalls.contains(
        MethodCall("dev/kpaths/compiler/fixture/Delivery\$Message", "<init>"),
      ),
    )

    val online = assertDirectGeneratedImplementation(ConnectionStatus.Paths.Online, CaseKeyPathBase::class.java)
    val enumField = FieldAccess(
      Opcodes.GETSTATIC,
      "dev/kpaths/compiler/fixture/ConnectionStatus",
      "Online",
    )
    assertTrue(
      online.method(
        "match",
        "(Ldev/kpaths/compiler/fixture/ConnectionStatus;)Ldev/kpaths/core/CaseMatch;",
      ).fieldAccesses.contains(enumField),
    )
    assertTrue(
      online.method(
        "embed",
        "(Lkotlin/Unit;)Ldev/kpaths/compiler/fixture/ConnectionStatus;",
      ).fieldAccesses.contains(enumField),
    )
  }

  @Test
  fun generatedMutableClassPropertyUsesReferenceWritableSemantics() {
    val draft = Draft("Original")
    val path = Draft.Paths.text

    assertSame(draft, path.set(draft, "Revised"))
    assertEquals("Revised", draft.text)
  }

  @Test
  fun generatedDataClassVarUsesCopyWriteBackSemantics() {
    val original = DraftSnapshot(text = "Original", revision = 3)

    val revised = DraftSnapshot.Paths.text.set(original, "Revised")

    assertEquals(DraftSnapshot(text = "Original", revision = 3), original)
    assertEquals(DraftSnapshot(text = "Revised", revision = 3), revised)
  }

  @Test
  fun generatedDataClassBodyVarCopiesThenMutatesTheCopy() {
    val original = ExtendedDraftSnapshot(text = "Original")

    val revised = ExtendedDraftSnapshot.Paths.isPinned.set(original, true)

    assertEquals(false, original.isPinned)
    assertEquals("Original", revised.text)
    assertEquals(true, revised.isPinned)
  }

  @Test
  fun copyWritableValsUpgradesDataClassValsToCopyWriteBackPaths() {
    val original = CopyWritableSnapshot(text = "Original", revision = 3)

    val revised = CopyWritableSnapshot.Paths.text.set(original, "Revised")

    assertEquals(CopyWritableSnapshot(text = "Original", revision = 3), original)
    assertEquals(CopyWritableSnapshot(text = "Revised", revision = 3), revised)
  }

  @Test
  fun metaAnnotationAndExternalRequestCanEnableCopyWritableVals() {
    val document = Document(Cell("Original"))
    val updatedDocument = Document.Paths.title.set(document, Cell("Revised"))
    assertEquals("Original", document.title.value)
    assertEquals("Revised", updatedDocument.title.value)

    val requested = RequestedDocument("Original")
    val updatedRequested = RequestedDocument.GeneratedPaths.title.set(requested, "Revised")
    assertEquals(RequestedDocument("Original"), requested)
    assertEquals(RequestedDocument("Revised"), updatedRequested)
    assertEquals("id", RequestedDocument.GeneratedPaths.identifier(requested))
    assertEquals(false, RequestedDocument.GeneratedPaths.identifier is dev.kpaths.core.WritableKeyPath<*, *>)
    assertEquals(2, RequestedDocument.GeneratedPaths.revision.set(requested, 2).revision)
    assertEquals(0, requested.revision)
    assertEquals(false, RequestedEvent.GeneratedPaths.ProtectedPaths.value is dev.kpaths.core.WritableKeyPath<*, *>)
    assertEquals(RequestedEvent.Editable("new"),
      RequestedEvent.GeneratedPaths.EditablePaths.value.set(RequestedEvent.Editable("old"), "new"))
  }

  @Test
  fun generatedPropertyPathPreservesNullableTypeIdentity() {
    val path = OptionalDocument.Paths.title

    assertEquals(null, path(OptionalDocument(null)))
    assertEquals(TypeId("kotlin.String", nullable = true), path.__kpaths_valueType)
  }

  @Test
  fun generatedPropertyPathPreservesGenericTypeIdentityWithoutReflection() {
    val path = Document.Paths.title

    assertEquals(
      TypeId(
        "dev.kpaths.compiler.fixture.Cell",
        listOf(TypeArgument.invariant(TypeId("kotlin.String"))),
      ),
      path.__kpaths_valueType,
    )
    assertEquals("Ada", path(Document(Cell("Ada"))).value)
  }

  @Test
  fun generatedPropertyPathPreservesNestedNullabilityAndProjections() {
    assertEquals(
      TypeId(
        "dev.kpaths.compiler.fixture.Cell",
        listOf(TypeArgument.invariant(TypeId("kotlin.String", nullable = true))),
      ),
      ProjectedDocument.Paths.nullableTitle.__kpaths_valueType,
    )
    assertEquals(
      TypeId(
        "kotlin.collections.MutableList",
        listOf(TypeArgument.out(TypeId("kotlin.String"))),
      ),
      ProjectedDocument.Paths.names.__kpaths_valueType,
    )
    assertEquals(
      TypeId(
        "kotlin.collections.List",
        listOf(TypeArgument.star),
      ),
      ProjectedDocument.Paths.unknown.__kpaths_valueType,
    )
  }

  private fun assertDirectGeneratedImplementation(path: Any, expectedBase: Class<*>): ClassBytecode {
    val implementationClass = path.javaClass
    assertEquals(expectedBase, implementationClass.superclass)
    assertTrue(
      implementationClass.name.contains("\$__KPaths_") ||
        implementationClass.name.contains("\$Paths\$"),
    )
    val resourceName = implementationClass.name.replace('.', '/') + ".class"
    val classFile = checkNotNull(implementationClass.classLoader.getResourceAsStream(resourceName)) {
      "Could not read generated implementation class '$resourceName'."
    }.use { ClassBytecode.read(it.readBytes()) }
    assertEquals(expectedBase.name.replace('.', '/'), classFile.superName)
    assertFalse(classFile.references.any { it.startsWith("kotlin/reflect/") })
    assertFalse(classFile.references.any { it == "dev/kpaths/core/KeyPathKt" })
    assertFalse(classFile.references.any { it == "dev/kpaths/core/CaseKeyPathKt" })
    return classFile
  }

  private data class ClassBytecode(
    val superName: String?,
    val methods: Map<Pair<String, String>, MethodBytecode>,
    val references: Set<String>,
  ) {
    fun method(name: String, descriptor: String): MethodBytecode =
      checkNotNull(methods[name to descriptor]) { "Expected bytecode method $name$descriptor." }

    companion object {
      fun read(bytes: ByteArray): ClassBytecode {
        var superName: String? = null
        val methods = mutableMapOf<Pair<String, String>, MethodBytecode>()
        val references = mutableSetOf<String>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
          override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superNameValue: String?,
            interfaces: Array<out String>?,
          ) {
            superName = superNameValue
            superNameValue?.let(references::add)
            interfaces?.forEach(references::add)
          }

          override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?,
          ): MethodVisitor {
            val method = MethodBytecode()
            methods[name to descriptor] = method
            return object : MethodVisitor(Opcodes.ASM9) {
              override fun visitTypeInsn(opcode: Int, type: String) {
                references += type
                method.typeInstructions += TypeInstruction(opcode, type)
              }

              override fun visitMethodInsn(
                opcode: Int,
                owner: String,
                name: String,
                descriptor: String,
                isInterface: Boolean,
              ) {
                references += owner
                method.methodCalls += MethodCall(owner, name)
              }

              override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
                references += owner
                method.fieldAccesses += FieldAccess(opcode, owner, name)
              }
            }
          }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return ClassBytecode(superName, methods, references)
      }
    }
  }

  private data class MethodBytecode(
    val typeInstructions: MutableSet<TypeInstruction> = mutableSetOf(),
    val methodCalls: MutableSet<MethodCall> = mutableSetOf(),
    val fieldAccesses: MutableSet<FieldAccess> = mutableSetOf(),
  )

  private data class TypeInstruction(val opcode: Int, val type: String)
  private data class MethodCall(val owner: String, val name: String)
  private data class FieldAccess(val opcode: Int, val owner: String, val name: String)
}
