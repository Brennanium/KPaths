package dev.kpaths.compiler

import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

/** Registers the declaration-generation extension used during source resolution. */
@OptIn(ExperimentalKPathsCompilerApi::class)
internal class PathsFirRegistrar(
  private val adapterBridge: KPathsAdapterBridge,
  private val firSnapshot: PathsFirSnapshot? = null,
) : FirExtensionRegistrar() {
  override fun ExtensionRegistrarContext.configurePlugin() {
    +{ session: org.jetbrains.kotlin.fir.FirSession -> KPathsGenerationRegistry(session, adapterBridge) }
    +::PathsDeclarationGenerator
    +::PathsRootCasePathableSupertypeGenerator
    +::PathsDiagnosticCheckers
    firSnapshot?.let { snapshot ->
      +{ session: org.jetbrains.kotlin.fir.FirSession -> PathsFirSnapshotCheckers(session, snapshot) }
    }
  }
}
