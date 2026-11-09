@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package dev.kpaths.compiler.fixture

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import dev.kpaths.compiler.PathsPluginRegistrar

/** Compile one or more source snippets with the KPaths plugin in process. */
internal fun compileSnippet(vararg sources: Pair<String, String>) = KotlinCompilation().apply {
  this.sources = sources.map { (name, text) -> SourceFile.kotlin(name, text.trimIndent()) }
  compilerPluginRegistrars = listOf(PathsPluginRegistrar())
  inheritClassPath = true
  jvmTarget = "11"
}.compile()
