package dev.kpaths.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

/** Registers the FIR declaration and IR-body halves of the experimental `Paths` generator. */
public class PathsPluginRegistrar : CompilerPluginRegistrar() {
  override val pluginId: String = PathsPluginRegistrar.pluginId
  override val supportsK2: Boolean = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    FirExtensionRegistrarAdapter.registerExtension(PathsFirRegistrar())
    IrGenerationExtension.registerExtension(
      PathsIrGenerationExtension(configuration.get(KPathsCompilerConfiguration.irSnapshotPath)),
    )
  }

  internal companion object {
    const val pluginId: String = "dev.kpaths.compiler"
  }
}
