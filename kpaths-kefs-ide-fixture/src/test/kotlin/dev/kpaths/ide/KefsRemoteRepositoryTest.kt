package dev.kpaths.ide

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.idea.fir.extensions.KotlinBundledFirCompilerPluginProvider
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Opt-in test against a real, separately published HTTPS Maven repository. */
class KefsRemoteRepositoryTest : BasePlatformTestCase() {
  fun testKefsResolvesExactVersionFromRemoteRepository() {
    val repositoryUrl = checkNotNull(System.getProperty("kpaths.remoteRepositoryUrl"))
    require(URI(repositoryUrl).scheme == "https") { "The test repository must use HTTPS" }
    val version = checkNotNull(System.getProperty("kpaths.remoteTestVersion"))
    val expected = Path.of(checkNotNull(System.getProperty("kpaths.remoteExpectedJar")))
    val loader = checkNotNull(checkNotNull(PluginManagerCore.getPlugin(PluginId.getId("com.github.mr3zee.kotlinPlugins"))).pluginClassLoader)
    fun cls(name: String): Class<*> = loader.loadClass("com.github.mr3zee.kefs.$name")
    fun construct(name: String, vararg args: Any?): Any = cls(name).declaredConstructors
      .first { it.parameterCount == args.size }.apply { isAccessible = true }.newInstance(*args)
    fun enumValue(name: String, value: String): Any = cls(name).enumConstants.first { (it as Enum<*>).name == value }
    val versionService = checkNotNull(ApplicationManager.getApplication().getService(cls("KotlinVersionService")))
    val ideVersion = cls("KotlinVersionService").getMethod("getKotlinIdePluginVersion").invoke(versionService) as String
    System.getProperty("kpaths.expectedIdeKotlinVersion")?.let { assertEquals(it, ideVersion) }

    // This represents the ordinary build-compiler plugin already on the consumer's classpath.
    val requested = Files.createTempDirectory("kpaths-remote-request")
      .resolve("io/github/brennanium/kpaths-compiler-plugin-jvm/$version/kpaths-compiler-plugin-jvm-$version.jar")
    Files.createDirectories(requested.parent)
    Files.copy(Path.of(checkNotNull(System.getProperty("kpaths.buildCompilerPluginJar"))), requested, StandardCopyOption.REPLACE_EXISTING)
    val repo = construct("KotlinArtifactsRepository", "KPaths remote test", repositoryUrl.trimEnd('/'), enumValue("KotlinArtifactsRepository\$Type", "URL"))
    val id = construct("MavenId", "io.github.brennanium:kpaths-compiler-plugin-jvm")
    val descriptor = construct("KotlinPluginDescriptor", "KPaths", listOf(id), enumValue("KotlinPluginDescriptor\$VersionMatching", "EXACT"), true, false, listOf(repo), null)
    val settingsClass = cls("KefsSettings")
    val settings = checkNotNull(project.getService(settingsClass))
    settingsClass.getMethod("updateToNewState", List::class.java, List::class.java).invoke(settings, listOf(repo), listOf(descriptor))
    val provider = cls("KefsProvider").getDeclaredConstructor().apply { isAccessible = true }.newInstance() as KotlinBundledFirCompilerPluginProvider
    var resolved: Path? = null
    val deadline = System.currentTimeMillis() + 90000
    while (resolved == null && System.currentTimeMillis() < deadline) {
      resolved = provider.provideBundledPluginJar(project, requested)
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
      if (resolved == null) Thread.sleep(1200)
    }
    val result = checkNotNull(resolved) { "KEFS did not resolve $version from $repositoryUrl for $ideVersion" }
    assertFalse("KEFS must substitute the build compiler JAR", requested == result)
    assertEquals("KEFS must download the expected IDE compiler JAR", -1L, Files.mismatch(expected, result))
    println("KEFS resolved $version for $ideVersion from $repositoryUrl; downloaded bytes match")
  }
}
