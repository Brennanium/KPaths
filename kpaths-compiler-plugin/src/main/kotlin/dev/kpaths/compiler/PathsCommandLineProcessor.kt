package dev.kpaths.compiler

import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

/** Test-only compiler options for observing the experimental generator's real output. */
public class PathsCommandLineProcessor : CommandLineProcessor {
  override val pluginId: String = PathsPluginRegistrar.pluginId

  override val pluginOptions: Collection<CliOption> = listOf(
    CliOption(
      optionName = "irSnapshot",
      valueDescription = "path",
      description = "Writes a normalized post-generation KPaths IR snapshot to this path.",
      required = false,
      allowMultipleOccurrences = false,
    ),
  )

  override fun processOption(
    option: AbstractCliOption,
    value: String,
    configuration: CompilerConfiguration,
  ) {
    when (option.optionName) {
      "irSnapshot" -> configuration.put(KPathsCompilerConfiguration.irSnapshotPath, value)
      else -> error("Unknown KPaths compiler-plugin option '${option.optionName}'.")
    }
  }
}

/** Internal configuration shared between the command-line processor and plugin registrar. */
internal object KPathsCompilerConfiguration {
  val irSnapshotPath: CompilerConfigurationKey<String> =
    CompilerConfigurationKey.create("kpaths.irSnapshotPath")
}
