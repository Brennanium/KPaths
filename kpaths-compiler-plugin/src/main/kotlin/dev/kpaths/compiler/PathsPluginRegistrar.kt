package dev.kpaths.compiler

import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration

/** Entry point for the experimental K2 compiler plugin. */
public class PathsPluginRegistrar : CompilerPluginRegistrar() {
  override val pluginId: String = PathsPluginRegistrar.pluginId
  override val supportsK2: Boolean = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) = Unit

  internal companion object {
    const val pluginId: String = "dev.kpaths.compiler"
  }
}
