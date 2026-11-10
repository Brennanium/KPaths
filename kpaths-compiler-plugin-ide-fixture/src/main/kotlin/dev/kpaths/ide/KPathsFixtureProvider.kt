package dev.kpaths.ide

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.idea.fir.extensions.KotlinBundledFirCompilerPluginProvider
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** Experimental local provider: replaces the Gradle compiler JAR with an IDE-built JAR. */
@Suppress("UnstableApiUsage", "JetBrainsInternalApiUsage")
class KPathsFixtureProvider : KotlinBundledFirCompilerPluginProvider {
  override fun provideBundledPluginJar(project: Project, userSuppliedPluginJar: Path): Path? {
    val requested = Path.of(checkNotNull(System.getProperty("kpaths.buildCompilerPluginJar")))
    if (userSuppliedPluginJar.toAbsolutePath().normalize() != requested.toAbsolutePath().normalize()) return null
    requests.incrementAndGet()
    return Path.of(checkNotNull(System.getProperty("kpaths.ideCompilerPluginJar")))
  }

  companion object {
    val requests = AtomicInteger()
  }
}
