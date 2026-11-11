package dev.kpaths.ide

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.fir.extensions.KotlinBundledFirCompilerPluginProvider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class KefsIntegrationTest : BasePlatformTestCase() {
  fun testKefsResolvesAndHighlightsKPaths() {
    val loader = checkNotNull(checkNotNull(PluginManagerCore.getPlugin(PluginId.getId("com.github.mr3zee.kotlinPlugins"))).pluginClassLoader)
    fun cls(name: String): Class<*> = loader.loadClass("com.github.mr3zee.kefs.$name")
    fun construct(name: String, vararg args: Any?): Any = cls(name).declaredConstructors
      .first { it.parameterCount == args.size }.apply { isAccessible = true }.newInstance(*args)
    fun enumValue(name: String, value: String): Any = cls(name).enumConstants.first { (it as Enum<*>).name == value }
    val versionService = checkNotNull(ApplicationManager.getApplication().getService(cls("KotlinVersionService")))
    val ideVersion = cls("KotlinVersionService").getMethod("getKotlinIdePluginVersion").invoke(versionService) as String
    System.getProperty("kpaths.expectedIdeKotlinVersion")?.let { expected ->
      assertEquals("The publication version must match the compiler version reported by KEFS", expected, ideVersion)
    }
    val testVersion = "0.1.0-test${System.nanoTime()}"
    val contributorVersion = "0.2.0-test${System.nanoTime()}"
    val repoRoot = Files.createTempDirectory("kpaths-kefs-repo")
    val artifactDir = repoRoot.resolve("io/github/brennanium/kpaths-compiler-plugin-jvm")
    val requested = artifactDir.resolve("$testVersion/kpaths-compiler-plugin-jvm-$testVersion.jar")
    Files.createDirectories(requested.parent)
    Files.copy(Path.of(checkNotNull(System.getProperty("kpaths.buildCompilerPluginJar"))), requested, StandardCopyOption.REPLACE_EXISTING)
    val ideArtifact = artifactDir.resolve("$ideVersion-$testVersion/kpaths-compiler-plugin-jvm-$ideVersion-$testVersion.jar")
    Files.createDirectories(ideArtifact.parent)
    val candidate = Path.of(System.getProperty("kpaths.ideCandidateJar") ?: checkNotNull(System.getProperty("kpaths.ideCompilerPluginJar")))
    Files.copy(candidate, ideArtifact, StandardCopyOption.REPLACE_EXISTING)
    Files.writeString(artifactDir.resolve("maven-metadata.xml"), """
      <metadata><groupId>io.github.brennanium</groupId><artifactId>kpaths-compiler-plugin-jvm</artifactId>
      <versioning><versions><version>$ideVersion-$testVersion</version></versions></versioning></metadata>
    """.trimIndent())
    val contributorDir = repoRoot.resolve("io/github/brennanium/kpaths-compiler-plugin-contributor-jvm")
    val contributorRequested = contributorDir.resolve("$contributorVersion/kpaths-compiler-plugin-contributor-jvm-$contributorVersion.jar")
    Files.createDirectories(contributorRequested.parent)
    Files.copy(Path.of(checkNotNull(System.getProperty("kpaths.buildContributorJar"))), contributorRequested, StandardCopyOption.REPLACE_EXISTING)
    val ideContributor = contributorDir.resolve("$ideVersion-$contributorVersion/kpaths-compiler-plugin-contributor-jvm-$ideVersion-$contributorVersion.jar")
    Files.createDirectories(ideContributor.parent)
    Files.copy(Path.of(checkNotNull(System.getProperty("kpaths.ideContributorJar"))), ideContributor, StandardCopyOption.REPLACE_EXISTING)
    Files.writeString(contributorDir.resolve("maven-metadata.xml"), """
      <metadata><groupId>io.github.brennanium</groupId><artifactId>kpaths-compiler-plugin-contributor-jvm</artifactId>
      <versioning><versions><version>$ideVersion-$contributorVersion</version></versions></versioning></metadata>
    """.trimIndent())
    val repo = construct("KotlinArtifactsRepository", "kpaths local", repoRoot.toString(), enumValue("KotlinArtifactsRepository\$Type", "PATH"))
    val id = construct("MavenId", "io.github.brennanium:kpaths-compiler-plugin-jvm")
    val contributorId = construct("MavenId", "io.github.brennanium:kpaths-compiler-plugin-contributor-jvm")
    val descriptor = construct("KotlinPluginDescriptor", "KPaths", listOf(id), enumValue("KotlinPluginDescriptor\$VersionMatching", "EXACT"), true, false, listOf(repo), null)
    val contributorDescriptor = construct("KotlinPluginDescriptor", "Contributor", listOf(contributorId), enumValue("KotlinPluginDescriptor\$VersionMatching", "EXACT"), true, false, listOf(repo), null)
    val settingsClass = cls("KefsSettings")
    val settings = checkNotNull(project.getService(settingsClass))
    settingsClass.getMethod("updateToNewState", List::class.java, List::class.java).invoke(settings, listOf(repo), listOf(descriptor, contributorDescriptor))
    val providerClass = cls("KefsProvider")
    val provider = providerClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as KotlinBundledFirCompilerPluginProvider
    fun awaitResolution(path: Path): Path {
      var resolved: Path? = null
      val deadline = System.currentTimeMillis() + 30000
      while (resolved == null && System.currentTimeMillis() < deadline) {
        resolved = provider.provideBundledPluginJar(project, path)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        if (resolved == null) Thread.sleep(1200)
      }
      return checkNotNull(resolved) { "KEFS did not resolve $path for IDE compiler $ideVersion" }
    }
    val resolvedKPaths = awaitResolution(requested)
    val resolvedContributor = awaitResolution(contributorRequested)
    assertFalse("KEFS must substitute the IDE build", requested == resolvedKPaths)
    assertFalse("KEFS must substitute the contributor IDE build", contributorRequested == resolvedContributor)
    assertEquals("KEFS must resolve the IDE-compatible KPaths artifact", -1L, Files.mismatch(ideArtifact, resolvedKPaths))
    assertEquals("KEFS must resolve the IDE-compatible contributor artifact", -1L, Files.mismatch(ideContributor, resolvedContributor))
    val libraries = listOf("kpaths.ideKotlinStdlib", "kpaths.coreJar", "kpaths.annotationsJar")
      .map { checkNotNull(System.getProperty(it)) }
    VfsRootAccess.allowRootAccess(testRootDisposable, *libraries.toTypedArray())
    libraries.forEach { PsiTestUtil.addLibrary(myFixture.module, it) }
    KotlinCommonCompilerArgumentsHolder.getInstance(project).update { pluginClasspaths = arrayOf(requested.toString(), contributorRequested.toString()) }
    val scenario = System.getProperty("kpaths.kefsScenario", "full")
    val code = when (scenario) {
      "properties" -> """
        package dev.kpaths.compiler.fixture
        import dev.kpaths.annotations.Pathable
        import dev.kpaths.core.*
        @Pathable data class Person(var name: String)
        val name: WritableKeyPath<Person, String> = Person.Paths.name
      """
      "cases" -> """
        package dev.kpaths.compiler.fixture
        import dev.kpaths.annotations.Pathable
        import dev.kpaths.core.*
        @Pathable sealed interface Event { data class Message(val text: String) : Event }
        val text: CaseKeyPath<Event, String> = Event.Paths.Message.text
      """
      "full" -> """
      package dev.kpaths.compiler.fixture
      import dev.kpaths.annotations.Pathable
      import dev.kpaths.core.*
      @Pathable data class Person(var name: String)
      @Pathable sealed interface Event { data class Message(val text: String) : Event }
      val name: WritableKeyPath<Person, String> = Person.Paths.name
      val text: CaseKeyPath<Event, String> = Event.Paths.Message.text
      class TrackedRecord
      class DraftAddress
      val draftName: ReferenceWritableKeyPath<TrackedRecord.Draft, String> = TrackedRecord.Draft.Paths.name
      val address: ReferenceWritableKeyPath<TrackedRecord.Draft, DraftAddress> = TrackedRecord.Draft.Paths.address
      """
      else -> error("Unknown KEFS test scenario: $scenario")
    }
    myFixture.configureByText("Consumer.kt", code.trimIndent())
    val errors = myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }.map { it.description }
      .filterNot { it.contains("MISSING_DEPENDENCY") }
    assertEmpty(errors)
  }
}
