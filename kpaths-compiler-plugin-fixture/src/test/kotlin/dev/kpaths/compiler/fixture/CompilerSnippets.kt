package dev.kpaths.compiler.fixture

import dev.knapshots.testing.compiler.CompilerRequest
import dev.knapshots.testing.compiler.CompilerSource
import dev.knapshots.testing.compiler.EmbeddedCompilerHost
import java.io.File
import java.nio.file.Path

internal data class SnippetResult(val succeeded: Boolean, val messages: String, val classLoader: ClassLoader)

/** Compile source using the same Knapshots host as the compiler assertions. */
internal fun compileSnippet(vararg sources: Pair<String, String>): SnippetResult = compile(false, *sources)

/** Include the independently registered contributor compiler plugin. */
internal fun compileSnippetWithContributor(vararg sources: Pair<String, String>): SnippetResult = compile(true, *sources)

private fun compile(contributor: Boolean, vararg sources: Pair<String, String>): SnippetResult {
  val version = checkNotNull(System.getProperty("kpaths.testKotlinVersion"))
  val plugin = checkNotNull(System.getProperty("kpaths.snippet.pluginJar"))
  val contributorJar = checkNotNull(System.getProperty("kpaths.snippet.contributorJar"))
  val classpath = checkNotNull(System.getProperty("kpaths.snippet.classpath"))
    .split(File.pathSeparator).filter(String::isNotBlank).map(Path::of)
  val arguments = listOf("-jvm-target", "11") +
    (if (contributor) listOf("-Xplugin=$contributorJar") else emptyList()) + listOf("-Xplugin=$plugin")
  val result = EmbeddedCompilerHost(classpath, arguments).compile(CompilerRequest(
    sources.map { (name, text) -> CompilerSource(name, text.trimIndent()) }, version, emptySet(),
  ))
  return SnippetResult(result.succeeded, result.diagnostics.joinToString("\n") { it.message }, result.classLoader())
}
