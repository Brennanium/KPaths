package dev.kpaths.ksp

import dev.knapshots.testing.ksp.GradleKspHost
import dev.knapshots.testing.ksp.KspDependency
import dev.knapshots.testing.ksp.KspRequest
import dev.knapshots.testing.ksp.KspSource
import java.io.File
import java.nio.file.Path

/** Bridges the existing processor contracts to Knapshots while preserving their call sites. */
internal fun compileFixture(
  fileName: String,
  source: String,
  expectFailure: Boolean,
  runMain: Boolean = false,
  dependencySource: String? = null,
): String {
  fun paths(key: String) = System.getProperty(key).orEmpty().split(File.pathSeparator)
    .filter(String::isNotBlank).map(Path::of)
  val host = GradleKspHost(
    checkNotNull(System.getProperty("knapshots.ksp.version")),
    paths("knapshots.ksp.processorClasspath"), paths("knapshots.ksp.classpath"),
  )
  val dependencies = dependencySource?.let {
    listOf(KspDependency("adapters", listOf(KspSource("Reader.kt", it))))
  }.orEmpty()
  val result = host.process(KspRequest(
    listOf(KspSource(fileName, source)), checkNotNull(System.getProperty("knapshots.ksp.kotlinVersion")),
    dependencies, if (runMain) "fixture.DeclarativeAdapterKt" else null,
  ))
  check(result.succeeded != expectFailure) { "Unexpected KSP compilation result:\n${result.output}" }
  if (runMain && !expectFailure) check(result.executionSucceeded == true) { "Fixture execution failed:\n${result.output}" }
  return result.output
}
