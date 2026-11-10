package dev.kpaths.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/** Installs KPaths' compiler plugin for supported Kotlin compilations. */
public class KPathsGradlePlugin : KotlinCompilerPluginSupportPlugin {
  override fun apply(target: Project) {
    // The Kotlin Gradle plugin calls isApplicable after the Kotlin compilation is available.
  }

  override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean {
    return kotlinCompilation.platformType in setOf(
      KotlinPlatformType.common, KotlinPlatformType.jvm, KotlinPlatformType.androidJvm, KotlinPlatformType.native,
    )
  }

  override fun getCompilerPluginId(): String = "dev.kpaths.compiler"

  override fun getPluginArtifact(): SubpluginArtifact =
    SubpluginArtifact(KPATHS_GROUP, "kpaths-compiler-plugin-jvm", KPATHS_VERSION)

  override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> =
    kotlinCompilation.target.project.provider { emptyList() }
}
