package dev.kpaths.compiler.fixture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes

/**
 * Acceptance contracts for compiler-plugin capabilities that have deliberately not landed yet.
 *
 * Each test is a complete downstream Kotlin project: its source is the input, and successful
 * compilation is the output contract. Keep these disabled while their capability is absent. When
 * beginning one feature, enable its test first; it should fail before implementation and join the
 * ordinary fixture suite once it passes.
 */
@Tag("compiler-plugin-contract")
class FutureCompilerPluginContractTest {
  @Test
  fun `IR snapshot records generated bound payload factories`(@TempDir directory: Path) {
    val snapshot = directory.resolve("bound.ir.txt")
    compileSuccessfully(directory, """
      package fixture
      import dev.kpaths.annotations.Pathable
      @Pathable sealed interface Event {
        data class Message(val id: Int, val text: String) : Event
      }
    """, irSnapshot = snapshot)
    val ir = Files.readString(snapshot)
    assertContains(ir, "class fixture.Event.Paths.MessagePaths")
    assertContains(ir, "fun textFor(id: kotlin.Int): dev.kpaths.core.CaseKeyPath<fixture.Event, kotlin.String>")
    assertContains(ir, "fun idFor(text: kotlin.String): dev.kpaths.core.CaseKeyPath<fixture.Event, kotlin.Int>")
  }

  @Test
  fun `enum roots automatically conform to CasePathable`(@TempDir directory: Path) {
    compileSuccessfully(
      directory,
      """
        package fixture
        import dev.kpaths.annotations.Pathable
        import dev.kpaths.core.CasePathable

        @Pathable
        enum class Status { Offline, Online }

        fun check(value: Status): CasePathable<Status> = value
        val entry: CasePathable<Status> = Status.Online
      """,
    )
  }

  @Test
  fun `IR snapshot records the generated catalog and direct payload implementation`(
    @TempDir directory: Path,
  ) {
    val snapshot = directory.resolve("kpaths.ir.txt")
    compileSuccessfully(
      directory,
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        sealed interface Delivery {
          data object Refresh : Delivery
          data class Message(val text: String) : Delivery
        }
      """,
      irSnapshot = snapshot,
    )

    assertEquals(
      """
        KPaths post-generation IR

        class fixture.Delivery.Paths
          extends dev.kpaths.core.CasePathCatalogBase<fixture.Delivery>
          val Message: fixture.Delivery.Paths.Message
          val Refresh: fixture.Delivery.Paths.Refresh
          val allCasePaths: kotlin.collections.List<dev.kpaths.core.PartialCaseKeyPath<fixture.Delivery>>
          fun __kpaths_buildAllCasePaths(): kotlin.collections.List<dev.kpaths.core.PartialCaseKeyPath<fixture.Delivery>>

        class fixture.Delivery.Paths.Message
          extends dev.kpaths.core.CaseKeyPathBase<fixture.Delivery, fixture.Delivery.Message>
          val __kpaths_id: dev.kpaths.core.PathId
          val __kpaths_valueType: dev.kpaths.core.TypeId
          val text: dev.kpaths.core.CaseKeyPath<fixture.Delivery, kotlin.String>
          fun embed(value: fixture.Delivery.Message): fixture.Delivery
          fun match(root: fixture.Delivery): dev.kpaths.core.CaseMatch<fixture.Delivery.Message>

        class fixture.Delivery.Paths.Message.__KPaths_text_CasePath
          extends dev.kpaths.core.CaseKeyPathBase<fixture.Delivery, kotlin.String>
          val __kpaths_id: dev.kpaths.core.PathId
          val __kpaths_valueType: dev.kpaths.core.TypeId
          fun embed(value: kotlin.String): fixture.Delivery
          fun match(root: fixture.Delivery): dev.kpaths.core.CaseMatch<kotlin.String>

        class fixture.Delivery.Paths.MessagePaths
          extends kotlin.Any
          val text: dev.kpaths.core.KeyPath<fixture.Delivery.Message, kotlin.String>

        class fixture.Delivery.Paths.MessagePaths.__KPaths_text_Path
          extends dev.kpaths.core.KeyPathBase<fixture.Delivery.Message, kotlin.String>
          val __kpaths_id: dev.kpaths.core.PathId
          val __kpaths_valueType: dev.kpaths.core.TypeId
          fun get(root: fixture.Delivery.Message): kotlin.String
          fun get(root: kotlin.Any): kotlin.Any

        class fixture.Delivery.Paths.Refresh
          extends dev.kpaths.core.CaseKeyPathBase<fixture.Delivery, fixture.Delivery.Refresh>
          val __kpaths_id: dev.kpaths.core.PathId
          val __kpaths_valueType: dev.kpaths.core.TypeId
          val unit: dev.kpaths.core.CaseKeyPath<fixture.Delivery, kotlin.Unit>
          fun embed(value: fixture.Delivery.Refresh): fixture.Delivery
          fun match(root: fixture.Delivery): dev.kpaths.core.CaseMatch<fixture.Delivery.Refresh>

        class fixture.Delivery.Paths.Refresh.__KPaths_unit_CasePath
          extends dev.kpaths.core.CaseKeyPathBase<fixture.Delivery, kotlin.Unit>
          val __kpaths_id: dev.kpaths.core.PathId
          val __kpaths_valueType: dev.kpaths.core.TypeId
          fun embed(value: kotlin.Unit): fixture.Delivery
          fun match(root: fixture.Delivery): dev.kpaths.core.CaseMatch<kotlin.Unit>
      """.trimIndent() + "\n",
      Files.readString(snapshot),
    )
  }

  @Test
  fun `implemented contract - downstream source sees generated Paths without a companion`(
    @TempDir directory: Path,
  ) {
    compileSuccessfully(
      directory,
      """
        package fixture

        import dev.kpaths.annotations.Pathable
        import dev.kpaths.core.WritableKeyPath

        @Pathable
        data class Person(var name: String)

        val name: WritableKeyPath<Person, String> = Person.Paths.name
      """,
    )
  }

  @Test
  fun `generated case namespace exposes a direct payload CaseKeyPath`(@TempDir directory: Path) {
    compileSuccessfully(
      directory,
      """
        package fixture

        import dev.kpaths.annotations.Pathable
        import dev.kpaths.core.CaseKeyPath

        data class EditorAction(val query: String)

        @Pathable
        sealed interface ParentAction {
          data class Editor(val action: EditorAction) : ParentAction
        }

        val editorAction: CaseKeyPath<ParentAction, EditorAction> =
          ParentAction.Paths.Editor.action
      """,
    )
  }

  @Test
  fun `generated case object bytecode exposes a direct payload member`(@TempDir directory: Path) {
    val project = compileSuccessfully(
      directory,
      """
        package fixture

        import dev.kpaths.annotations.Pathable
        import dev.kpaths.core.CaseKeyPath

        data class EditorAction(val query: String)

        @Pathable
        sealed interface ParentAction {
          data class Editor(val action: EditorAction) : ParentAction
        }

        val editorAction: CaseKeyPath<ParentAction, EditorAction> =
          ParentAction.Paths.Editor.action
      """,
    )
    val editorClass = project.resolve(
      "build/classes/kotlin/main/fixture/ParentAction\$Paths\$Editor.class",
    )
    val references = mutableSetOf<String>()
    var superName: String? = null
    var hasActionGetter = false
    ClassReader(Files.readAllBytes(editorClass)).accept(object : ClassVisitor(Opcodes.ASM9) {
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
      }

      override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
      ) = object : org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {
        init {
          if (name == "getAction" && descriptor == "()Ldev/kpaths/core/CaseKeyPath;") {
            hasActionGetter = true
          }
        }

        override fun visitMethodInsn(
          opcode: Int,
          owner: String,
          name: String,
          descriptor: String,
          isInterface: Boolean,
        ) {
          references += owner
        }
      }
    }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    assertEquals("dev/kpaths/core/CaseKeyPathBase", superName)
    assertEquals(true, hasActionGetter)
    assertEquals(false, references.any { it.startsWith("kotlin/reflect/") })
    assertEquals(false, references.any { it == "dev/kpaths/core/CaseKeyPathKt" })
  }

  @Test
  fun `IR snapshot records generic sealed whole-case paths`(@TempDir directory: Path) {
    val snapshot = directory.resolve("generic-cases.ir.txt")
    compileSuccessfully(directory, """
      package fixture
      import dev.kpaths.annotations.Pathable
      @Pathable sealed interface Event<out T> {
        data object Idle : Event<Nothing>
        data class Value<T>(val value: T) : Event<T>
      }
      val path = Event.Paths<String>().Value
    """, irSnapshot = snapshot)
    val ir = Files.readString(snapshot)
    assertContains(ir, "val Value: fixture.Event.Paths.__kpaths_Catalog.ValueCasePath<T>")
    assertContains(ir, "class fixture.Event.Paths.__kpaths_Catalog.ValueCasePath")
    assertContains(ir, "extends dev.kpaths.core.CaseKeyPathBase<fixture.Event<T>, fixture.Event.Value<T>>")
    assertContains(ir, "fun embed(value: fixture.Event.Value<T>): fixture.Event<T>")
    assertContains(ir, "class fixture.Event.Paths.__kpaths_Catalog.ValueCasePath.__KPaths_value_CasePath")
    assertContains(ir, "extends dev.kpaths.core.CaseKeyPathBase<fixture.Event<T>, T>")
    assertContains(ir, "class fixture.Event.Paths.__kpaths_Catalog.ValuePropertyPaths")
    assertContains(ir, "val value: dev.kpaths.core.KeyPath<fixture.Event.Value<T>, T>")
  }

  @Test
  fun `IR snapshot records generic catalog and writable path implementation`(@TempDir directory: Path) {
    val snapshot = directory.resolve("generic.ir.txt")
    compileSuccessfully(directory, """
      package fixture
      import dev.kpaths.annotations.Pathable
      @Pathable data class Box<T>(var value: T)
      val path = Box.Paths<String>().value
    """, irSnapshot = snapshot)
    val ir = Files.readString(snapshot)
    assertContains(ir, "class fixture.Box.Paths.__kpaths_Catalog")
    assertContains(ir, "fun of(TTypeId: dev.kpaths.core.TypeId): fixture.Box.Paths.__kpaths_Catalog<T>")
    assertContains(ir, "val value: dev.kpaths.core.WritableKeyPath<fixture.Box<T>, T>")
    assertContains(ir, "extends dev.kpaths.core.WritableKeyPathBase<fixture.Box<T>, T>")
    assertContains(ir, "fun set(root: fixture.Box<T>, value: T): fixture.Box<T>")
  }

  @Test
  fun `generic root Paths factory retains root and focus types`(@TempDir directory: Path) {
    compileSuccessfully(
      directory,
      """
        package fixture

        import dev.kpaths.annotations.Pathable
        import dev.kpaths.core.KeyPath

        @Pathable
        data class Box<Value>(val value: Value)

        val value: KeyPath<Box<String>, String> = Box.Paths<String>().value
      """,
    )
  }

  @Test
  fun `generic adapter factories preserve their specialized path type`(@TempDir directory: Path) {
    compileSuccessfully(
      directory,
      """
        package fixture

        import dev.kpaths.annotations.*
        import dev.kpaths.core.*

        @PathsAdapter(factory = CellFactory::class)
        class Cell<T>(val value: T)
        object CellFactory : KPathsAdapterFactory {
          fun <Root, Value> make(storage: KeyPath<Root, Cell<Value>>): CellPath<Root, Value> = CellPath(storage)
        }
        class CellPath<Root, Value>(private val storage: KeyPath<Root, Cell<Value>>) : KeyPathBase<Root, Value>() {
          override val __kpaths_id get() = storage.__kpaths_id
          override val __kpaths_valueType get() = storage.__kpaths_valueType.requireTypeArgument(0)
          override fun get(root: Root): Value = storage.get(root).value
        }
        @Pathable data class Box<T>(val value: Cell<T>)
        val value: CellPath<Box<String>, String> = Box.Paths<String>().value
      """,
    )
  }

  @Disabled("Acceptance contract: catalogs discover same-package sealed inheritors.")
  @Test
  fun `package level sealed inheritor is included in its root catalog`(@TempDir directory: Path) {
    compileSuccessfully(
      directory,
      """
        package fixture

        import dev.kpaths.annotations.Pathable
        import dev.kpaths.core.CaseKeyPath

        @Pathable
        sealed interface ParentAction

        data class Editor(val query: String) : ParentAction

        val editor: CaseKeyPath<ParentAction, Editor> = ParentAction.Paths.Editor
      """,
    )
  }

  @Test
  fun `generated catalog exposes all case paths`(@TempDir directory: Path) {
    compileSuccessfully(
      directory,
      """
        package fixture

        import dev.kpaths.annotations.Pathable
        import dev.kpaths.core.PartialCaseKeyPath

        @Pathable
        sealed interface Delivery {
          data object Refresh : Delivery
          data class Message(val text: String) : Delivery
        }

        val all: List<PartialCaseKeyPath<Delivery>> = Delivery.Paths.allCasePaths
      """,
    )
  }

  private fun compileSuccessfully(
    directory: Path,
    source: String,
    irSnapshot: Path? = null,
  ): Path {
    val projectDirectory = Path.of(checkNotNull(System.getProperty("kpaths.projectDir")))
    val version = Files.readAllLines(projectDirectory.resolve("gradle.properties"))
      .single { it.startsWith("VERSION_NAME=") }
      .substringAfter('=')
    fun artifact(module: String): Path = projectDirectory
      .resolve(module)
      .resolve("build/libs/$module-jvm-$version.jar")

    val annotations = artifact("kpaths-annotations")
    val core = artifact("kpaths-core")
    val plugin = artifact("kpaths-compiler-plugin")
    val testKotlinVersion = checkNotNull(System.getProperty("kpaths.testKotlinVersion"))
    require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-dev-[0-9]+)?").matches(testKotlinVersion)) {
      "Unsupported test compiler version format: $testKotlinVersion"
    }
    val devRepositories = if ("-dev-" in testKotlinVersion) {
      """
        maven("https://packages.jetbrains.team/maven/p/kt/dev/")
        maven("https://packages.jetbrains.team/maven/p/kt/experimental/")
        maven("https://packages.jetbrains.team/maven/p/kt/bootstrap/")
      """.trimIndent()
    } else ""
    check(Files.isRegularFile(annotations) && Files.isRegularFile(core) && Files.isRegularFile(plugin)) {
      "Build KPaths JVM artifacts before running compiler-plugin acceptance contracts."
    }
    Files.writeString(
      directory.resolve("settings.gradle.kts"),
      """
        pluginManagement {
          repositories {
            $devRepositories
            gradlePluginPortal()
            mavenCentral()
          }
          ${if (devRepositories.isNotEmpty()) """
            resolutionStrategy.eachPlugin {
              if (requested.id.id == "org.jetbrains.kotlin.jvm") {
                useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${'$'}{requested.version}")
              }
            }
          """.trimIndent() else ""}
        }
        dependencyResolutionManagement {
          repositories {
            $devRepositories
            mavenCentral()
          }
        }
        rootProject.name = "kpaths-contract"
      """.trimIndent(),
    )
    Files.writeString(
      directory.resolve("build.gradle.kts"),
      """
        plugins { kotlin("jvm") version "$testKotlinVersion" }

        dependencies {
          implementation(files("${annotations.toGradlePath()}"))
          implementation(files("${core.toGradlePath()}"))
        }

        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
          compilerOptions.freeCompilerArgs.add("-Xplugin=${plugin.toGradlePath()}")
          providers.gradleProperty("kpaths.irSnapshot").orNull?.let { snapshot ->
            compilerOptions.freeCompilerArgs.addAll(
              listOf("-P", "plugin:dev.kpaths.compiler:irSnapshot=${'$'}snapshot"),
            )
          }
        }
      """.trimIndent(),
    )
    val sourceDirectory = Files.createDirectories(directory.resolve("src/main/kotlin/fixture"))
    Files.writeString(sourceDirectory.resolve("Contract.kt"), source.trimIndent())

    val snapshotOptions = irSnapshot?.let { listOf("-Pkpaths.irSnapshot=${it.toGradlePath()}") }.orEmpty()
    val result = GradleRunner.create()
      .withProjectDir(directory.toFile())
      .withArguments(
        "compileKotlin",
        "--stacktrace",
        *snapshotOptions.toTypedArray(),
      )
      .build()
    assertEquals(TaskOutcome.SUCCESS, result.task(":compileKotlin")?.outcome)
    return directory
  }

  private fun Path.toGradlePath(): String = toAbsolutePath().toString().replace("\\", "\\\\")
}
