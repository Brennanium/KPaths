package dev.kpaths.ksp

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir

class KPathsProcessorIntegrationTest {
  @Test
  fun `external processor can replace an eligible generated property path implementation`(
    @TempDir fixtureDirectory: Path,
  ) {
    val projectDirectory = Path.of(checkNotNull(System.getProperty("kpaths.projectDir")))
    val annotationsJar = projectDirectory.jvmArtifact("kpaths-annotations")
    val coreJar = projectDirectory.jvmArtifact("kpaths-core")
    val codegenJar = projectDirectory.jvmArtifact("kpaths-ksp-codegen")

    Files.writeString(
      fixtureDirectory.resolve("settings.gradle.kts"),
      """
        pluginManagement {
          repositories {
            gradlePluginPortal()
            mavenCentral()
          }
        }
        dependencyResolutionManagement {
          repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
          repositories {
            mavenCentral()
          }
        }
        rootProject.name = "adapter-kpaths-fixture"
        include(":adapter-processor")
      """.trimIndent(),
    )
    Files.writeString(
      fixtureDirectory.resolve("build.gradle.kts"),
      """
        plugins {
          kotlin("jvm") version "2.4.21"
          id("com.google.devtools.ksp") version "2.3.12"
        }

        dependencies {
          implementation(files("${coreJar.gradlePath()}"))
          ksp(project(":adapter-processor"))
        }
      """.trimIndent(),
    )
    val processorDirectory = Files.createDirectories(fixtureDirectory.resolve("adapter-processor"))
    Files.writeString(
      processorDirectory.resolve("build.gradle.kts"),
      """
        plugins {
          kotlin("jvm") version "2.4.21"
        }

        dependencies {
          implementation("com.google.devtools.ksp:symbol-processing-api:2.3.12")
          implementation("com.squareup:kotlinpoet:2.2.0")
          implementation(files("${annotationsJar.gradlePath()}"))
          implementation(files("${codegenJar.gradlePath()}"))
        }
      """.trimIndent(),
    )
    val processorSourceDirectory = Files.createDirectories(
      processorDirectory.resolve("src/main/kotlin/fixtureadapter"),
    )
    Files.writeString(
      processorSourceDirectory.resolve("SharedProcessor.kt"),
      """
        package fixtureadapter

        import com.google.devtools.ksp.processing.CodeGenerator
        import com.google.devtools.ksp.processing.KSPLogger
        import com.google.devtools.ksp.processing.Resolver
        import com.google.devtools.ksp.processing.SymbolProcessor
        import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
        import com.google.devtools.ksp.processing.SymbolProcessorProvider
        import com.google.devtools.ksp.symbol.KSAnnotated
        import com.google.devtools.ksp.symbol.KSClassDeclaration
        import com.squareup.kotlinpoet.ClassName
        import com.squareup.kotlinpoet.CodeBlock
        import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
        import com.squareup.kotlinpoet.ParameterizedTypeName
        import dev.kpaths.ksp.codegen.KPathsCatalogGenerator
        import dev.kpaths.ksp.codegen.KPathsCatalogRequest
        import dev.kpaths.ksp.codegen.KPathsPathCapability
        import dev.kpaths.ksp.codegen.KPathsPropertyPathAdapter
        import dev.kpaths.ksp.codegen.KPathsPropertyPathRecipe

        private const val marker = "fixture.PathAdapted"

        class SharedProcessorProvider : SymbolProcessorProvider {
          override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
            SharedProcessor(environment.codeGenerator, environment.logger)
        }

        private class SharedProcessor(
          codeGenerator: CodeGenerator,
          private val logger: KSPLogger,
        ) : SymbolProcessor {
          private val generator = KPathsCatalogGenerator(codeGenerator, logger)

          override fun process(resolver: Resolver): List<KSAnnotated> =
            generator.generate(
              resolver,
              resolver.getSymbolsWithAnnotation(marker)
                .filterIsInstance<KSClassDeclaration>()
                .map { declaration ->
                  KPathsCatalogRequest(
                    declaration = declaration,
                    generatedName = declaration.simpleName.asString() + "Paths",
                    annotationQualifiedName = marker,
                    annotationDisplayName = "@PathAdapted",
                    propertyPathAdapters = listOf(SharedValuePathAdapter),
                  )
                }
                .toList(),
            )
        }

        private object SharedValuePathAdapter : KPathsPropertyPathAdapter {
          override fun pathRecipe(context: dev.kpaths.ksp.codegen.KPathsPropertyPathContext): KPathsPropertyPathRecipe? {
            val sharedType = context.declaration.type.resolve()
            if (sharedType.declaration.qualifiedName?.asString() != "fixture.Shared") return null
            val valueType = sharedType.arguments.singleOrNull()?.type?.resolve() ?: return null
            val value = (context.sourceValueType as? ParameterizedTypeName)
              ?.typeArguments
              ?.singleOrNull()
              ?: return null
            return KPathsPropertyPathRecipe(
              publicPathType = ClassName("fixture", "WritableSharedValueKeyPath")
                .parameterizedBy(context.rootType, value),
              implementationBaseType = ClassName("fixture", "WritableSharedValueKeyPathBase")
                .parameterizedBy(context.rootType, value),
              constructorArguments = listOf(
                context.pathId(value),
                context.typeId(valueType),
                CodeBlock.of("{ root -> %L }", context.propertyAccess(CodeBlock.of("root"))),
              ),
              capability = KPathsPathCapability.WRITABLE,
            )
          }
        }
      """.trimIndent(),
    )
    val serviceDirectory = Files.createDirectories(
      processorDirectory.resolve("src/main/resources/META-INF/services"),
    )
    Files.writeString(
      serviceDirectory.resolve("com.google.devtools.ksp.processing.SymbolProcessorProvider"),
      "fixtureadapter.SharedProcessorProvider\n",
    )
    val sourceDirectory = Files.createDirectories(fixtureDirectory.resolve("src/main/kotlin/fixture"))
    Files.writeString(
      sourceDirectory.resolve("State.kt"),
      """
        package fixture

        import dev.kpaths.core.PathId
        import dev.kpaths.core.TypeId
        import dev.kpaths.core.WritableKeyPath

        @Target(AnnotationTarget.CLASS)
        annotation class PathAdapted

        class Shared<Value>(var value: Value)

        interface WritableSharedValueKeyPath<Root, Value> : WritableKeyPath<Root, Value>

        abstract class WritableSharedValueKeyPathBase<Root, Value>(
          override val __kpaths_id: PathId,
          override val __kpaths_valueType: TypeId,
          private val resolveShared: (Root) -> Shared<Value>,
        ) : WritableSharedValueKeyPath<Root, Value> {
          override fun get(root: Root): Value = resolveShared(root).value
          override fun set(root: Root, value: Value): Root {
            resolveShared(root).value = value
            return root
          }
        }

        data class Profile(val name: String)

        @PathAdapted
        data class State(val profile: Shared<Profile>) {
          companion object
        }

        @PathAdapted
        data class Box<Value>(val shared: Shared<Value>) {
          companion object Paths
        }

        @PathAdapted
        sealed interface Destination {
          data class Sheet(val profile: Shared<Profile>) : Destination

          companion object
        }

        fun verifyAdapter() {
          val path: WritableSharedValueKeyPath<State, Profile> = State.Paths.profile
          val state = State(Shared(Profile("before")))
          check(path.get(state) == Profile("before"))
          check(path.set(state, Profile("after")) === state)
          check(state.profile.value == Profile("after"))

          val boxPath: WritableSharedValueKeyPath<Box<Profile>, Profile> =
            Box.Paths.of<Profile>().shared

          val sheetPath: WritableSharedValueKeyPath<Destination.Sheet, Profile> =
            Destination.Paths.SheetPaths.profile
          val sheet = Destination.Sheet(Shared(Profile("before")))
          check(sheetPath.set(sheet, Profile("after")) === sheet)
          check(sheet.profile.value == Profile("after"))
        }
      """.trimIndent(),
    )

    GradleRunner.create()
      .withProjectDir(fixtureDirectory.toFile())
      .withArguments("compileKotlin", "--stacktrace")
      .build()
  }

  @Test
  fun `reports an unnamed bare companion before later class members`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "AmbiguousBareCompanion.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        sealed interface Event {
          companion object

          data object Idle : Event
        }
      """.trimIndent(),
    )

    assertContains(output, "AmbiguousBareCompanion.kt:")
    assertContains(output, "[KPaths] An unnamed `companion object` must be the final member")
    assertContains(output, "Move it to the end of the class body")
  }

  @Test
  fun `reports companion Paths member collisions`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "CompanionPathsCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        data class Person(val name: String) {
          companion object {
            val Paths = "existing"
          }
        }
      """.trimIndent(),
    )

    assertContains(output, "CompanionPathsCollision.kt:")
    assertContains(output, "[KPaths] Cannot generate Person.Companion.Paths")
    assertContains(output, "already declares a member named 'Paths'")
  }

  @Test
  fun `reports companion Paths extension collisions`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "CompanionPathsExtensionCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        data class Person(val name: String) {
          companion object
        }

        val Person.Companion.Paths: String
          get() = "existing"
      """.trimIndent(),
    )

    assertContains(output, "CompanionPathsExtensionCollision.kt:")
    assertContains(output, "[KPaths] Cannot generate Person.Companion.Paths because the top-level extension")
    assertContains(output, "already declares it")
  }

  @Test
  fun `reports generic named Paths factory collisions`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "GenericCompanionPathsFunctionCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        data class Box<Value>(val value: Value) {
          companion object Paths {
            fun <Value> of(): String = "existing"
          }
        }
      """.trimIndent(),
    )

    assertContains(output, "GenericCompanionPathsFunctionCollision.kt:")
    assertContains(output, "[KPaths] Cannot generate Box.Paths.of")
    assertContains(output, "already declares a member named 'of'")
  }

  @Test
  fun `reports invalid generated case and property catalog names`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "InvalidCaseNames.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.CaseName
        import dev.kpaths.annotations.Pathable

        @Pathable
        sealed interface Event {
          @CaseName("not a name")
          data object Idle : Event
        }
      """.trimIndent(),
    )

    assertContains(output, "InvalidCaseNames.kt:")
    assertContains(output, "[KPaths] @CaseName value 'not a name'")
    assertContains(output, "must be a single Kotlin identifier")
  }

  @Test
  fun `reports invalid generated property names`(
    @TempDir fixtureDirectory: Path,
  ) {
    val invalidOutput = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "InvalidPathName.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable
        import dev.kpaths.annotations.PathName

        @Pathable
        data class Profile(
          @PathName("not a name") val legalName: String,
        )
      """.trimIndent(),
    )

    assertContains(invalidOutput, "InvalidPathName.kt:")
    assertContains(invalidOutput, "[KPaths] @PathName value 'not a name'")

  }

  @Test
  fun `reports colliding generated property names`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "PathNameCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable
        import dev.kpaths.annotations.PathName

        @Pathable
        data class Profile(
          @PathName("name") val legalName: String,
          val name: String,
        )
      """.trimIndent(),
    )

    assertContains(output, "PathNameCollision.kt:")
    assertContains(output, "'name' would name both")
    assertContains(output, "property 'legalName'")
    assertContains(output, "property 'name'")
  }

  @Test
  fun `reports generated case member and property catalog collisions`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "CaseNameCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.CaseName
        import dev.kpaths.annotations.Pathable
        import dev.kpaths.annotations.PathsName

        @Pathable
        sealed interface Event {
          @CaseName("MessagePaths")
          data object Idle : Event

          @PathsName("MessagePaths")
          data class Message(val text: String) : Event
        }
      """.trimIndent(),
    )

    assertContains(output, "CaseNameCollision.kt:")
    assertContains(output, "[KPaths] Cannot generate paths because 'MessagePaths' would name both")
    assertContains(output, "the case path for 'Idle'")
    assertContains(output, "the property-path namespace for case 'Message'")
  }

  @Test
  fun `reports an actionable error at Pathable for unsupported generic sealed variants`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "InvalidLoadable.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        sealed interface InvalidLoadable<Value> {
          data object Idle : InvalidLoadable<Nothing>
        }
      """.trimIndent(),
    )

    assertContains(output, "InvalidLoadable.kt:")
    assertContains(output, "[KPaths] Cannot generate 'Idle'")
    assertContains(output, "must forward that type parameter, or refine it to Nothing through an `out` parameter")
  }

  @Test
  fun `reports use of KPaths reserved internal prefix at Pathable`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "ReservedPrefix.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        data class ReservedPrefix(val __kpaths_rootType: String)
      """.trimIndent(),
    )

    assertContains(output, "ReservedPrefix.kt:")
    assertContains(output, "[KPaths] Cannot generate paths because property '__kpaths_rootType'")
    assertContains(output, "uses the reserved '__kpaths_' prefix")
  }

  @Test
  fun `reports property and case path collisions at Pathable`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "PropertyCaseCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        sealed class Connection {
          abstract val Connected: String

          data class Connected(override val Connected: String) : Connection()
        }
      """.trimIndent(),
    )

    assertContains(output, "PropertyCaseCollision.kt:")
    assertContains(output, "[KPaths] Cannot generate paths because 'Connected' would name both")
    assertContains(output, "the key path for property 'Connected' and the case path for 'Connected'")
  }

  @Test
  fun `reports generated case catalog collisions at Pathable`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "CaseCatalogCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        sealed interface Event {
          data object allCasePaths : Event
        }
      """.trimIndent(),
    )

    assertContains(output, "CaseCatalogCollision.kt:")
    assertContains(output, "[KPaths] Cannot generate paths because 'allCasePaths' would name both")
    assertContains(output, "the case path for 'allCasePaths' and the generated catalog of all case paths")
  }

  @Test
  fun `reports top level generated namespace collisions at Pathable`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "TopLevelCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        data class Person(val name: String)

        object PersonPaths
      """.trimIndent(),
    )

    assertContains(output, "TopLevelCollision.kt:")
    assertContains(output, "[KPaths] Cannot generate 'PersonPaths' because it conflicts with existing top-level")
    assertContains(output, "type 'fixture.PersonPaths'")
    assertContains(output, "@Pathable(\"CustomPersonPaths\")")
  }

  @Test
  fun `reports duplicate custom generated namespaces at Pathable`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "DuplicateCustomNames.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable("SharedPaths")
        data class First(val value: String)

        @Pathable("SharedPaths")
        data class Second(val value: Int)
      """.trimIndent(),
    )

    assertContains(output, "DuplicateCustomNames.kt:")
    assertContains(output, "[KPaths] Cannot generate 'SharedPaths' because @Pathable also requests that name")
    assertContains(output, "'fixture.First'")
    assertContains(output, "'fixture.Second'")
  }

  @Test
  fun `reports top level type alias collisions at Pathable`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "TypeAliasCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        data class Person(val name: String)

        typealias PersonPaths = String
      """.trimIndent(),
    )

    assertContains(output, "TypeAliasCollision.kt:")
    assertContains(output, "[KPaths] Cannot generate 'PersonPaths' because it conflicts with existing top-level")
    assertContains(output, "type alias 'fixture.PersonPaths'")
  }

  @Test
  fun `reports reserved prefixes on sealed cases at Pathable`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "ReservedCasePrefix.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        sealed interface ReservedCasePrefix {
          data object __kpaths_rootType : ReservedCasePrefix
        }
      """.trimIndent(),
    )

    assertContains(output, "ReservedCasePrefix.kt:")
    assertContains(output, "[KPaths] Cannot generate paths because case '__kpaths_rootType'")
    assertContains(output, "uses the reserved '__kpaths_' prefix")
  }

  @Test
  fun `reports generic companion collisions at Pathable`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "CompanionCollision.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        data class Box<Value>(val Companion: Value)
      """.trimIndent(),
    )

    assertContains(output, "CompanionCollision.kt:")
    assertContains(output, "[KPaths] Cannot generate paths because 'Companion' would name both")
    assertContains(output, "the generated generic-path companion and the key path for property 'Companion'")
  }

  @Test
  fun `reports generated source file collisions at Pathable`(
    @TempDir fixtureDirectory: Path,
  ) {
    val output = compileFailure(
      fixtureDirectory = fixtureDirectory,
      fileName = "PersonPaths.kt",
      source =
      """
        package fixture

        import dev.kpaths.annotations.Pathable

        @Pathable
        data class Person(val name: String)
      """.trimIndent(),
    )

    assertContains(output, "PersonPaths.kt:")
    assertContains(output, "[KPaths] Cannot generate 'PersonPaths.kt' because source file")
    assertContains(output, "@Pathable(\"CustomPersonPaths\")")
  }

  private fun compileFailure(
    fixtureDirectory: Path,
    fileName: String,
    source: String,
  ): String {
    val projectDirectory = Path.of(checkNotNull(System.getProperty("kpaths.projectDir")))
    val annotationsJar = projectDirectory.jvmArtifact("kpaths-annotations")
    val coreJar = projectDirectory.jvmArtifact("kpaths-core")
    val codegenJar = projectDirectory.jvmArtifact("kpaths-ksp-codegen")
    val processorJar = projectDirectory.jvmArtifact("kpaths-ksp")

    Files.writeString(
      fixtureDirectory.resolve("settings.gradle.kts"),
      """
        pluginManagement {
          repositories {
            gradlePluginPortal()
            mavenCentral()
          }
        }
        dependencyResolutionManagement {
          repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
          repositories {
            mavenCentral()
          }
        }
        rootProject.name = "invalid-kpaths-fixture"
      """.trimIndent(),
    )
    Files.writeString(
      fixtureDirectory.resolve("build.gradle.kts"),
      """
        plugins {
          kotlin("jvm") version "2.4.21"
          id("com.google.devtools.ksp") version "2.3.12"
        }

        dependencies {
          implementation(files("${annotationsJar.gradlePath()}"))
          implementation(files("${coreJar.gradlePath()}"))
          ksp(files(
            "${processorJar.gradlePath()}",
            "${codegenJar.gradlePath()}",
            "${annotationsJar.gradlePath()}",
          ))
        }
      """.trimIndent(),
    )
    val sourceDirectory = Files.createDirectories(fixtureDirectory.resolve("src/main/kotlin/fixture"))
    Files.writeString(sourceDirectory.resolve(fileName), source)

    return GradleRunner.create()
      .withProjectDir(fixtureDirectory.toFile())
      .withArguments("compileKotlin", "--stacktrace")
      .buildAndFail()
      .output
  }

  private fun Path.jvmArtifact(moduleName: String): Path {
    val librariesDirectory = resolve(moduleName).resolve("build/libs")
    return Files.list(librariesDirectory).use { artifacts ->
      artifacts
        .filter { artifact ->
          artifact.fileName.toString().let { name ->
            name.startsWith("$moduleName-jvm-") &&
              name.endsWith(".jar") &&
              !name.endsWith("-sources.jar") &&
              !name.endsWith("-javadoc.jar")
          }
        }
        .findFirst()
        .orElseThrow { error("Could not find the JVM publication for $moduleName in $librariesDirectory.") }
    }
  }

  private fun Path.gradlePath(): String = toString().replace("\\", "\\\\")
}
