package dev.kpaths.compiler

import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

/** Registers the declaration-generation extension used during source resolution. */
@OptIn(ExperimentalKPathsCompilerApi::class)
internal class PathsFirRegistrar : FirExtensionRegistrar() {
  override fun ExtensionRegistrarContext.configurePlugin() {
    +::KPathsGenerationRegistry
    +::PathsDeclarationGenerator
    +::PathsRootCasePathableSupertypeGenerator
    +::PathsDiagnosticCheckers
  }
}
