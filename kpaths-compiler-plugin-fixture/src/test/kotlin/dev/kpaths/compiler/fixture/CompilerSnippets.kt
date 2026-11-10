@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package dev.kpaths.compiler.fixture

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import dev.kpaths.compiler.PathsPluginRegistrar
import dev.kpaths.compiler.contributor.ContributedPathsPluginRegistrar

/** Compile one or more source snippets with the KPaths plugin in process. */
internal fun compileSnippet(vararg sources: Pair<String, String>) = KotlinCompilation().apply {
  this.sources = sources.map { (name, text) -> SourceFile.kotlin(name, text.trimIndent()) }
  compilerPluginRegistrars = listOf(PathsPluginRegistrar())
  inheritClassPath = true
  jvmTarget = "11"
}.compile()

/** Compile with an independently registered integration plugin as well as KPaths. */
internal fun compileSnippetWithContributor(vararg sources: Pair<String, String>) = KotlinCompilation().apply {
  this.sources = sources.map { (name, text) -> SourceFile.kotlin(name, text.trimIndent()) }
  compilerPluginRegistrars = listOf(ContributedPathsPluginRegistrar(), PathsPluginRegistrar())
  inheritClassPath = true
  jvmTarget = "11"
}.compile()
